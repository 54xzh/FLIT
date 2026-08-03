package me.rerere.rikkahub.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.dao.WorkspaceDao
import me.rerere.rikkahub.data.db.entity.SafWorkspaceEntity
import me.rerere.rikkahub.data.db.entity.SandboxRootfsStatus
import me.rerere.rikkahub.data.db.entity.SandboxWorkspaceEntity
import me.rerere.rikkahub.data.db.entity.SandboxWorkspaceMountEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceType
import me.rerere.rikkahub.data.sync.SANDBOX_RESTORE_SENTINEL
import me.rerere.rikkahub.data.db.entity.toolDefaultNeedsApproval
import me.rerere.rikkahub.data.db.entity.workspaceToolNames
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.workspace.SandboxCommandResult
import me.rerere.rikkahub.workspace.SandboxBindMount
import me.rerere.rikkahub.workspace.SandboxFileEntry
import me.rerere.rikkahub.workspace.SandboxRootfsInstallProgress
import me.rerere.rikkahub.workspace.SandboxRootfsInstaller
import me.rerere.rikkahub.workspace.SandboxStorageArea
import me.rerere.rikkahub.workspace.SandboxWorkspaceManager
import me.rerere.rikkahub.workspace.SandboxMountPathResolver
import me.rerere.rikkahub.workspace.SandboxMountSource
import me.rerere.rikkahub.workspace.SandboxProcessCoordinator
import me.rerere.rikkahub.workspace.WorkspaceTransferArchive
import me.rerere.rikkahub.workspace.WorkspaceTransferManifest
import me.rerere.rikkahub.workspace.WorkspaceTransferMount
import me.rerere.rikkahub.workspace.WorkspaceTransferProgress
import me.rerere.rikkahub.workspace.WorkspaceTransferStage
import me.rerere.rikkahub.workspace.estimateWorkspaceImportBytes
import me.rerere.rikkahub.service.ChatService
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

private const val TAG = "WorkspaceRepository"

/**
 * 根据已有名称集合，生成不重复的工作区名。
 * 未被占用则原样返回；否则依次尝试 `名字 2`、`名字 3`…（与导入逻辑一致）。
 */
fun uniqueWorkspaceName(
    sourceName: String,
    existingNames: Collection<String>,
    blankFallback: String = "Sandbox",
): String {
    val taken = existingNames.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val fallback = blankFallback.trim()
    val base = sourceName.trim().ifBlank { fallback }.take(200)
    if (base.isEmpty()) error("Workspace name is invalid")
    if (base !in taken) return base
    for (index in 2..10_000) {
        val suffix = " $index"
        val maxBaseLen = (200 - suffix.length).coerceAtLeast(1)
        val candidate = base.take(maxBaseLen).trimEnd() + suffix
        if (candidate !in taken) return candidate
    }
    error("Cannot create a unique workspace name")
}

internal fun normalizeSandboxMountParentPath(rawPath: String): String {
    val normalized = rawPath.replace('\\', '/').trim()
    require(normalized.startsWith('/')) { "Mount path must be absolute" }
    require(!normalized.contains('\u0000')) { "Mount path contains an invalid character" }
    val segments = normalized.split('/').filter { it.isNotBlank() }
    require(segments.isNotEmpty() && segments.first() == "workspace") { "Mount path must be inside /workspace" }
    require(segments.none { it == "." || it == ".." }) { "Mount path is invalid" }
    return "/" + segments.joinToString("/")
}

internal fun normalizeSandboxMountTarget(parentPath: String, rawName: String): String {
    val parent = normalizeSandboxMountParentPath(parentPath)
    val name = rawName.trim().take(200)
    require(name.isNotEmpty()) { "Folder name is required" }
    require(name != "." && name != ".." && !name.contains('/') && !name.contains('\\') && !name.contains('\u0000')) {
        "Folder name is invalid"
    }
    return "$parent/$name"
}

internal fun sandboxMountRelativePath(targetPath: String): String {
    if (targetPath.isBlank()) return ""
    return normalizeSandboxMountParentPath(targetPath).removePrefix("/workspace").trim('/')
}

internal fun uniqueSandboxMountName(sourceName: String, existingNames: Collection<String>): String {
    val taken = existingNames.toSet()
    val base = sourceName.trim().ifBlank { "Mounted folder" }.take(200)
    if (base !in taken) return base
    for (index in 2..10_000) {
        val suffix = " $index"
        val candidate = base.take((200 - suffix.length).coerceAtLeast(1)).trimEnd() + suffix
        if (candidate !in taken) return candidate
    }
    error("Cannot create a unique folder name")
}

