package me.rerere.rikkahub.data.localai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.LocalModelDao
import me.rerere.rikkahub.data.db.entity.LocalModelEntity
import java.io.File
import java.security.MessageDigest
import kotlin.uuid.Uuid

enum class LocalModelFormat(val extension: String) {
    GGUF("gguf"),
    LITERT_LM("litertlm"),
}

enum class LocalModelState { DOWNLOADING, PAUSED, READY, FAILED, MISSING }
enum class LocalModelSource { CATALOG, IMPORT }

data class LocalModelRecord(
    val entity: LocalModelEntity,
    val format: LocalModelFormat,
    val state: LocalModelState,
)

/** Owns private model files and keeps the selectable ProviderSetting in sync with Room. */
class LocalModelRepository(
    private val context: Context,
    private val dao: LocalModelDao,
    private val settingsStore: SettingsStore,
) {
    private val root = File(context.noBackupFilesDir, "local-ai/models")

    fun observeModels(): Flow<List<LocalModelRecord>> = dao.observeAll().map { entities ->
        entities.mapNotNull(::toRecord)
    }

    suspend fun get(modelId: Uuid): LocalModelRecord? = toRecord(dao.get(modelId.toString()))

    suspend fun importModel(uri: Uri): Result<Model> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = queryDisplayName(uri) ?: "Imported model"
            val format = detectFormat(displayName)
                ?: error("Only .gguf and .litertlm language model files are supported")
            val id = Uuid.random()
            val directory = File(root, id.toString())
            check(directory.mkdirs() || directory.isDirectory) { "Unable to create model directory" }
            val temporary = File(directory, "model.${format.extension}.part")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temporary.outputStream().use(input::copyTo)
                } ?: error("Could not open the selected file")
                check(temporary.length() > 0L) { "The selected model is empty" }
                validateHeader(temporary, format)
                val finalFile = File(directory, "model.${format.extension}")
                check(temporary.renameTo(finalFile)) { "Unable to finish importing the model" }
                val model = Model(
                    id = id,
                    modelId = id.toString(),
                    displayName = displayName.substringBeforeLast('.').ifBlank { "Imported model" },
                    abilities = emptyList(),
                )
                dao.upsert(
                    LocalModelEntity(
                        modelId = id.toString(),
                        displayName = model.displayName,
                        format = format.name,
                        relativePath = relativePath(finalFile),
                        source = LocalModelSource.IMPORT.name,
                        sha256 = sha256(finalFile),
                        sizeBytes = finalFile.length(),
                        state = LocalModelState.READY.name,
                    ),
                )
                ensureProviderModel(model)
                model
            } catch (t: Throwable) {
                directory.deleteRecursively()
                throw t
            }
        }
    }

    suspend fun remove(modelId: Uuid) = withContext(Dispatchers.IO) {
        dao.get(modelId.toString())?.let { entity ->
            safeModelFile(entity.relativePath)?.parentFile?.deleteRecursively()
        }
        dao.delete(modelId.toString())
        settingsStore.update { settings ->
            settings.copy(providers = settings.providers.map { provider ->
                if (provider is ProviderSetting.Local) provider.delModel(Model(id = modelId)) else provider
            })
        }
    }

    suspend fun reconcile() = withContext(Dispatchers.IO) {
        val ready = dao.getAll().mapNotNull { entity ->
            val file = safeModelFile(entity.relativePath)
            if (entity.state == LocalModelState.READY.name && (file == null || !file.isFile)) {
                dao.upsert(entity.copy(state = LocalModelState.MISSING.name))
                null
            } else if (entity.state == LocalModelState.READY.name) entity else null
        }
        val readyIds = ready.map { it.modelId }.toSet()
        settingsStore.update { settings ->
            val local = settings.providers.filterIsInstance<ProviderSetting.Local>().firstOrNull()
                ?: ProviderSetting.Local()
            val retained = local.models.filter { it.id.toString() in readyIds }
            val added = ready.filter { entity -> retained.none { it.id.toString() == entity.modelId } }
                .map(::modelFromEntity)
            val normalized = local.copy(models = retained + added)
            settings.copy(
                providers = settings.providers.filterNot { it is ProviderSetting.Local } + normalized,
            )
        }
    }

    fun fileFor(record: LocalModelRecord): File =
        requireNotNull(safeModelFile(record.entity.relativePath)) { "Invalid local model path" }

    private suspend fun ensureProviderModel(model: Model) {
        settingsStore.update { settings ->
            val local = settings.providers.filterIsInstance<ProviderSetting.Local>().firstOrNull()
                ?: ProviderSetting.Local()
            val updated = if (local.models.any { it.id == model.id }) local else local.addModel(model) as ProviderSetting.Local
            settings.copy(providers = settings.providers.filterNot { it is ProviderSetting.Local } + updated)
        }
    }

    private fun modelFromEntity(entity: LocalModelEntity): Model = Model(
        id = Uuid.parse(entity.modelId),
        modelId = entity.modelId,
        displayName = entity.displayName,
        abilities = if (entity.supportsTools) listOf(ModelAbility.TOOL) else emptyList(),
    )

    private fun toRecord(entity: LocalModelEntity?): LocalModelRecord? = entity?.let {
        val format = runCatching { LocalModelFormat.valueOf(it.format) }.getOrNull() ?: return null
        val state = runCatching { LocalModelState.valueOf(it.state) }.getOrNull() ?: return null
        LocalModelRecord(it, format, state)
    }

    private fun detectFormat(name: String): LocalModelFormat? = when (name.substringAfterLast('.', "").lowercase()) {
        LocalModelFormat.GGUF.extension -> LocalModelFormat.GGUF
        LocalModelFormat.LITERT_LM.extension -> LocalModelFormat.LITERT_LM
        else -> null
    }

    private fun validateHeader(file: File, format: LocalModelFormat) {
        if (format != LocalModelFormat.GGUF) return
        val header = ByteArray(4)
        file.inputStream().use { check(it.read(header) == 4) { "Invalid GGUF file" } }
        check(header.decodeToString() == "GGUF") { "Invalid GGUF file" }
    }

    private fun relativePath(file: File): String = file.relativeTo(File(context.noBackupFilesDir, "local-ai")).path

    private fun safeModelFile(relativePath: String): File? {
        val localRoot = File(context.noBackupFilesDir, "local-ai").canonicalFile
        val candidate = File(localRoot, relativePath).canonicalFile
        return candidate.takeIf { it.path.startsWith(localRoot.path + File.separator) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun queryDisplayName(uri: Uri): String? = uri.lastPathSegment?.substringAfterLast('/')
}
