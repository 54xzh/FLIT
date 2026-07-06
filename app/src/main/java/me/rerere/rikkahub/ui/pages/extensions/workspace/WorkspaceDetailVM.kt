package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.repository.WorkspaceRepository

class WorkspaceDetailVM(
    private val workspaceId: String,
    private val repository: WorkspaceRepository,
) : ViewModel() {

    val workspace: StateFlow<WorkspaceEntity?> = kotlinx.coroutines.flow.flow {
        emit(repository.getById(workspaceId))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * 把 SAF treeUri 解析成可读短路径（如 "Documents/chat"），在 IO 线程算好，
     * UI 直接读结果，避免在渲染路径里查 SAF 卡主线程。
     * workspace 为空时为 null，UI 自行回退到原始 treeUri。
     */
    val friendlyRootPath: StateFlow<String?> = workspace.map { ws ->
        ws?.let { friendlyRootPath(it.treeUri) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _toolApprovals = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val toolApprovals: StateFlow<Map<String, Boolean>> = _toolApprovals.asStateFlow()

    init {
        viewModelScope.launch {
            _toolApprovals.value = repository.getById(workspaceId)?.toolApprovalOverrides() ?: emptyMap()
        }
    }

    /**
     * 把 SAF treeUri 解析成可读短路径，比如
     * content://com.android.externalstorage.documents/tree/primary%3ADocuments%2Fchat
     * -> "Documents/chat"
     * 解析失败回退到授权目录名（friendlyName），再失败回退到原始 treeUri。
     * 内部会走 SAF(ContentResolver) 查询，必须在 IO 线程调用。
     */
    private suspend fun friendlyRootPath(treeUri: String): String = withContext(Dispatchers.IO) {
        val decoded = runCatching {
            val raw = android.net.Uri.parse(treeUri)
                .pathSegments
                .dropWhile { it != "tree" }
                .drop(1) // 去掉 "tree" 段
                .joinToString("/")
            val path = android.net.Uri.decode(raw).replace(':', '/')
            // 去掉开头的卷标识（如 "primary/"），保留后面的相对路径
            path.substringAfter('/')
        }.getOrNull()?.takeIf { it.isNotBlank() }
        if (!decoded.isNullOrEmpty()) return@withContext "/$decoded"
        val friendly = runCatching { repository.friendlyName(treeUri, treeUri) }.getOrNull()
        // friendlyName 失败时回退返回原始 treeUri，此时不加前缀，避免出现 "/content://..."
        if (!friendly.isNullOrBlank() && friendly != treeUri) "/$friendly" else treeUri
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        _toolApprovals.update { it + (toolName to needsApproval) }
        persist()
    }

    /** allowAll=true 表示全部免审批（needsApproval=false） */
    fun setAll(allowAll: Boolean) {
        val allTools = WORKSPACE_TOOL_NAMES
        _toolApprovals.update { current ->
            allTools.associateWith { !allowAll } + current.filterKeys { it !in allTools }
        }
        persist()
    }

    private fun persist() {
        viewModelScope.launch {
            runCatching { repository.setToolApprovals(workspaceId, _toolApprovals.value) }
        }
    }

    fun rename(name: String) {
        viewModelScope.launch {
            runCatching { repository.rename(workspaceId, name) }
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            runCatching { repository.delete(workspaceId) }
            onDone()
        }
    }

    companion object {
        val WORKSPACE_TOOL_NAMES = listOf(
            "workspace_list",
            "workspace_read_file",
            "workspace_write_file",
            "workspace_mkdir",
            "workspace_delete",
            "workspace_rename",
            "eval_python",
            "run_skill_script",
        )
    }
}