data class SandboxMountDraft(
    val source: SandboxMountSource,
    val parentPath: String = "/workspace",
    val suggestedName: String,
)

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
    private val db: AppDatabase,
    private val dao: WorkspaceDao,
    private val settingsStore: SettingsStore,
    private val context: Context,
    private val sandboxManager: SandboxWorkspaceManager,
    private val rootfsInstaller: SandboxRootfsInstaller,
    private val workspaceTransferArchive: WorkspaceTransferArchive,
    private val conversationRepository: ConversationRepository,
    private val mountPathResolver: SandboxMountPathResolver,
    private val sandboxProcessCoordinator: SandboxProcessCoordinator,
    private val safRepository: SafRepository,
) : KoinComponent {
    private val sandboxLocks = ConcurrentHashMap<String, Mutex>()
    private val workspaceImportMutex = Mutex()
    private val workspaceStorageMutex = Mutex()

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
        db.withTransaction {
            // 新建时自动避重：已有同名则变成「名字 2」「名字 3」…
            val finalName = nextAvailableName(name, blankFallback = "Workspace")
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
    }

    suspend fun createSandbox(name: String): Workspace = workspaceStorageMutex.withLock {
        withContext(Dispatchers.IO) {
            db.withTransaction {
                // 新建时自动避重：默认 Sandbox 被占用则变成 Sandbox 2…
                val finalName = nextAvailableName(name, blankFallback = "Sandbox")
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
        }
    }

    suspend fun exportSandboxWorkspace(
        id: String,
        output: OutputStream,
        onProgress: (WorkspaceTransferProgress) -> Unit = {},
    ) = sandboxLock(id).withLock {
        sandboxProcessCoordinator.withWorkspaceMaintenance(id) {
        val workspace = requireSandbox(id)
        val detail = workspace.sandbox ?: error("Sandbox details are missing")
        val transferMounts = dao.getSandboxMounts(id).map { mount ->
            WorkspaceTransferMount(
                treeUri = mount.treeUri,
                sourcePath = mount.sourcePath,
                targetPath = mount.targetPath,
            )
        }
        runInterruptible(Dispatchers.IO) {
            val workspaceDir = sandboxManager.workspaceDirForAccess(id)
            val summary = workspaceTransferArchive.scan(workspaceDir, onProgress)
            val manifest = WorkspaceTransferManifest(
                sourceWorkspaceId = workspace.id,
                name = workspace.name,
                toolApprovals = workspace.toolApprovals,
                rootfsStatus = detail.rootfsStatus.name,
                rootfsSourceUrl = detail.rootfsSourceUrl,
                rootfsVersion = detail.rootfsVersion,
                rootfsInstalledAt = detail.rootfsInstalledAt,
                sourceAbi = Build.SUPPORTED_ABIS.firstOrNull(),
                createdAt = System.currentTimeMillis(),
                payloadBytes = summary.bytes,
                payloadEntries = summary.entries,
                mounts = transferMounts,
            )
            workspaceTransferArchive.export(workspaceDir, manifest, output, onProgress)
            onProgress(
                WorkspaceTransferProgress(
                    stage = WorkspaceTransferStage.FINALIZING,
                    processedBytes = summary.bytes,
                    totalBytes = summary.bytes,
                    processedEntries = summary.entries,
                    totalEntries = summary.entries,
                )
            )
        }
        }
    }

    suspend fun importSandboxWorkspace(
        input: InputStream,
        onProgress: (WorkspaceTransferProgress) -> Unit = {},
    ): Workspace = workspaceStorageMutex.withLock {
        workspaceImportMutex.withLock {
            sandboxProcessCoordinator.withGlobalMaintenance {
        val newId = Uuid.random().toString()
        val staging = withContext(Dispatchers.IO) { sandboxManager.prepareImportStaging(newId) }
        var movedToFinal = false
        var databaseCommitted = false
        try {
            val manifest = runInterruptible(Dispatchers.IO) {
                workspaceTransferArchive.import(
                    input = input,
                    stagingDir = staging,
                    onManifest = ::validateWorkspaceImportCapacity,
                    onProgress = onProgress,
                )
            }
            val importedName = nextAvailableName(manifest.name)
            val importedApprovals = runCatching {
                JsonInstant.decodeFromString<Map<String, Boolean>>(manifest.toolApprovals)
            }.getOrElse { error("Workspace tool settings are invalid") }
                .filterKeys { it in workspaceToolNames(WorkspaceType.SANDBOX) }
            val importedStatus = SandboxRootfsStatus.entries
                .firstOrNull { it.name == manifest.rootfsStatus }
                ?: error("Workspace Linux status is invalid")
            val status = if (importedStatus == SandboxRootfsStatus.READY && !File(staging, "linux/bin/sh").isFile) {
                SandboxRootfsStatus.BROKEN
            } else {
                importedStatus
            }
            val now = System.currentTimeMillis()
            val record = WorkspaceEntity(
                id = newId,
                name = importedName,
                type = WorkspaceType.SANDBOX,
                toolApprovals = JsonInstant.encodeToString(importedApprovals),
                createdAt = now,
                updatedAt = now,
            )
            onProgress(WorkspaceTransferProgress(stage = WorkspaceTransferStage.FINALIZING))
            db.withTransaction {
                withContext(Dispatchers.IO) {
                    sandboxManager.commitImportStaging(newId, staging)
                    movedToFinal = true
                }
                dao.upsert(record)
                dao.upsertSandboxDetail(
                    SandboxWorkspaceEntity(
                        workspaceId = newId,
                        rootfsStatus = status,
                        rootfsSourceUrl = manifest.rootfsSourceUrl,
                        rootfsVersion = manifest.rootfsVersion,
                        rootfsInstalledAt = manifest.rootfsInstalledAt,
                    )
                )
            }
            databaseCommitted = true
            val imported = resolve(record) ?: error("Failed to import workspace")
            manifest.mounts.forEach { mount ->
                runCatching {
                    restoreSandboxMountMarker(imported.id, mount)
                }.onFailure { error ->
                    Log.i(TAG, "importSandboxWorkspace: skipped unavailable mount ${mount.targetPath}: ${error.message}")
                }
            }
            imported
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                sandboxManager.discardImportStaging(newId)
                if (movedToFinal && !databaseCommitted) sandboxManager.deleteWorkspace(newId)
            }
        }
            }
        }
    }

    private fun validateWorkspaceImportCapacity(manifest: WorkspaceTransferManifest) {
        val sourceAbi = manifest.sourceAbi
        if (
            manifest.rootfsStatus != SandboxRootfsStatus.DISABLED.name &&
            !sourceAbi.isNullOrBlank() && sourceAbi !in Build.SUPPORTED_ABIS
        ) {
            error("Workspace Linux environment is not compatible with this device")
        }
        val storage = StatFs(context.filesDir.absolutePath)
        val required = estimateWorkspaceImportBytes(
            payloadBytes = manifest.payloadBytes,
            payloadEntries = manifest.payloadEntries,
            blockSizeBytes = storage.blockSizeLong,
        )
        val available = storage.availableBytes
        require(available >= required) { "Not enough storage space to import this workspace" }
    }

    /**
     * 为新建/导入工作区挑选不重复名称。
     * 规则与 [uniqueWorkspaceName] 一致：先用原名，被占用则追加 ` 2`、` 3`…
     */
    suspend fun nextAvailableName(sourceName: String, blankFallback: String = "Sandbox"): String {
        val existing = dao.getAll().map { it.name }
        return uniqueWorkspaceName(sourceName, existing, blankFallback)
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
        onProgress: (SandboxRootfsInstallProgress) -> Unit = {},
    ): Boolean = sandboxLock(id).withLock {
        sandboxProcessCoordinator.withWorkspaceMaintenance(id) {
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
    }

    suspend fun delete(id: String): Boolean = workspaceStorageMutex.withLock {
        val workspace = getById(id) ?: return@withLock false
        if (workspace.type == WorkspaceType.SANDBOX) {
            sandboxLock(id).withLock {
                sandboxProcessCoordinator.withWorkspaceMaintenance(id) {
                    deleteInternal(workspace)
                }
            }
        } else {
            deleteInternal(workspace)
        }
        true
    }

    private suspend fun deleteInternal(workspace: Workspace) {
        if (workspace.type == WorkspaceType.SANDBOX) {
            val mountUris = dao.getSandboxMounts(workspace.id).map { it.treeUri }.distinct()
            dao.deleteSandboxMountsByWorkspace(workspace.id)
            withContext(Dispatchers.IO) { sandboxManager.deleteWorkspace(workspace.id) }
            dao.deleteSandboxDetail(workspace.id)
            mountUris.forEach { releaseTreePermissionIfUnused(it) }
        } else {
            dao.deleteSafDetail(workspace.id)
        }
        dao.deleteById(workspace.id)
        cleanupAssistantReferences(workspace.id)
    }

    suspend fun checkIntegrity() = workspaceStorageMutex.withLock {
        withContext(Dispatchers.IO) {
            sandboxProcessCoordinator.withGlobalMaintenance {
        sandboxManager.cleanupImportStagingDirectories()
        val workspaceRecords = dao.getAll()
        sandboxManager.cleanupOrphanedWorkspaceDirectories(
            workspaceRecords.filter { it.type == WorkspaceType.SANDBOX }.mapTo(mutableSetOf()) { it.id }
        )
        // 检测沙盒恢复 sentinel：恢复成功后由 WebdavSync 写入，标志本次启动需要把所有沙盒工作区
        // 的旧 rootfs（linux/ 与 tmp/）清掉并降级状态。备份不含 rootfs，恢复到本机后旧 linux/
        // 残留会让 hasRootfs 误判已就绪；按 zip 条目清理会漏掉 files/ 为空的工作区，所以集中到
        // 这里遍历全部工作区统一处理。清理完成后删除 sentinel，下次启动走正常分支。
        val pendingSandboxClean = File(context.filesDir, SANDBOX_RESTORE_SENTINEL).exists()
        if (pendingSandboxClean) {
            Log.i(TAG, "checkIntegrity: 检测到沙盒恢复 sentinel，开始清理旧 rootfs 残留")
        }
        workspaceRecords.forEach { record ->
            val workspace = resolve(dao.getById(record.id) ?: return@forEach) ?: return@forEach
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
                WorkspaceType.SANDBOX -> sandboxLock(workspace.id).withLock {
                    val currentRecord = dao.getById(workspace.id) ?: return@withLock
                    val current = resolve(currentRecord) ?: return@withLock
                    val detail = current.sandbox ?: return@withLock
                    if (!sandboxManager.workspaceDirAvailable(current.id)) {
                        sandboxManager.deleteWorkspace(current.id)
                        dao.deleteSandboxDetail(current.id)
                        dao.deleteById(current.id)
                        cleanupAssistantReferences(current.id)
                    } else if (pendingSandboxClean) {
                        // 恢复后清理：删掉本机残留的旧 rootfs，状态降级为未安装，由用户重装。
                        // 无论 DB 状态是 READY/INSTALLING/DISABLED 都清，避免旧 linux/ 让 hasRootfs 误判。
                        val cleaned = sandboxManager.cleanRootfsResidue(current.id)
                        if (!cleaned) {
                            Log.w(TAG, "checkIntegrity: 工作区 ${current.id} 的旧 rootfs 未完全删除，下次启动会重试")
                        }
                        updateSandboxStatus(current.id, SandboxRootfsStatus.DISABLED, detail.rootfsSourceUrl, detail.rootfsVersion, null)
                    } else if (detail.rootfsStatus == SandboxRootfsStatus.INSTALLING) {
                        // 安装中进程被杀会残留 INSTALLING。rootfs 实际是否落盘决定回收还是降级，
                        // 同时清掉强杀时 finally 来不及清理的 tmp/ 残留（下载包与解压 staging）。
                        val hasRootfs = sandboxManager.hasRootfs(current.id)
                        val recovered: SandboxRootfsStatus
                        val cleaned: Boolean
                        if (hasRootfs) {
                            // rootfs 完整：上次安装已成功、只是收尾前被杀。只清 tmp/，保留 linux/。
                            recovered = SandboxRootfsStatus.READY
                            cleaned = sandboxManager.cleanTempResidue(current.id)
                        } else {
                            // rootfs 不完整：清 linux/（可能半写残骸或不存在）与 tmp/。
                            recovered = SandboxRootfsStatus.DISABLED
                            cleaned = sandboxManager.cleanRootfsResidue(current.id)
                        }
                        if (!cleaned) {
                            Log.w(TAG, "checkIntegrity: 工作区 ${current.id} 的 INSTALLING 残留未完全清理，下次启动会重试")
                        }
                        updateSandboxStatus(
                            current.id,
                            recovered,
                            detail.rootfsSourceUrl,
                            detail.rootfsVersion,
                            if (recovered == SandboxRootfsStatus.READY) detail.rootfsInstalledAt else null,
                        )
                    } else if (detail.rootfsStatus == SandboxRootfsStatus.READY
                        && !sandboxManager.hasRootfs(current.id)
                    ) {
                        updateSandboxStatus(current.id, SandboxRootfsStatus.DISABLED, detail.rootfsSourceUrl, detail.rootfsVersion, null)
                    } else if (current.toolApprovalOverrides().isEmpty()) {
                        migrateDefaultToolApprovals(current)
                    }
                }
            }
        }
        if (pendingSandboxClean) {
            runCatching { File(context.filesDir, SANDBOX_RESTORE_SENTINEL).delete() }
                .onFailure { Log.w(TAG, "checkIntegrity: 删除沙盒恢复 sentinel 失败: ${it.message}") }
            Log.i(TAG, "checkIntegrity: 沙盒恢复清理完成")
        }

        val currentRecords = dao.getAll().associateBy { it.id }
        dao.getAllSandboxMounts().forEach { mount ->
            sandboxLock(mount.workspaceId).withLock {
                val owner = currentRecords[mount.workspaceId]
                val structurallyValid = owner?.type == WorkspaceType.SANDBOX && runCatching {
                    check(normalizeSandboxMountTarget(
                        mount.targetPath.substringBeforeLast('/', "/workspace"),
                        mount.targetPath.substringAfterLast('/'),
                    ) == mount.targetPath)
                    sandboxMountRelativePath(mount.targetPath)
                }.isSuccess
                if (!structurallyValid) {
                    dao.deleteSandboxMount(mount.id)
                    releaseTreePermissionIfUnused(mount.treeUri)
                } else {
                    sandboxManager.ensureFilesDirectory(
                        mount.workspaceId,
                        sandboxMountRelativePath(mount.targetPath.substringBeforeLast('/', "/workspace")),
                    )
                    if (!mountPathResolver.hasPersistedReadWritePermission(mount.treeUri) ||
                        !mountPathResolver.sourceMarkerMatches(mount.treeUri, mount.sourcePath)
                    ) {
                        Log.w(TAG, "checkIntegrity: 保留暂时不可用的沙盒挂载 ${mount.workspaceId}:${mount.targetPath}")
                    }
                }
            }
        }
        cleanupOrphanedMountPermissions()
            }
        }
    }

    fun hasAllFilesAccess(): Boolean = mountPathResolver.hasAllFilesAccess()

    suspend fun prepareSandboxMount(id: String, treeUri: String): SandboxMountDraft = withContext(Dispatchers.IO) {
        requireSandbox(id)
        registerMountPermission(treeUri)
        context.contentResolver.takePersistableUriPermission(
            Uri.parse(treeUri),
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        require(mountPathResolver.hasPersistedReadWritePermission(treeUri)) { "Folder permission was not saved" }
        val source = mountPathResolver.resolve(Uri.parse(treeUri))
        val parentPath = "/workspace"
        val existingNames = sandboxMountChildNames(id, parentPath)
        SandboxMountDraft(
            source = source,
            parentPath = parentPath,
            suggestedName = uniqueSandboxMountName(source.displayName, existingNames),
        )
    }

    suspend fun createSandboxMount(
        id: String,
        treeUri: String,
        parentPath: String,
        name: String,
    ): SandboxWorkspaceMountEntity = sandboxLock(id).withLock {
        withContext(Dispatchers.IO) {
            requireSandbox(id)
            require(mountPathResolver.hasPersistedReadWritePermission(treeUri)) { "Folder permission is unavailable" }
            val source = mountPathResolver.resolve(Uri.parse(treeUri))
            val targetPath = normalizeSandboxMountTarget(parentPath, name)
            insertSandboxMountRecord(id, source.treeUri, source.sourcePath, targetPath)
        }
    }

    suspend fun removeSandboxMount(workspaceId: String, mountId: String): Boolean = sandboxLock(workspaceId).withLock {
        withContext(Dispatchers.IO) {
            requireSandbox(workspaceId)
            val mount = dao.getSandboxMount(mountId) ?: return@withContext false
            require(mount.workspaceId == workspaceId) { "Mounted folder belongs to another workspace" }
            val deleted = dao.deleteSandboxMount(mountId) > 0
            if (deleted) releaseTreePermissionIfUnused(mount.treeUri)
            deleted
        }
    }

    suspend fun releaseUnusedMountPermission(treeUri: String) = withContext(Dispatchers.IO) {
        releaseTreePermissionIfUnused(treeUri)
    }

    suspend fun listSandboxFiles(
        id: String,
        path: String,
        area: SandboxStorageArea = SandboxStorageArea.FILES,
    ): List<WorkspaceFileEntry> = withContext(Dispatchers.IO) {
        requireSandbox(id)
        if (area != SandboxStorageArea.FILES) {
            return@withContext sandboxManager.listFiles(id, path, area).map(SandboxFileEntry::toWorkspaceEntry)
        }
        val normalizedPath = normalizeWorkspaceRelativePath(path)
        val mounts = dao.getSandboxMounts(id)
        mounts.forEach { mount ->
            sandboxManager.ensureFilesDirectory(id, mountParentRelative(mount))
        }
        val resolved = resolveSandboxMount(mounts, normalizedPath)
        if (resolved != null) {
            return@withContext sandboxManager.listExternalFiles(mountSourceFile(resolved.mount), resolved.relativePath)
                .map { entry -> entry.toWorkspaceEntry(resolved.mount, joinRelative(resolved.targetRelative, entry.path)) }
        }
        val localEntries = sandboxManager.listFiles(id, normalizedPath, area).map(SandboxFileEntry::toWorkspaceEntry).toMutableList()
        mounts.filter { mountParentRelative(it) == normalizedPath }.forEach { mount ->
            val targetRelative = sandboxMountRelativePath(mount.targetPath)
            localEntries.removeAll { it.path == targetRelative }
            val source = File(mount.sourcePath)
            localEntries += WorkspaceFileEntry(
                path = targetRelative,
                name = mount.targetPath.substringAfterLast('/'),
                isDirectory = true,
                sizeBytes = 0,
                updatedAt = source.lastModified(),
                mountId = mount.id,
                isMountRoot = true,
            )
        }
        localEntries.sortedWith(compareBy<WorkspaceFileEntry> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    suspend fun readSandboxText(id: String, path: String): String = withContext(Dispatchers.IO) {
        requireSandbox(id)
        val normalized = normalizeWorkspaceRelativePath(path)
        val resolved = resolveSandboxMount(dao.getSandboxMounts(id), normalized)
        if (resolved == null) sandboxManager.readText(id, normalized)
        else sandboxManager.readExternalText(mountSourceFile(resolved.mount), resolved.relativePath)
    }

    suspend fun writeSandboxText(id: String, path: String, text: String, overwrite: Boolean): WorkspaceFileEntry =
        sandboxLock(id).withLock {
            withContext(Dispatchers.IO) {
                requireSandbox(id)
                val normalized = normalizeWorkspaceRelativePath(path)
                val resolved = resolveSandboxMount(dao.getSandboxMounts(id), normalized)
                if (resolved == null) {
                    sandboxManager.writeText(id, normalized, text, overwrite).toWorkspaceEntry()
                } else {
                    sandboxManager.writeExternalText(mountSourceFile(resolved.mount), resolved.relativePath, text, overwrite)
                        .toWorkspaceEntry(resolved.mount, normalized)
                }
            }
        }

    suspend fun importSandboxFile(
        id: String,
        path: String,
        fileName: String,
        input: InputStream,
        area: SandboxStorageArea = SandboxStorageArea.FILES,
    ): WorkspaceFileEntry =
        sandboxLock(id).withLock {
            withContext(Dispatchers.IO) {
                requireSandbox(id)
                val normalized = normalizeWorkspaceRelativePath(path)
                val resolved = if (area == SandboxStorageArea.FILES) resolveSandboxMount(dao.getSandboxMounts(id), normalized) else null
                if (resolved == null) {
                    sandboxManager.importFile(id, normalized, fileName, input, area).toWorkspaceEntry()
                } else {
                    val entry = sandboxManager.importExternalFile(mountSourceFile(resolved.mount), resolved.relativePath, fileName, input)
                    entry.toWorkspaceEntry(resolved.mount, joinRelative(resolved.targetRelative, entry.path))
                }
            }
        }

    suspend fun exportSandboxFile(
        id: String,
        path: String,
        output: OutputStream,
        area: SandboxStorageArea = SandboxStorageArea.FILES,
    ) =
        sandboxLock(id).withLock {
            withContext(Dispatchers.IO) {
                requireSandbox(id)
                val normalized = normalizeWorkspaceRelativePath(path)
                val resolved = if (area == SandboxStorageArea.FILES) resolveSandboxMount(dao.getSandboxMounts(id), normalized) else null
                if (resolved == null) sandboxManager.exportFile(id, normalized, output, area)
                else sandboxManager.exportExternalFile(mountSourceFile(resolved.mount), resolved.relativePath, output)
            }
        }

    suspend fun deleteSandboxFile(
        id: String,
        path: String,
        recursive: Boolean,
        area: SandboxStorageArea = SandboxStorageArea.FILES,
    ): Boolean =
        sandboxLock(id).withLock {
            val deleteAction: suspend () -> Boolean = {
                withContext(Dispatchers.IO) {
                val workspace = requireSandbox(id)
                val normalized = normalizeWorkspaceRelativePath(path)
                val mounts = if (area == SandboxStorageArea.FILES) dao.getSandboxMounts(id) else emptyList()
                require(mounts.none { mount -> isMountDescendantOf(mount, normalized) }) {
                    "Unmount mounted folders inside this directory before deleting it"
                }
                val resolved = resolveSandboxMount(mounts, normalized)
                require(resolved?.relativePath?.isNotBlank() != false) { "Unmount the folder instead of deleting it" }
                val deleted = if (resolved == null) {
                    sandboxManager.deleteFile(id, normalized, recursive, area)
                } else {
                    sandboxManager.deleteExternalFile(mountSourceFile(resolved.mount), resolved.relativePath, recursive)
                }
                if (deleted && area == SandboxStorageArea.ROOTFS && !sandboxManager.hasRootfs(id)) {
                    val detail = workspace.sandbox
                    updateSandboxStatus(
                        id = id,
                        status = SandboxRootfsStatus.BROKEN,
                        sourceUrl = detail?.rootfsSourceUrl,
                        version = detail?.rootfsVersion,
                        installedAt = detail?.rootfsInstalledAt,
                    )
                }
                deleted
                }
            }
            if (area == SandboxStorageArea.ROOTFS) {
                sandboxProcessCoordinator.withWorkspaceMaintenance(id, deleteAction)
            } else {
                deleteAction()
            }
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
        val bindMounts = loadSandboxBindMounts(id)
        runInterruptible(Dispatchers.IO) {
            sandboxManager.executeCommand(id, command, cwd, timeoutMillis, stdin, bindMounts)
        }
    }

    suspend fun getSandboxBindMounts(id: String): List<SandboxBindMount> = withContext(Dispatchers.IO) {
        requireSandbox(id)
        loadSandboxBindMounts(id)
    }

    /**
     * 统计所有沙盒工作区在存储管理里展示的占用：每个工作区按 `files/`、`linux/`、`tmp/`
     * 三块分别算大小，附带工作区名与 rootfs 状态。
     *
     * `linux/` 通常几十~百 MB，是存储管理与系统显示差额的主要来源；放在这里是因为本仓库
     * 持有 [sandboxManager] 与工作区记录。各工作区并发扫描，避免大 rootfs 拖慢整体。
     */
    suspend fun getSandboxWorkspacesUsage(): List<SandboxWorkspaceUsage> = withContext(Dispatchers.IO) {
        val sandboxes = getAll().filter { it.type == WorkspaceType.SANDBOX && it.sandbox != null }
        if (sandboxes.isEmpty()) return@withContext emptyList()
        coroutineScope {
            sandboxes.map { workspace ->
                async(Dispatchers.IO) {
                    SandboxWorkspaceUsage(
                        workspaceId = workspace.id,
                        name = workspace.name,
                        rootfsStatus = workspace.sandboxStatus ?: SandboxRootfsStatus.DISABLED,
                        filesUsage = countDir(sandboxManager.filesDir(workspace.id)),
                        linuxUsage = countDir(sandboxManager.linuxDir(workspace.id)),
                        tmpUsage = countDir(sandboxManager.tempDir(workspace.id)),
                    )
                }
            }.awaitAll().sortedByDescending { it.totalBytes }
        }
    }

    /**
     * 清理单个沙盒工作区的 rootfs（`linux/` 与 `tmp/`），保留 `files/` 用户文件，并把
     * DB 状态降级为 DISABLED。供存储管理页"清理 rootfs"按钮调用。
     *
     * 与 [installSandboxRootfs] / [executeSandboxCommand] 共享 [sandboxLock]，互斥避免与正在
     * 运行的沙盒命令或安装冲突。返回 [cleanRootfsResidue] 的结果：删不干净返回 false（调用方
     * 应提示用户关闭使用该工作区的对话后重试），rootfs 本就不存在返回 true。
     */
    suspend fun cleanSandboxRootfs(id: String): Boolean = withContext(Dispatchers.IO) {
        sandboxLock(id).withLock {
            sandboxProcessCoordinator.withWorkspaceMaintenance(id) {
            requireSandbox(id)
            val detail = dao.getSandboxDetail(id)
            val cleaned = sandboxManager.cleanRootfsResidue(id)
            updateSandboxStatus(id, SandboxRootfsStatus.DISABLED, detail?.rootfsSourceUrl, detail?.rootfsVersion, null)
            cleaned
            }
        }
    }

    /**
     * 统计沙盒工作区某个子目录（files/linux/tmp）的占用，口径对齐 Android 系统设置"按真实磁盘块统计"：
     * - **不跟随符号链接**：rootfs 里常见 `/bin → /usr/bin`、`/lib → /usr/lib` 这类目录符号链接，
     *   跟随会把同一棵子树遍历两遍，几百 MB 的 rootfs 被累加成好几 GB（应用内显示与 Android 系统
     *   设置存储统计出入巨大的主因）。符号链接自身按一个文件计入，不再展开其目标。
     * - **按 (设备号, inode) 给硬链接去重**：rootfs 大量用硬链接省空间（同一磁盘块多个名字），
     *   每个名字的 size 都是完整大小；按 (dev, ino) 去重后每块只算一次，与 `du`/系统统计一致。
     *   key 含 st_dev 是为不同文件系统上 inode 号重复时也能正确区分（虽然单次扫描通常同 fs）。
     * - **按块占用计大小**：用 `st_blocks * 512`（实际占用的磁盘块）而非 `length()`（逻辑大小），
     *   稀疏文件不会被按逻辑大小虚高统计，与系统设置数字更接近。
     *
     * 用 `Os.lstat`（不跟随链接）取类型与 inode。任何一步失败都回退到 `File` 自身的目录/文件判断，
     * 保证子树不被漏算；lstat 失败的文件按普通文件计入（size 用 `length()` 回退），不去重。
     */
    private fun countDir(root: File): SandboxWorkspaceDirUsage {
        if (!root.exists()) return SandboxWorkspaceDirUsage(bytes = 0L, fileCount = 0)
        var count = 0
        var bytes = 0L
        val seenDevIno = HashSet<Pair<Long, Long>>()
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            current.listFiles().orEmpty().forEach { entry ->
                val stat: StructStat? = runCatching { Os.lstat(entry.absolutePath) }.getOrNull()
                val mode = stat?.st_mode ?: 0
                when {
                    OsConstants.S_ISLNK(mode) -> {
                        // 符号链接：算一个文件，不跟随，避免重复遍历被链接的子树。
                        count += 1
                    }
                    OsConstants.S_ISDIR(mode) -> {
                        stack.addLast(entry)
                    }
                    OsConstants.S_ISREG(mode) -> {
                        // 普通文件（含硬链接）：按 (dev, ino) 去重，同一磁盘块只计一次；按块占用计大小。
                        val dev = stat?.st_dev ?: 0L
                        val ino = stat?.st_ino ?: 0L
                        if (ino > 0 && !seenDevIno.add(dev to ino)) return@forEach
                        count += 1
                        // st_blocks * 512 是真实磁盘块占用；lstat 失败（stat==null）或 st_blocks 不可得时回退 length()。
                        val blockBytes = stat?.st_blocks?.let { it * 512L }
                        bytes += blockBytes ?: runCatching { entry.length() }.getOrNull() ?: 0L
                    }
                    else -> {
                        // mode == 0（lstat 失败）或其他类型：用 File 回退判断，避免目录子树被漏算。
                        if (entry.isDirectory) {
                            stack.addLast(entry)
                        } else if (entry.isFile) {
                            count += 1
                            bytes += runCatching { entry.length() }.getOrNull() ?: 0L
                        }
                    }
                }
            }
        }
        return SandboxWorkspaceDirUsage(bytes = bytes, fileCount = count)
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

    /** Resolve a workspace path without reading its contents. */
    suspend fun resolveWorkspaceEntry(id: String, path: String): WorkspaceFileEntry? = withContext(Dispatchers.IO) {
        val workspace = getById(id) ?: return@withContext null
        val normalized = runCatching { normalizeWorkspaceRelativePath(path) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        when (workspace.type) {
            WorkspaceType.LIGHTWEIGHT -> {
                val root = workspace.treeUri?.let(::resolveRoot) ?: return@withContext null
                val target = safRepository.resolve(root, normalized)
                    ?: return@withContext null
                WorkspaceFileEntry(
                    path = normalized,
                    name = target.name ?: normalized.substringAfterLast('/'),
                    isDirectory = target.isDirectory,
                    sizeBytes = target.length().coerceAtLeast(0L),
                    updatedAt = target.lastModified(),
                )
            }

            WorkspaceType.SANDBOX -> try {
                resolveSandboxWorkspaceEntry(
                    manager = sandboxManager,
                    workspaceId = id,
                    mounts = dao.getSandboxMounts(id),
                    normalizedPath = normalized,
                    sourceForMount = { mount -> mountSourceFile(mount) },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Resolve a regular file from either workspace implementation without reading its contents. */
    suspend fun resolveWorkspaceFile(id: String, path: String): WorkspaceFileEntry? =
        resolveWorkspaceEntry(id, path)?.takeIf { !it.isDirectory }

    /** Return a direct URI when the file is SAF-backed; sandbox files need a temporary export. */
    suspend fun resolveWorkspaceFileUri(id: String, path: String): Uri? = withContext(Dispatchers.IO) {
        val workspace = getById(id) ?: return@withContext null
        if (workspace.type != WorkspaceType.LIGHTWEIGHT) return@withContext null
        val normalized = runCatching { normalizeWorkspaceRelativePath(path) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        val root = workspace.treeUri?.let(::resolveRoot) ?: return@withContext null
        safRepository.resolve(root, normalized)?.takeIf { it.isFile }?.uri
    }

    /** Stream the current file into [output], rechecking the workspace path at click time. */
    suspend fun exportWorkspaceFile(id: String, path: String, output: OutputStream) = withContext(Dispatchers.IO) {
        val workspace = getById(id) ?: error("Workspace is unavailable")
        val normalized = runCatching { normalizeWorkspaceRelativePath(path) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: error("Invalid workspace file path")
        when (workspace.type) {
            WorkspaceType.LIGHTWEIGHT -> {
                val root = workspace.treeUri?.let(::resolveRoot) ?: error("Workspace folder is unavailable")
                val target = safRepository.resolve(root, normalized)?.takeIf { it.isFile }
                    ?: error("File is unavailable")
                context.contentResolver.openInputStream(target.uri)?.use { input ->
                    input.copyTo(output)
                } ?: error("Unable to read file")
            }

            WorkspaceType.SANDBOX -> exportSandboxFile(id, normalized, output)
        }
    }

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

    private suspend fun sandboxMountChildNames(id: String, parentPath: String): Set<String> {
        val normalizedParent = normalizeSandboxMountParentPath(parentPath)
        val parentRelative = sandboxMountRelativePath(normalizedParent)
        val localNames = runCatching {
            sandboxManager.listFiles(id, parentRelative, SandboxStorageArea.FILES).map { it.name }
        }.getOrDefault(emptyList())
        val mountNames = dao.getSandboxMounts(id)
            .filter { mountParentRelative(it) == parentRelative }
            .map { it.targetPath.substringAfterLast('/') }
        return (localNames + mountNames).toSet()
    }

    private suspend fun restoreSandboxMountMarker(id: String, marker: WorkspaceTransferMount) =
        sandboxLock(id).withLock {
            withContext(Dispatchers.IO) {
                requireSandbox(id)
                require(mountPathResolver.hasPersistedReadWritePermission(marker.treeUri)) {
                    "Folder permission is unavailable"
                }
                require(mountPathResolver.sourceMarkerMatches(marker.treeUri, marker.sourcePath)) {
                    "Mounted folder marker is invalid"
                }
                val targetPath = normalizeSandboxMountTarget(
                    marker.targetPath.substringBeforeLast('/', "/workspace"),
                    marker.targetPath.substringAfterLast('/'),
                )
                insertSandboxMountRecord(id, marker.treeUri, marker.sourcePath, targetPath)
            }
        }

    private suspend fun insertSandboxMountRecord(
        id: String,
        treeUri: String,
        sourcePath: String,
        targetPath: String,
    ): SandboxWorkspaceMountEntity {
        val canonicalSource = File(sourcePath).canonicalFile
        val targetRelative = sandboxMountRelativePath(targetPath)
        val parentPath = targetPath.substringBeforeLast('/', "/workspace")
        val mounts = dao.getSandboxMounts(id)
        require(mounts.none { File(it.sourcePath).canonicalFile == canonicalSource }) {
            "This folder is already mounted"
        }
        require(mounts.none { pathsOverlap(it.targetPath, targetPath) }) {
            "The mount path overlaps another mounted folder"
        }
        sandboxManager.ensureFilesDirectory(id, sandboxMountRelativePath(parentPath))
        require(!sandboxManager.filesPathExists(id, targetRelative)) { "A file or folder already exists at the mount path" }
        val mount = SandboxWorkspaceMountEntity(
            id = Uuid.random().toString(),
            workspaceId = id,
            treeUri = treeUri,
            sourcePath = canonicalSource.absolutePath,
            targetPath = targetPath,
            createdAt = System.currentTimeMillis(),
        )
        dao.insertSandboxMount(mount)
        registerMountPermission(treeUri)
        return mount
    }

    private suspend fun releaseTreePermissionIfUnused(treeUri: String) {
        if (dao.countSandboxMountsByTreeUri(treeUri) > 0) return
        unregisterMountPermission(treeUri)
        if (dao.getSafDetailByTreeUri(treeUri) != null) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Log.w(TAG, "releaseTreePermissionIfUnused: ${it.message}") }
    }

    private suspend fun cleanupOrphanedMountPermissions() {
        registeredMountPermissions().forEach { treeUri ->
            releaseTreePermissionIfUnused(treeUri)
        }
    }

    private fun registerMountPermission(treeUri: String) {
        synchronized(MOUNT_PERMISSION_LOCK) {
            val prefs = context.getSharedPreferences(MOUNT_PERMISSION_PREFS, Context.MODE_PRIVATE)
            val updated = prefs.getStringSet(MOUNT_PERMISSION_URIS, emptySet()).orEmpty().toMutableSet()
            if (updated.add(treeUri)) prefs.edit().putStringSet(MOUNT_PERMISSION_URIS, updated).commit()
        }
    }

    private fun unregisterMountPermission(treeUri: String) {
        synchronized(MOUNT_PERMISSION_LOCK) {
            val prefs = context.getSharedPreferences(MOUNT_PERMISSION_PREFS, Context.MODE_PRIVATE)
            val updated = prefs.getStringSet(MOUNT_PERMISSION_URIS, emptySet()).orEmpty().toMutableSet()
            if (updated.remove(treeUri)) prefs.edit().putStringSet(MOUNT_PERMISSION_URIS, updated).commit()
        }
    }

    private fun registeredMountPermissions(): Set<String> = synchronized(MOUNT_PERMISSION_LOCK) {
        context.getSharedPreferences(MOUNT_PERMISSION_PREFS, Context.MODE_PRIVATE)
            .getStringSet(MOUNT_PERMISSION_URIS, emptySet())
            .orEmpty()
            .toSet()
    }

    private fun mountSourceFile(mount: SandboxWorkspaceMountEntity): File {
        check(hasAllFilesAccess()) { "All files access is required for mounted folders" }
        check(mountPathResolver.hasPersistedReadWritePermission(mount.treeUri)) { "Mounted folder permission is unavailable" }
        val resolved = mountPathResolver.resolve(Uri.parse(mount.treeUri))
        val stored = File(mount.sourcePath).canonicalFile
        val selected = File(resolved.sourcePath).canonicalFile
        check(stored == selected) { "Mounted folder path no longer matches its permission" }
        check(stored.isDirectory) { "Mounted folder is unavailable: ${mount.targetPath}" }
        return stored
    }

    private suspend fun loadSandboxBindMounts(id: String): List<SandboxBindMount> {
        val mounts = dao.getSandboxMounts(id)
        if (mounts.isNotEmpty()) check(hasAllFilesAccess()) { "All files access is required for mounted folders" }
        return mounts.map { mount ->
            sandboxManager.ensureFilesDirectory(id, mountParentRelative(mount))
            SandboxBindMount(mountSourceFile(mount), mount.targetPath)
        }
    }

    private suspend fun updateSandboxStatus(id: String, status: SandboxRootfsStatus, sourceUrl: String?, version: String?, installedAt: Long?) {
        dao.updateSandboxRootfs(id, status, sourceUrl, version, installedAt)
    }

    private fun rootfsVersion(url: String): String = url.substringBefore('?').substringAfterLast('/').take(128)

    private suspend fun validateName(name: String, fallback: String = "Workspace", excludeId: String? = null): String {
        // 重命名仍严格禁止重名；新建走 nextAvailableName 自动加序号
        val finalName = name.trim().ifBlank { fallback }.take(200)
        require(finalName.isNotEmpty()) { "Workspace name is invalid" }
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
        // 同步清空会话侧的工作区覆写，避免指向已删除工作区的悬空引用。
        // 不静默吞异常：DB 写失败要留痕，否则旧覆写会持续被读到。
        try {
            conversationRepository.clearWorkspaceOverrideByWorkspaceId(workspaceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "cleanupAssistantReferences: clearWorkspaceOverrideByWorkspaceId failed for $workspaceId (${e.message})", e)
        }
        // 同步清内存：已加载到 ChatService 的会话里残留的覆写也一并置空，
        // UI 和下次工具装配都立刻看到「跟随助手」。
        runCatching { get<ChatService>().clearWorkspaceOverrideFromMemory(workspaceId) }
            .onFailure { Log.w(TAG, "cleanupAssistantReferences: clearWorkspaceOverrideFromMemory failed (${it.message})", it) }
    }

    private fun sandboxLock(id: String): Mutex = sandboxLocks.getOrPut(id) { Mutex() }

    private companion object {
        const val MOUNT_PERMISSION_PREFS = "sandbox_mount_permissions"
        const val MOUNT_PERMISSION_URIS = "tree_uris"
        val MOUNT_PERMISSION_LOCK = Any()
    }
}

private fun SandboxFileEntry.toWorkspaceEntry() = WorkspaceFileEntry(path, name, isDirectory, sizeBytes, updatedAt)

private fun SandboxFileEntry.toWorkspaceEntry(
    mount: SandboxWorkspaceMountEntity,
    workspacePath: String,
) = WorkspaceFileEntry(
    path = workspacePath,
    name = name,
    isDirectory = isDirectory,
    sizeBytes = sizeBytes,
    updatedAt = updatedAt,
    mountId = mount.id,
)

internal data class ResolvedSandboxMount(
    val mount: SandboxWorkspaceMountEntity,
    val targetRelative: String,
    val relativePath: String,
)

internal fun resolveSandboxWorkspaceEntry(
    manager: SandboxWorkspaceManager,
    workspaceId: String,
    mounts: List<SandboxWorkspaceMountEntity>,
    normalizedPath: String,
    sourceForMount: (SandboxWorkspaceMountEntity) -> File,
): WorkspaceFileEntry? {
    val resolved = resolveSandboxMount(mounts, normalizedPath)
    return if (resolved == null) {
        manager.resolveEntry(workspaceId, normalizedPath)?.toWorkspaceEntry()
    } else {
        manager.resolveExternalEntry(sourceForMount(resolved.mount), resolved.relativePath)
            ?.toWorkspaceEntry(resolved.mount, normalizedPath)
    }
}

internal fun resolveSandboxMount(
    mounts: List<SandboxWorkspaceMountEntity>,
    workspaceRelativePath: String,
): ResolvedSandboxMount? = mounts.mapNotNull { mount ->
    val target = sandboxMountRelativePath(mount.targetPath)
    when {
        workspaceRelativePath == target -> ResolvedSandboxMount(mount, target, "")
        workspaceRelativePath.startsWith("$target/") ->
            ResolvedSandboxMount(mount, target, workspaceRelativePath.removePrefix("$target/"))
        else -> null
    }
}.maxByOrNull { it.targetRelative.length }

private fun mountParentRelative(mount: SandboxWorkspaceMountEntity): String =
    sandboxMountRelativePath(mount.targetPath.substringBeforeLast('/', "/workspace"))

private fun isMountDescendantOf(mount: SandboxWorkspaceMountEntity, workspaceRelativePath: String): Boolean {
    return sandboxMountTargetIsDescendantOf(mount.targetPath, workspaceRelativePath)
}

internal fun sandboxMountTargetIsDescendantOf(targetPath: String, workspaceRelativePath: String): Boolean {
    if (workspaceRelativePath.isBlank()) return true
    val target = sandboxMountRelativePath(targetPath)
    return target.startsWith("${workspaceRelativePath.trimEnd('/')}/")
}

private fun pathsOverlap(first: String, second: String): Boolean =
    first == second || first.startsWith("$second/") || second.startsWith("$first/")

internal fun normalizeWorkspaceFileReferencePath(rawPath: String): String? {
    val normalized = runCatching { normalizeWorkspaceRelativePath(rawPath) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    if (normalized.split('/').any { it.isBlank() }) return null
    return normalized
}

private fun normalizeWorkspaceRelativePath(rawPath: String): String {
    val normalized = rawPath.replace('\\', '/').trim().trim('/')
    require(!normalized.contains('\u0000')) { "Path contains an invalid character" }
    require(normalized.split('/').none { it == "." || it == ".." }) { "Path is invalid" }
    return normalized
}

private fun joinRelative(parent: String, child: String): String = when {
    parent.isBlank() -> child.trim('/')
    child.isBlank() -> parent.trim('/')
    else -> "${parent.trimEnd('/')}/${child.trimStart('/')}"
}
