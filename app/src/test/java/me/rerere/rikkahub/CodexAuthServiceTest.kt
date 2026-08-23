package me.rerere.rikkahub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import me.rerere.ai.provider.ProviderProxy
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.providers.codex.CodexCredential
import me.rerere.ai.provider.providers.codex.CodexDeviceCode
import me.rerere.ai.provider.providers.codex.CodexProtocolClient
import me.rerere.ai.provider.providers.codex.CodexProtocolException
import me.rerere.ai.provider.providers.codex.CodexQuotaSnapshot
import me.rerere.rikkahub.data.ai.codex.CodexAuthService
import me.rerere.rikkahub.data.ai.codex.CodexCredentialStore
import me.rerere.rikkahub.data.ai.codex.CodexCredentialTransactionGate
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

class CodexAuthServiceTest {
    @Test
    fun `concurrent expired requests share one refresh`() = runBlocking {
        val provider = ProviderSetting.OpenAICodex()
        val store = InMemoryCredentialStore().apply {
            write(provider.id, credential(accessToken = "expired", expiresAt = 0L))
        }
        val protocol = FakeCodexProtocolClient()
        val service = CodexAuthService(store, CodexCredentialTransactionGate(), protocol)

        val credentials = coroutineScope {
            List(12) {
                async(Dispatchers.Default) { service.requireValidCredential(provider) }
            }.awaitAll()
        }

        assertEquals(1, protocol.refreshCalls.get())
        assertTrue(credentials.all { it.accessToken == "refreshed" })
    }

    @Test
    fun `quota 401 refreshes once and retries once`() = runBlocking {
        val provider = ProviderSetting.OpenAICodex()
        val store = InMemoryCredentialStore().apply {
            write(provider.id, credential(accessToken = "old", expiresAt = Long.MAX_VALUE))
        }
        val protocol = FakeCodexProtocolClient(quotaRejectsOldToken = true)
        val service = CodexAuthService(store, CodexCredentialTransactionGate(), protocol)

        service.readQuota(provider)

        assertEquals(1, protocol.refreshCalls.get())
        assertEquals(2, protocol.quotaCalls.get())
    }

    @Test
    fun `quota reads use the ten minute cache and expire afterwards`() = runBlocking {
        val provider = ProviderSetting.OpenAICodex()
        val store = InMemoryCredentialStore().apply {
            write(provider.id, credential(accessToken = "access", expiresAt = Long.MAX_VALUE))
        }
        val protocol = FakeCodexProtocolClient()
        var nowMillis = 1_000L
        val service = CodexAuthService(
            credentialStore = store,
            credentialTransactionGate = CodexCredentialTransactionGate(),
            protocolClient = protocol,
            nowMillis = { nowMillis },
        )

        service.readQuota(provider)
        nowMillis += 9 * 60 * 1_000L
        service.readQuota(provider)
        assertEquals(1, protocol.quotaCalls.get())

        nowMillis += 60 * 1_000L
        service.readQuota(provider)
        assertEquals(2, protocol.quotaCalls.get())
    }

    @Test
    fun `manual quota refresh bypasses cache`() = runBlocking {
        val provider = ProviderSetting.OpenAICodex()
        val store = InMemoryCredentialStore().apply {
            write(provider.id, credential(accessToken = "access", expiresAt = Long.MAX_VALUE))
        }
        val protocol = FakeCodexProtocolClient()
        val service = CodexAuthService(store, CodexCredentialTransactionGate(), protocol)

        service.readQuota(provider)
        service.refreshQuota(provider)

        assertEquals(2, protocol.quotaCalls.get())
    }

    @Test
    fun `quota cache is not reused after the access token changes`() = runBlocking {
        val provider = ProviderSetting.OpenAICodex()
        val store = InMemoryCredentialStore().apply {
            write(provider.id, credential(accessToken = "first", expiresAt = Long.MAX_VALUE))
        }
        val protocol = FakeCodexProtocolClient()
        val service = CodexAuthService(store, CodexCredentialTransactionGate(), protocol)

        service.readQuota(provider)
        store.write(provider.id, credential(accessToken = "second", expiresAt = Long.MAX_VALUE))
        service.readQuota(provider)

        assertEquals(2, protocol.quotaCalls.get())
    }

