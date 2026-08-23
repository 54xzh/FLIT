package me.rerere.rikkahub.data.ai.codex

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.rerere.ai.provider.providers.codex.CodexCredential
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import kotlin.uuid.Uuid

interface CodexCredentialStore {
    val revision: StateFlow<Long>
    suspend fun read(providerId: Uuid): CodexCredential?
    suspend fun readAll(): Map<Uuid, CodexCredential>
    suspend fun write(providerId: Uuid, credential: CodexCredential)
    suspend fun replaceAll(credentials: Map<Uuid, CodexCredential>)
    suspend fun remove(providerId: Uuid)
}

class CodexCredentialTransactionGate {
    private val mutex = Mutex()
    private var globalVersion = 0L
    private val providerVersions = mutableMapOf<String, Long>()

    data class Version internal constructor(
        internal val global: Long,
        internal val provider: Long,
    )

    suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }

    suspend fun version(providerId: Uuid): Version = mutex.withLock {
        Version(globalVersion, providerVersions[providerId.toString()] ?: 0L)
    }

    suspend fun <T> mutate(providerId: Uuid, block: suspend () -> T): T = mutex.withLock {
        block().also {
            val key = providerId.toString()
            providerVersions[key] = (providerVersions[key] ?: 0L) + 1L
        }
    }

    suspend fun mutateIfCurrent(
        providerId: Uuid,
        expected: Version,
        block: suspend () -> Unit,
    ): Boolean = mutex.withLock {
        val key = providerId.toString()
        val current = Version(globalVersion, providerVersions[key] ?: 0L)
        if (current != expected) return@withLock false
        block()
        providerVersions[key] = current.provider + 1L
        true
    }

    suspend fun <T> mutateAll(block: suspend () -> T): T = mutex.withLock {
        block().also { globalVersion += 1L }
    }
}

@Serializable
internal data class CodexCredentialFile(
    val version: Int = 1,
    val credentials: Map<String, CodexCredential> = emptyMap(),
)

internal fun encodeCodexCredentials(credentials: Map<Uuid, CodexCredential>): String =
    JsonInstant.encodeToString(
        CodexCredentialFile(
            credentials = credentials.mapKeys { (providerId, _) -> providerId.toString() },
        )
    )

internal fun decodeCodexCredentials(content: String): Map<Uuid, CodexCredential> {
    val stored = JsonInstant.decodeFromString<CodexCredentialFile>(content)
    require(stored.version == 1) { "Unsupported Codex credential file version: ${stored.version}" }
    return stored.credentials.mapNotNull { (providerId, credential) ->
        runCatching { Uuid.parse(providerId) }.getOrNull()?.let { it to credential }
    }.toMap()
}

/**
 * Plaintext OAuth storage in the app-private no-backup directory. FLIT's full export carries this
 * file explicitly, while keeping it outside the files tree exposed by the optional Web service.
 */
class AndroidCodexCredentialStore(
    context: Context,
) : CodexCredentialStore {
    private val file = File(context.noBackupFilesDir, FILE_NAME)
    private val mutex = Mutex()
    private val mutableRevision = MutableStateFlow(0L)
    override val revision: StateFlow<Long> = mutableRevision.asStateFlow()

    override suspend fun read(providerId: Uuid): CodexCredential? = withContext(Dispatchers.IO) {
        mutex.withLock { readAllLocked()[providerId] }
    }

    override suspend fun readAll(): Map<Uuid, CodexCredential> = withContext(Dispatchers.IO) {
        mutex.withLock { readAllLocked() }
    }

    override suspend fun write(providerId: Uuid, credential: CodexCredential) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                writeAllLocked(readAllLocked() + (providerId to credential))
                mutableRevision.update { it + 1L }
            }
        }

    override suspend fun replaceAll(credentials: Map<Uuid, CodexCredential>) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                writeAllLocked(credentials)
                mutableRevision.update { it + 1L }
            }
        }

    override suspend fun remove(providerId: Uuid): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = readAllLocked()
            if (providerId in current) {
                writeAllLocked(current - providerId)
                mutableRevision.update { it + 1L }
            }
        }
    }

    private fun readAllLocked(): Map<Uuid, CodexCredential> {
        if (!file.exists()) return emptyMap()
        return decodeCodexCredentials(AtomicFile(file).readFully().decodeToString())
    }

    private fun writeAllLocked(credentials: Map<Uuid, CodexCredential>) {
        file.parentFile?.let { parent ->
            check(parent.mkdirs() || parent.isDirectory) {
                "Failed to create Codex credential directory"
            }
        }
        val atomicFile = AtomicFile(file)
        val stream = atomicFile.startWrite()
        try {
            stream.write(encodeCodexCredentials(credentials).encodeToByteArray())
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            atomicFile.failWrite(stream)
            throw error
        }
    }

    companion object {
        const val FILE_NAME = "codex_credentials.json"
    }
}
