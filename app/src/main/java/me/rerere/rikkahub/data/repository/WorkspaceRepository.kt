package me.rerere.rikkahub.data.repository

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.dao.WorkspaceDao
import me.rerere.rikkahub.data.db.entity.SafWorkspaceEntity
import me.rerere.rikkahub.data.db.entity.SandboxRootfsStatus
import me.rerere.rikkahub.data.db.entity.SandboxWorkspaceEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceType
import me.rerere.rikkahub.data.db.entity.toolDefaultNeedsApproval
import me.rerere.rikkahub.data.db.entity.workspaceToolNames
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.workspace.SandboxCommandResult
import me.rerere.rikkahub.workspace.SandboxFileEntry
import me.rerere.rikkahub.workspace.SandboxRootfsInstaller
import me.rerere.rikkahub.workspace.SandboxWorkspaceManager
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

data class Workspace(
    val id: String,
    val name: String,
    val type: WorkspaceType,
    val toolApprovals: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessAt: Long?,
    val treeUri: String? = null,
    val sandbox: SandboxWorkspaceEntity? = null,
) {
    fun toolApprovalOverrides(): Map<String, Boolean> = runCatching {
        JsonInstant.decodeFromString<Map<String, Boolean>>(toolApprovals)
    }.getOrDefault(emptyMap())

    val sandboxStatus: SandboxRootfsStatus?
        get() = sandbox?.rootfsStatus
}