    @Test
    fun `logout clears the quota cache`() = runBlocking {
        val provider = ProviderSetting.OpenAICodex()
        val store = InMemoryCredentialStore().apply {
            write(provider.id, credential(accessToken = "first", expiresAt = Long.MAX_VALUE))
        }
        val protocol = FakeCodexProtocolClient()
        val service = CodexAuthService(store, CodexCredentialTransactionGate(), protocol)

        service.readQuota(provider)
        service.logout(provider.id)
        store.write(provider.id, credential(accessToken = "second", expiresAt = Long.MAX_VALUE))
        service.readQuota(provider)

        assertEquals(2, protocol.quotaCalls.get())
    }

    @Test
    fun `concurrent quota reads share one network request`() = runBlocking {
        val provider = ProviderSetting.OpenAICodex()
        val store = InMemoryCredentialStore().apply {
            write(provider.id, credential(accessToken = "access", expiresAt = Long.MAX_VALUE))
        }
        val protocol = FakeCodexProtocolClient()
        val service = CodexAuthService(store, CodexCredentialTransactionGate(), protocol)

        coroutineScope {
            List(12) {
                async(Dispatchers.Default) { service.readQuota(provider) }
            }.awaitAll()
        }

        assertEquals(1, protocol.quotaCalls.get())
    }

    @Test
    fun `invalid refresh clears credentials and requires login`() = runBlocking {
        val provider = ProviderSetting.OpenAICodex()
        val store = InMemoryCredentialStore().apply {
            write(provider.id, credential(accessToken = "expired", expiresAt = 0L))
        }
        val protocol = FakeCodexProtocolClient(refreshError = CodexProtocolException(401, "invalid grant"))
        val service = CodexAuthService(store, CodexCredentialTransactionGate(), protocol)

        val failure = runCatching { service.requireValidCredential(provider) }.exceptionOrNull()

        assertTrue(failure is CodexProtocolException)
        assertNull(store.read(provider.id))
    }

    @Test
    fun `logout during refresh cannot revive credentials`() = runBlocking {
        val provider = ProviderSetting.OpenAICodex()
        val store = InMemoryCredentialStore().apply {
            write(provider.id, credential(accessToken = "expired", expiresAt = 0L))
        }
        val protocol = FakeCodexProtocolClient()
        val service = CodexAuthService(store, CodexCredentialTransactionGate(), protocol)

        val refresh = async(Dispatchers.Default) { service.requireValidCredential(provider) }
        while (protocol.refreshCalls.get() == 0) delay(1L)
        service.logout(provider.id)
        refresh.await()

        assertNull(store.read(provider.id))
    }

    @Test
    fun `logout during device login prevents late credential write`() = runBlocking {
        val provider = ProviderSetting.OpenAICodex()
        val store = InMemoryCredentialStore()
        val protocol = FakeCodexProtocolClient()
        val service = CodexAuthService(store, CodexCredentialTransactionGate(), protocol)
        val code = service.startDeviceLogin(provider)

        val completion = async(Dispatchers.Default) {
            runCatching { service.completeDeviceLogin(provider, code) }.exceptionOrNull()
        }
        while (protocol.devicePollCalls.get() == 0) delay(1L)
        service.logout(provider.id)

        assertTrue(completion.await() is CancellationException)
        assertNull(store.read(provider.id))
    }

    @Test
    fun `device login commits associated provider state before returning`() = runBlocking {
        val provider = ProviderSetting.OpenAICodex()
        val store = InMemoryCredentialStore()
        val service = CodexAuthService(
            store,
            CodexCredentialTransactionGate(),
            FakeCodexProtocolClient(),
        )
        val code = service.startDeviceLogin(provider)
        var associatedStateCommitted = false

        service.completeDeviceLogin(provider, code) {
            assertEquals("device-access", store.read(provider.id)?.accessToken)
            associatedStateCommitted = true
        }

        assertTrue(associatedStateCommitted)
        assertEquals("device-access", store.read(provider.id)?.accessToken)
    }

    @Test
    fun `failed associated state commit restores previous credential`() = runBlocking {
        val provider = ProviderSetting.OpenAICodex()
        val store = InMemoryCredentialStore().apply {
            write(provider.id, credential(accessToken = "previous", expiresAt = Long.MAX_VALUE))
        }
        val service = CodexAuthService(
            store,
            CodexCredentialTransactionGate(),
            FakeCodexProtocolClient(),
        )
        val code = service.startDeviceLogin(provider)

        val failure = runCatching {
            service.completeDeviceLogin(provider, code) {
                error("settings write failed")
            }
        }.exceptionOrNull()

        assertEquals("settings write failed", failure?.message)
        assertEquals("previous", store.read(provider.id)?.accessToken)
    }

