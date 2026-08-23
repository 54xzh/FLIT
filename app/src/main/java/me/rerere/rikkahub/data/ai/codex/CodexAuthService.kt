package me.rerere.rikkahub.data.ai.codex

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.codex.CodexAuthRequiredException
import me.rerere.ai.provider.providers.codex.CodexCredential
import me.rerere.ai.provider.providers.codex.CodexDeviceCode
import me.rerere.ai.provider.providers.codex.CodexProtocolClient
import me.rerere.ai.provider.providers.codex.CodexProtocolException
import me.rerere.ai.provider.providers.codex.CodexQuotaSnapshot
import me.rerere.ai.provider.providers.codex.CodexSessionProvider
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.uuid.Uuid

interface CodexLoginService {
    suspend fun startDeviceLogin(provider: ProviderSetting.OpenAICodex): CodexDeviceCode

    fun cancelDeviceLogin(providerId: Uuid)

    suspend fun completeDeviceLogin(
        provider: ProviderSetting.OpenAICodex,
        deviceCode: CodexDeviceCode,
        commitAssociatedState: suspend () -> Unit = {},
    ): CodexCredential
}

class CodexAuthService(
    private val credentialStore: CodexCredentialStore,
    private val credentialTransactionGate: CodexCredentialTransactionGate,
    private val protocolClient: CodexProtocolClient,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : CodexSessionProvider, CodexLoginService {
    private data class PendingDeviceLogin(
        val code: CodexDeviceCode,
        val version: CodexCredentialTransactionGate.Version,
    )

    private data class CachedQuota(
        val accessToken: String,
        val snapshot: CodexQuotaSnapshot,
        val fetchedAtEpochMillis: Long,
    )

    private val refreshMutexes = ConcurrentHashMap<String, Mutex>()
    private val pendingDeviceLogins = ConcurrentHashMap<String, PendingDeviceLogin>()
    private val quotaMutexes = ConcurrentHashMap<String, Mutex>()
    private val quotaCache = ConcurrentHashMap<String, CachedQuota>()
    private val quotaCacheGenerations = ConcurrentHashMap<String, Long>()
    private val deviceLoginStartMutex = Mutex()
    val credentialRevision = credentialStore.revision

    override suspend fun startDeviceLogin(provider: ProviderSetting.OpenAICodex): CodexDeviceCode {
        val key = provider.id.toString()
        return deviceLoginStartMutex.withLock {
            pendingDeviceLogins[key]
                ?.code
                ?.takeIf { it.expiresAtEpochMillis > now() }
                ?: run {
                    val version = credentialTransactionGate.version(provider.id)
                    val code = protocolClient.requestDeviceCode(provider.proxy)
                    val pending = PendingDeviceLogin(
                        code = code,
                        version = version,
                    )
                    pendingDeviceLogins[key] = pending
                    pending.code
                }
        }
    }

    override fun cancelDeviceLogin(providerId: Uuid) {
        pendingDeviceLogins.remove(providerId.toString())
    }

    override suspend fun completeDeviceLogin(
        provider: ProviderSetting.OpenAICodex,
        deviceCode: CodexDeviceCode,
        commitAssociatedState: suspend () -> Unit,
    ): CodexCredential {
        val key = provider.id.toString()
        val pending = pendingDeviceLogins[key]
            ?.takeIf { it.code == deviceCode }
            ?: throw CancellationException("Device login is no longer active")
        var terminal = false
        try {
            val credential = protocolClient.pollDeviceAuthorization(deviceCode, provider.proxy)
            val committed = credentialTransactionGate.mutateIfCurrent(provider.id, pending.version) {
                if (pendingDeviceLogins[key] != pending) {
                    throw CancellationException("Device login is no longer active")
                }
                val previous = credentialStore.read(provider.id)
                try {
                    credentialStore.write(provider.id, credential)
                    commitAssociatedState()
                } catch (error: Throwable) {
                    if (previous == null) {
                        credentialStore.remove(provider.id)
                    } else {
                        credentialStore.write(provider.id, previous)
                    }
                    throw error
                }
            }
            if (!committed) {
                terminal = true
                throw CancellationException("Device login was superseded")
            }
            terminal = true
            invalidateQuotaCache(key)
            return credential
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            terminal = true
            throw error
        } finally {
            if (terminal) pendingDeviceLogins.remove(key, pending)
        }
    }

    override suspend fun getCredential(providerId: Uuid): CodexCredential? =
        credentialStore.read(providerId)

    override suspend fun requireValidCredential(
        providerSetting: ProviderSetting.OpenAICodex,
    ): CodexCredential {
        val credential = credentialStore.read(providerSetting.id) ?: throw CodexAuthRequiredException()
        return if (credential.expiresAtEpochMillis <= now() + REFRESH_SKEW_MILLIS) {
            refreshCredential(providerSetting, failedAccessToken = null, refreshEvenIfValid = false)
        } else {
            credential
        }
    }

    override suspend fun forceRefreshCredential(
        providerSetting: ProviderSetting.OpenAICodex,
        failedAccessToken: String?,
    ): CodexCredential = refreshCredential(
        providerSetting = providerSetting,
        failedAccessToken = failedAccessToken,
        refreshEvenIfValid = true,
    )

    suspend fun readQuota(providerSetting: ProviderSetting.OpenAICodex): CodexQuotaSnapshot =
        readQuota(providerSetting, forceRefresh = false)

    suspend fun refreshQuota(providerSetting: ProviderSetting.OpenAICodex): CodexQuotaSnapshot =
        readQuota(providerSetting, forceRefresh = true)

    private suspend fun readQuota(
        providerSetting: ProviderSetting.OpenAICodex,
        forceRefresh: Boolean,
    ): CodexQuotaSnapshot {
        val key = providerSetting.id.toString()
        val mutex = quotaMutexes.getOrPut(key) { Mutex() }
        return mutex.withLock {
            val credential = requireValidCredential(providerSetting)
            val cacheGeneration = quotaCacheGenerations[key] ?: 0L
            val now = now()
            quotaCache[key]
                ?.takeIf { cached ->
                    !forceRefresh &&
                        cached.accessToken == credential.accessToken &&
                        now >= cached.fetchedAtEpochMillis &&
                        now - cached.fetchedAtEpochMillis < QUOTA_CACHE_TTL_MILLIS
                }
                ?.snapshot
                ?: readQuotaFromNetwork(providerSetting, credential).also { (snapshot, credentialUsed) ->
                    if (
                        (quotaCacheGenerations[key] ?: 0L) == cacheGeneration &&
                        credentialStore.read(providerSetting.id)?.accessToken == credentialUsed.accessToken
                    ) {
                        quotaCache[key] = CachedQuota(
                            accessToken = credentialUsed.accessToken,
                            snapshot = snapshot,
                            fetchedAtEpochMillis = now(),
                        )
                    }
                }.first
        }
    }

    private suspend fun readQuotaFromNetwork(
        providerSetting: ProviderSetting.OpenAICodex,
        credential: CodexCredential,
    ): Pair<CodexQuotaSnapshot, CodexCredential> {
        return try {
            protocolClient.readQuota(credential, providerSetting.proxy) to credential
        } catch (error: CodexProtocolException) {
            if (error.statusCode != 401) throw error
            val refreshed = forceRefreshCredential(providerSetting, credential.accessToken)
            protocolClient.readQuota(refreshed, providerSetting.proxy) to refreshed
        }
    }

    suspend fun logout(
        providerId: Uuid,
        commitAssociatedState: suspend () -> Unit = {},
    ) {
        credentialTransactionGate.mutate(providerId) {
            val previous = credentialStore.read(providerId)
            try {
                credentialStore.remove(providerId)
                commitAssociatedState()
                refreshMutexes.remove(providerId.toString())
                quotaMutexes.remove(providerId.toString())
                invalidateQuotaCache(providerId.toString())
            } catch (error: Throwable) {
                previous?.let { credentialStore.write(providerId, it) }
                throw error
            }
        }
    }

    private suspend fun refreshCredential(
        providerSetting: ProviderSetting.OpenAICodex,
        failedAccessToken: String?,
        refreshEvenIfValid: Boolean,
    ): CodexCredential {
        val mutex = refreshMutexes.getOrPut(providerSetting.id.toString()) { Mutex() }
        return mutex.withLock {
            credentialTransactionGate.mutate(providerSetting.id) transaction@{
                val current = credentialStore.read(providerSetting.id) ?: throw CodexAuthRequiredException()
                if (failedAccessToken != null && current.accessToken != failedAccessToken) {
                    return@transaction current
                }
                if (!refreshEvenIfValid && current.expiresAtEpochMillis > now() + REFRESH_SKEW_MILLIS) {
                    return@transaction current
                }
                try {
                    protocolClient.refreshCredential(current, providerSetting.proxy).also {
                        credentialStore.write(providerSetting.id, it)
                    }
                } catch (error: CodexProtocolException) {
                    if (error.statusCode == 400 || error.statusCode == 401) {
                        credentialStore.remove(providerSetting.id)
                        invalidateQuotaCache(providerSetting.id.toString())
                    }
                    throw error
                }
            }
        }
    }

    private fun now() = nowMillis()

    private fun invalidateQuotaCache(key: String) {
        quotaCache.remove(key)
        quotaCacheGenerations.compute(key) { _, generation -> (generation ?: 0L) + 1L }
    }

    private companion object {
        const val REFRESH_SKEW_MILLIS = 60_000L
        const val QUOTA_CACHE_TTL_MILLIS = 10 * 60 * 1_000L
    }
}