/** 统一工作区仓库。助手只通过 workspaceId 绑定，类型由这里解析。 */
class WorkspaceRepository(
    private val dao: WorkspaceDao,
    private val settingsStore: SettingsStore,
    private val context: Context,
    private val sandboxManager: SandboxWorkspaceManager,
    private val rootfsInstaller: SandboxRootfsInstaller,
) {
    private val sandboxLocks = ConcurrentHashMap<String, Mutex>()

    fun listFlow(): Flow<List<Workspace>> = dao.listFlow().map { records ->
        val resolved = mutableListOf<Workspace>()
        for (record in records) {
            resolve(record)?.let(resolved::add)
        }
        resolved
    }

    suspend fun getById(id: String): Workspace? {
        val record = dao.getById(id) ?: return null
        return resolve(record)
    }

    suspend fun getAll(): List<Workspace> {
        val resolved = mutableListOf<Workspace>()
        for (record in dao.getAll()) {
            resolve(record)?.let(resolved::add)
        }
        return resolved
    }

    suspend fun getByTreeUri(treeUri: String): Workspace? {
        val workspaceId = dao.getSafDetailByTreeUri(treeUri)?.workspaceId ?: return null
        return getById(workspaceId)
    }

    /** 兼容既有调用：创建轻量 SAF 工作区。 */
    suspend fun create(name: String, treeUri: String): Workspace = createLightweight(name, treeUri)

    suspend fun createLightweight(name: String, treeUri: String): Workspace = withContext(Dispatchers.IO) {
        val finalName = validateName(name)
        require(dao.getSafDetailByTreeUri(treeUri) == null) { "Workspace folder is already used" }
        val now = System.currentTimeMillis()
        val record = WorkspaceEntity(
            id = Uuid.random().toString(),
            name = finalName,
            type = WorkspaceType.LIGHTWEIGHT,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsert(record)
        dao.upsertSafDetail(SafWorkspaceEntity(record.id, treeUri))
        resolve(record) ?: error("Failed to create workspace")
    }

    suspend fun createSandbox(name: String): Workspace = withContext(Dispatchers.IO) {
        val finalName = validateName(name)
        val now = System.currentTimeMillis()
        val record = WorkspaceEntity(
            id = Uuid.random().toString(),
            name = finalName,
            type = WorkspaceType.SANDBOX,
            createdAt = now,
            updatedAt = now,
        )
        sandboxManager.ensureWorkspace(record.id)
        dao.upsert(record)
        dao.upsertSandboxDetail(SandboxWorkspaceEntity(workspaceId = record.id))
        resolve(record) ?: error("Failed to create sandbox")
    }

    suspend fun rename(id: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = dao.getById(id) ?: return@withContext false
        dao.upsert(workspace.copy(name = validateName(name, workspace.name, workspace.id), updatedAt = System.currentTimeMillis()))
        true
    }

    suspend fun isNameTaken(name: String, excludeId: String?): Boolean {
        val target = name.trim()
        return dao.getAll().any { it.id != excludeId && it.name.trim() == target }
    }

    suspend fun setToolApproval(id: String, toolName: String, needsApproval: Boolean): Boolean {
        val workspace = getById(id) ?: return false
        require(toolName in workspaceToolNames(workspace.type)) { "Tool is not available for this workspace" }
        return setToolApprovals(id, workspace.toolApprovalOverrides() + (toolName to needsApproval))
    }

    suspend fun setToolApprovals(id: String, overrides: Map<String, Boolean>): Boolean {
        val workspace = getById(id) ?: return false
        val validNames = workspaceToolNames(workspace.type).toSet()
        dao.updateToolApprovals(
            id = id,
            toolApprovals = JsonInstant.encodeToString(overrides.filterKeys(validNames::contains)),
            updatedAt = System.currentTimeMillis(),
        )
        return true
    }

    suspend fun installSandboxRootfs(
        id: String,
        sourceUrl: String,
        onProgress: (String) -> Unit = {},
    ): Boolean = sandboxLock(id).withLock {
        val workspace = requireSandbox(id)
        val previous = workspace.sandbox ?: error("Sandbox details are missing")
        val now = System.currentTimeMillis()
        updateSandboxStatus(id, SandboxRootfsStatus.INSTALLING, sourceUrl, previous.rootfsVersion, previous.rootfsInstalledAt)
        try {
            runInterruptible(Dispatchers.IO) {
                rootfsInstaller.install(id, sourceUrl, onProgress)
            }
            updateSandboxStatus(id, SandboxRootfsStatus.READY, sourceUrl, rootfsVersion(sourceUrl), now)
            true
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                val recovered = if (sandboxManager.hasRootfs(id)) SandboxRootfsStatus.READY else SandboxRootfsStatus.DISABLED
                updateSandboxStatus(id, recovered, previous.rootfsSourceUrl, previous.rootfsVersion, previous.rootfsInstalledAt)
            }
            throw error
        } catch (error: Throwable) {
            updateSandboxStatus(id, SandboxRootfsStatus.BROKEN, sourceUrl, previous.rootfsVersion, previous.rootfsInstalledAt)
            throw error
        }
    }

    suspend fun delete(id: String): Boolean {
        val workspace = getById(id) ?: return false
        if (workspace.type == WorkspaceType.SANDBOX) {
            sandboxLock(id).withLock { deleteInternal(workspace) }
        } else {
            deleteInternal(workspace)
        }
        return true
    }

    private suspend fun deleteInternal(workspace: Workspace) {
        if (workspace.type == WorkspaceType.SANDBOX) {
            withContext(Dispatchers.IO) { sandboxManager.deleteWorkspace(workspace.id) }
            dao.deleteSandboxDetail(workspace.id)
        } else {
            dao.deleteSafDetail(workspace.id)
        }
        dao.deleteById(workspace.id)
        cleanupAssistantReferences(workspace.id)
    }

    suspend fun checkIntegrity() = withContext(Dispatchers.IO) {
        dao.getAll().forEach { record ->
            val workspace = resolve(record) ?: return@forEach
            when (workspace.type) {
                WorkspaceType.LIGHTWEIGHT -> {
                    if (workspace.treeUri == null || !isTreeUriAccessible(workspace.treeUri)) {
                        dao.deleteSafDetail(workspace.id)
                        dao.deleteById(workspace.id)
                        cleanupAssistantReferences(workspace.id)
                    } else if (workspace.toolApprovalOverrides().isEmpty()) {
                        migrateDefaultToolApprovals(workspace)
                    }
                }
                WorkspaceType.SANDBOX -> {
                    val detail = workspace.sandbox ?: return@forEach
                    if (!sandboxManager.workspaceDir(workspace.id).exists()) {
                        dao.deleteSandboxDetail(workspace.id)
                        dao.deleteById(workspace.id)
                        cleanupAssistantReferences(workspace.id)
                    } else if (detail.rootfsStatus == SandboxRootfsStatus.READY && !sandboxManager.hasRootfs(workspace.id)) {
                        updateSandboxStatus(workspace.id, SandboxRootfsStatus.DISABLED, detail.rootfsSourceUrl, detail.rootfsVersion, null)
                    } else if (workspace.toolApprovalOverrides().isEmpty()) {
                        migrateDefaultToolApprovals(workspace)
                    }
                }
            }
        }
    }

    suspend fun listSandboxFiles(id: String, path: String): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        requireSandbox(id)
        sandboxManager.listFiles(id, path).map(SandboxFileEntry::toWorkspaceEntry)
    }

    suspend fun readSandboxText(id: String, path: String): String = withContext(Dispatchers.IO) {
        requireSandbox(id)
        sandboxManager.readText(id, path)
    }

    suspend fun writeSandboxText(id: String, path: String, text: String, overwrite: Boolean): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        requireSandbox(id)
        sandboxManager.writeText(id, path, text, overwrite).toWorkspaceEntry()
    }

    suspend fun importSandboxFile(id: String, path: String, fileName: String, input: InputStream): WorkspaceFileEntry = withContext(Dispatchers.IO) {
        requireSandbox(id)
        sandboxManager.importFile(id, path, fileName, input).toWorkspaceEntry()
    }

    suspend fun exportSandboxFile(id: String, path: String, output: OutputStream) = withContext(Dispatchers.IO) {
        requireSandbox(id)
        sandboxManager.exportFile(id, path, output)
    }

    suspend fun deleteSandboxFile(id: String, path: String, recursive: Boolean): Boolean = withContext(Dispatchers.IO) {
        requireSandbox(id)
        sandboxManager.deleteFile(id, path, recursive)
    }

    suspend fun executeSandboxCommand(
        id: String,
        command: String,
        cwd: String,
        timeoutMillis: Long,
        stdin: ByteArray? = null,
    ): SandboxCommandResult = sandboxLock(id).withLock {
        val workspace = requireSandbox(id)
        check(workspace.sandboxStatus == SandboxRootfsStatus.READY) { "Rootfs is not ready" }
        runInterruptible(Dispatchers.IO) { sandboxManager.executeCommand(id, command, cwd, timeoutMillis, stdin) }
    }

    fun friendlyName(treeUri: String, fallback: String): String {
        val doc = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) }.getOrNull()
        return doc?.name?.takeIf { it.isNotBlank() } ?: fallback
    }

    fun friendlyShortPath(treeUri: String): String {
        val decoded = runCatching {
            val raw = Uri.parse(treeUri)
            val path = Uri.decode(raw.pathSegments.dropWhile { it != "tree" }.drop(1).joinToString("/")).replace(':', '/')
            path.substringAfter('/').takeIf { it.isNotBlank() }
        }.getOrNull()
        if (!decoded.isNullOrBlank()) return "/$decoded"
        val friendly = friendlyName(treeUri, treeUri)
        return if (friendly != treeUri) "/$friendly" else treeUri
    }

    fun resolveRoot(treeUri: String): DocumentFile? = runCatching {
        DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
    }.getOrNull()?.takeIf { it.isDirectory }

    private suspend fun resolve(record: WorkspaceEntity): Workspace? = when (record.type) {
        WorkspaceType.LIGHTWEIGHT -> dao.getSafDetail(record.id)?.let { detail -> record.toWorkspace(treeUri = detail.treeUri) }
        WorkspaceType.SANDBOX -> dao.getSandboxDetail(record.id)?.let { detail -> record.toWorkspace(sandbox = detail) }
    }

    private fun WorkspaceEntity.toWorkspace(treeUri: String? = null, sandbox: SandboxWorkspaceEntity? = null) = Workspace(
        id = id,
        name = name,
        type = type,
        toolApprovals = toolApprovals,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastAccessAt = lastAccessAt,
        treeUri = treeUri,
        sandbox = sandbox,
    )

    private suspend fun requireSandbox(id: String): Workspace {
        val workspace = getById(id) ?: error("Workspace not found")
        require(workspace.type == WorkspaceType.SANDBOX) { "Workspace is not a sandbox" }
        return workspace
    }

    private suspend fun updateSandboxStatus(id: String, status: SandboxRootfsStatus, sourceUrl: String?, version: String?, installedAt: Long?) {
        dao.updateSandboxRootfs(id, status, sourceUrl, version, installedAt)
    }

    private fun rootfsVersion(url: String): String = url.substringBefore('?').substringAfterLast('/').take(128)

    private suspend fun validateName(name: String, fallback: String = "Workspace", excludeId: String? = null): String {
        val finalName = name.trim().ifBlank { fallback }
        require(!isNameTaken(finalName, excludeId)) { "Workspace name already exists: $finalName" }
        return finalName
    }

    private suspend fun migrateDefaultToolApprovals(workspace: Workspace) {
        setToolApprovals(workspace.id, workspaceToolNames(workspace.type).associateWith(::toolDefaultNeedsApproval))
    }

    private fun isTreeUriAccessible(uriString: String): Boolean {
        val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uriString)) }.getOrNull()
        return root?.isDirectory == true
    }

    private suspend fun cleanupAssistantReferences(workspaceId: String) {
        settingsStore.update { settings ->
            settings.copy(assistants = settings.assistants.map { assistant ->
                if (assistant.workspaceId == workspaceId) assistant.copy(workspaceId = null) else assistant
            })
        }
    }

    private fun sandboxLock(id: String): Mutex = sandboxLocks.getOrPut(id) { Mutex() }
}

private fun SandboxFileEntry.toWorkspaceEntry() = WorkspaceFileEntry(path, name, isDirectory, sizeBytes, updatedAt)
