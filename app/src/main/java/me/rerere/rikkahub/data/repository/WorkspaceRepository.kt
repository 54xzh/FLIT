package me.rerere.rikkahub.data.repository

import android.content.Context
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
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceType
import me.rerere.rikkahub.data.sync.SANDBOX_RESTORE_SENTINEL
import me.rerere.rikkahub.data.db.entity.toolDefaultNeedsApproval
import me.rerere.rikkahub.data.db.entity.workspaceToolNames
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.workspace.SandboxCommandResult
import me.rerere.rikkahub.workspace.SandboxFileEntry
import me.rerere.rikkahub.workspace.SandboxRootfsInstallProgress
import me.rerere.rikkahub.workspace.SandboxRootfsInstaller
import me.rerere.rikkahub.workspace.SandboxWorkspaceManager
import me.rerere.rikkahub.workspace.WorkspaceTransferArchive
import me.rerere.rikkahub.workspace.WorkspaceTransferManifest
import me.rerere.rikkahub.workspace.WorkspaceTransferProgress
import me.rerere.rikkahub.workspace.WorkspaceTransferStage
import me.rerere.rikkahub.workspace.estimateWorkspaceImportBytes
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "WorkspaceRepository"

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
) {
    private val sandboxLocks = ConcurrentHashMap<String, Mutex>()
    private val workspaceImportMutex = Mutex()

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
    }

    suspend fun createSandbox(name: String): Workspace = withContext(Dispatchers.IO) {
        db.withTransaction {
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
    }

    suspend fun exportSandboxWorkspace(
        id: String,
        output: OutputStream,
        onProgress: (WorkspaceTransferProgress) -> Unit = {},
    ) = sandboxLock(id).withLock {
        val workspace = requireSandbox(id)
        val detail = workspace.sandbox ?: error("Sandbox details are missing")
        runInterruptible(Dispatchers.IO) {
            val workspaceDir = sandboxManager.workspaceDir(id)
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

    suspend fun importSandboxWorkspace(
        input: InputStream,
        onProgress: (WorkspaceTransferProgress) -> Unit = {},
    ): Workspace = workspaceImportMutex.withLock {
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
            resolve(record) ?: error("Failed to import workspace")
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                sandboxManager.discardImportStaging(newId)
                if (movedToFinal && !databaseCommitted) sandboxManager.deleteWorkspace(newId)
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

    private suspend fun nextAvailableName(sourceName: String): String {
        val base = sourceName.trim().ifBlank { "Sandbox" }.take(200)
        if (!isNameTaken(base, null)) return base
        for (index in 2..10_000) {
            val suffix = " $index"
            val candidate = base.take(200 - suffix.length).trimEnd() + suffix
            if (!isNameTaken(candidate, null)) return candidate
        }
        error("Cannot create a unique workspace name")
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
                    } else if (pendingSandboxClean) {
                        // 恢复后清理：删掉本机残留的旧 rootfs，状态降级为未安装，由用户重装。
                        // 无论 DB 状态是 READY/INSTALLING/DISABLED 都清，避免旧 linux/ 让 hasRootfs 误判。
                        val cleaned = sandboxManager.cleanRootfsResidue(workspace.id)
                        if (!cleaned) {
                            Log.w(TAG, "checkIntegrity: 工作区 ${workspace.id} 的旧 rootfs 未完全删除，下次启动会重试")
                        }
                        updateSandboxStatus(workspace.id, SandboxRootfsStatus.DISABLED, detail.rootfsSourceUrl, detail.rootfsVersion, null)
                    } else if (detail.rootfsStatus == SandboxRootfsStatus.INSTALLING) {
                        // 安装中进程被杀会残留 INSTALLING。rootfs 实际是否落盘决定回收还是降级，
                        // 同时清掉强杀时 finally 来不及清理的 tmp/ 残留（下载包与解压 staging）。
                        val hasRootfs = sandboxManager.hasRootfs(workspace.id)
                        val recovered: SandboxRootfsStatus
                        val cleaned: Boolean
                        if (hasRootfs) {
                            // rootfs 完整：上次安装已成功、只是收尾前被杀。只清 tmp/，保留 linux/。
                            recovered = SandboxRootfsStatus.READY
                            cleaned = sandboxManager.cleanTempResidue(workspace.id)
                        } else {
                            // rootfs 不完整：清 linux/（可能半写残骸或不存在）与 tmp/。
                            recovered = SandboxRootfsStatus.DISABLED
                            cleaned = sandboxManager.cleanRootfsResidue(workspace.id)
                        }
                        if (!cleaned) {
                            Log.w(TAG, "checkIntegrity: 工作区 ${workspace.id} 的 INSTALLING 残留未完全清理，下次启动会重试")
                        }
                        updateSandboxStatus(
                            workspace.id,
                            recovered,
                            detail.rootfsSourceUrl,
                            detail.rootfsVersion,
                            if (recovered == SandboxRootfsStatus.READY) detail.rootfsInstalledAt else null,
                        )
                    } else if (detail.rootfsStatus == SandboxRootfsStatus.READY
                        && !sandboxManager.hasRootfs(workspace.id)
                    ) {
                        updateSandboxStatus(workspace.id, SandboxRootfsStatus.DISABLED, detail.rootfsSourceUrl, detail.rootfsVersion, null)
                    } else if (workspace.toolApprovalOverrides().isEmpty()) {
                        migrateDefaultToolApprovals(workspace)
                    }
                }
            }
        }
        if (pendingSandboxClean) {
            runCatching { File(context.filesDir, SANDBOX_RESTORE_SENTINEL).delete() }
                .onFailure { Log.w(TAG, "checkIntegrity: 删除沙盒恢复 sentinel 失败: ${it.message}") }
            Log.i(TAG, "checkIntegrity: 沙盒恢复清理完成")
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

    suspend fun writeSandboxText(id: String, path: String, text: String, overwrite: Boolean): WorkspaceFileEntry =
        sandboxLock(id).withLock {
            withContext(Dispatchers.IO) {
                requireSandbox(id)
                sandboxManager.writeText(id, path, text, overwrite).toWorkspaceEntry()
            }
        }

    suspend fun importSandboxFile(id: String, path: String, fileName: String, input: InputStream): WorkspaceFileEntry =
        sandboxLock(id).withLock {
            withContext(Dispatchers.IO) {
                requireSandbox(id)
                sandboxManager.importFile(id, path, fileName, input).toWorkspaceEntry()
            }
        }

    suspend fun exportSandboxFile(id: String, path: String, output: OutputStream) = withContext(Dispatchers.IO) {
        requireSandbox(id)
        sandboxManager.exportFile(id, path, output)
    }

    suspend fun deleteSandboxFile(id: String, path: String, recursive: Boolean): Boolean =
        sandboxLock(id).withLock {
            withContext(Dispatchers.IO) {
                requireSandbox(id)
                sandboxManager.deleteFile(id, path, recursive)
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
        runInterruptible(Dispatchers.IO) { sandboxManager.executeCommand(id, command, cwd, timeoutMillis, stdin) }
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
            requireSandbox(id)
            val detail = dao.getSandboxDetail(id)
            val cleaned = sandboxManager.cleanRootfsResidue(id)
            updateSandboxStatus(id, SandboxRootfsStatus.DISABLED, detail?.rootfsSourceUrl, detail?.rootfsVersion, null)
            cleaned
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
