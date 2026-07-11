package me.rerere.rikkahub.ui.pages.extensions.workspace

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.db.entity.WorkspaceType
import me.rerere.rikkahub.data.repository.Workspace
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.workspace.WorkspaceTransferProgress

enum class WorkspaceTransferOperation { IMPORT, EXPORT }

data class WorkspaceTransferUiState(
    val operation: WorkspaceTransferOperation? = null,
    val progress: WorkspaceTransferProgress? = null,
) {
    val active: Boolean get() = operation != null
}

/**
 * 工作区导入/导出的共用状态与流程控制。
 *
 * 列表页与详情页各自持有一个实例，避免两处各写一份进度管理/取消/异常处理逻辑。
 * 行为与原详情页实现一致：guard 命中时仅关闭流并返回，不回调 onResult。
 */
class WorkspaceTransferController(
    private val repository: WorkspaceRepository,
    private val scope: CoroutineScope,
) {
    private val _transferState = MutableStateFlow(WorkspaceTransferUiState())
    val transferState = _transferState.asStateFlow()
    private var transferJob: Job? = null

    fun importWorkspace(input: InputStream, onResult: (Result<Workspace>) -> Unit) {
        if (_transferState.value.active) {
            input.close()
            return
        }
        transferJob = scope.launch {
            _transferState.value = WorkspaceTransferUiState(operation = WorkspaceTransferOperation.IMPORT)
            try {
                val workspace = input.use { stream ->
                    repository.importSandboxWorkspace(stream) { progress ->
                        _transferState.value = WorkspaceTransferUiState(
                            operation = WorkspaceTransferOperation.IMPORT,
                            progress = progress,
                        )
                    }
                }
                onResult(Result.success(workspace))
            } catch (error: CancellationException) {
                onResult(Result.failure(error))
                throw error
            } catch (error: Throwable) {
                onResult(Result.failure(error))
            } finally {
                _transferState.value = WorkspaceTransferUiState()
                transferJob = null
            }
        }
    }

    fun exportWorkspace(workspace: Workspace, output: OutputStream, onResult: (Result<Unit>) -> Unit) {
        if (workspace.type != WorkspaceType.SANDBOX || _transferState.value.active) {
            output.close()
            return
        }
        transferJob = scope.launch {
            _transferState.value = WorkspaceTransferUiState(operation = WorkspaceTransferOperation.EXPORT)
            try {
                output.use { stream ->
                    repository.exportSandboxWorkspace(workspace.id, stream) { progress ->
                        _transferState.value = WorkspaceTransferUiState(
                            operation = WorkspaceTransferOperation.EXPORT,
                            progress = progress,
                        )
                    }
                }
                onResult(Result.success(Unit))
            } catch (error: CancellationException) {
                onResult(Result.failure(error))
                throw error
            } catch (error: Throwable) {
                onResult(Result.failure(error))
            } finally {
                _transferState.value = WorkspaceTransferUiState()
                transferJob = null
            }
        }
    }

    fun cancelTransfer() {
        transferJob?.cancel()
    }
}

/**
 * 把工作区名转成可安全用于文件名的形式（用于导出文件名）。
 */
internal fun workspaceTransferSafeName(name: String): String = name
    .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
    .trim()
    .ifBlank { "Sandbox" }
    .take(80)