    @Test
    fun `cancelled UI collector can resume the same device login`() = runBlocking {
        val provider = ProviderSetting.OpenAICodex()
        val store = InMemoryCredentialStore()
        val protocol = FakeCodexProtocolClient()
        val service = CodexAuthService(store, CodexCredentialTransactionGate(), protocol)
        val firstCode = service.startDeviceLogin(provider)
        val firstCollector = async(Dispatchers.Default) {
            service.completeDeviceLogin(provider, firstCode)
        }
        while (protocol.devicePollCalls.get() == 0) delay(1L)

        firstCollector.cancelAndJoin()
        val resumedCode = service.startDeviceLogin(provider)
        service.completeDeviceLogin(provider, resumedCode)

        assertEquals(firstCode, resumedCode)
        assertEquals(1, protocol.deviceCodeCalls.get())
        assertEquals("device-access", store.read(provider.id)?.accessToken)
    }

    private fun credential(accessToken: String, expiresAt: Long) = CodexCredential(
        accessToken = accessToken,
        refreshToken = "refresh",
        expiresAtEpochMillis = expiresAt,
        accountId = "account",
    )
}

private class InMemoryCredentialStore : CodexCredentialStore {
    private val values = ConcurrentHashMap<String, CodexCredential>()
    override val revision: StateFlow<Long> = MutableStateFlow(0L)

    override suspend fun read(providerId: Uuid): CodexCredential? = values[providerId.toString()]

    override suspend fun readAll(): Map<Uuid, CodexCredential> = values.mapKeys { (id, _) -> Uuid.parse(id) }

    override suspend fun write(providerId: Uuid, credential: CodexCredential) {
        values[providerId.toString()] = credential
    }

    override suspend fun replaceAll(credentials: Map<Uuid, CodexCredential>) {
        values.clear()
        credentials.forEach { (providerId, credential) ->
            values[providerId.toString()] = credential
        }
    }

    override suspend fun remove(providerId: Uuid) {
        values.remove(providerId.toString())
    }
}

private class FakeCodexProtocolClient(
    private val quotaRejectsOldToken: Boolean = false,
    private val refreshError: CodexProtocolException? = null,
) : CodexProtocolClient(OkHttpClient()) {
    val refreshCalls = AtomicInteger()
    val quotaCalls = AtomicInteger()
    val deviceCodeCalls = AtomicInteger()
    val devicePollCalls = AtomicInteger()

    override suspend fun requestDeviceCode(proxy: ProviderProxy): CodexDeviceCode {
        deviceCodeCalls.incrementAndGet()
        return CodexDeviceCode(
            deviceAuthId = "device-auth",
            userCode = "ABCD-EFGH",
            verificationUrl = "https://example.com/device",
            expiresAtEpochMillis = Long.MAX_VALUE,
            intervalMillis = 1L,
        )
    }

    override suspend fun pollDeviceAuthorization(
        deviceCode: CodexDeviceCode,
        proxy: ProviderProxy,
    ): CodexCredential {
        devicePollCalls.incrementAndGet()
        delay(50L)
        return CodexCredential(
            accessToken = "device-access",
            refreshToken = "device-refresh",
            expiresAtEpochMillis = Long.MAX_VALUE,
            accountId = "device-account",
        )
    }

    override suspend fun refreshCredential(
        credential: CodexCredential,
        proxy: ProviderProxy,
    ): CodexCredential {
        refreshCalls.incrementAndGet()
        delay(50L)
        refreshError?.let { throw it }
        return credential.copy(
            accessToken = "refreshed",
            expiresAtEpochMillis = Long.MAX_VALUE,
        )
    }

    override suspend fun readQuota(
        credential: CodexCredential,
        proxy: ProviderProxy,
    ): CodexQuotaSnapshot {
        quotaCalls.incrementAndGet()
        if (quotaRejectsOldToken && credential.accessToken == "old") {
            throw CodexProtocolException(401, "unauthorized")
        }
        return CodexQuotaSnapshot(planType = "plus", buckets = emptyList(), creditBalance = null)
    }
}
