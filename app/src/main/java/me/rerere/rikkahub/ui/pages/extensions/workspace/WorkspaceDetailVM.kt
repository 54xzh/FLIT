package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.InputStream
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
import me.rerere.rikkahub.data.db.entity.WORKSPACE_TOOL_NAMES
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.db.entity.toolDefaultNeedsApproval
import me.rerere.rikkahub.data.repository.SafRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.data.repository.WorkspaceFileEntry

class WorkspaceDetailVM(
    private val workspaceId: String,
    private val repository: WorkspaceRepository,
    private val safRepository: SafRepository,
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
        ws?.let { repository.friendlyShortPath(it.treeUri) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _toolApprovals = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val toolApprovals: StateFlow<Map<String, Boolean>> = _toolApprovals.asStateFlow()

    /**
     * 取某工具当前是否需要审批：有覆盖用覆盖，否则用默认值 [toolDefaultNeedsApproval]。
     * true=需要审批（弹审批卡片），false=免审批。
     */
    fun needsApproval(toolName: String): Boolean =
        _toolApprovals.value[toolName] ?: toolDefaultNeedsApproval(toolName)

    /** 是否所有工具都需审批（用于「全部需审批」总开关的勾选状态）。 */
    fun allNeedApproval(): Boolean =
        WORKSPACE_TOOL_NAMES.all { needsApproval(it) }
    // ---- 文件管理状态 ----
    /**
     * 文件管理页状态。path 是相对工作区根的相对路径，"" = 根目录。
     */
    private val _filesState = MutableStateFlow(FilesState())
    val filesState: StateFlow<FilesState> = _filesState.asStateFlow()

    /** 已解析的工作区根 DocumentFile；首次加载时懒解析，失效后置 null 重新解析。 */
    private var rootDoc: DocumentFile? = null

    init {
        viewModelScope.launch {
            _toolApprovals.value = repository.getById(workspaceId)?.toolApprovalOverrides() ?: emptyMap()
        }
        // workspace 首次加载完成后，自动加载文件列表
        viewModelScope.launch {
            workspace.collect { ws ->
                if (ws != null && rootDoc == null) refreshFiles()
            }
        }
    }

    /** 打开子目录（仅对目录条目有效）。 */
    fun open(entry: WorkspaceFileEntry) {
        if (!entry.isDirectory) return
        _filesState.update { it.copy(path = entry.path, error = null) }
        refreshFiles()
    }

    /** 返回上一层；已在根目录则不动作。 */
    fun goUp() {
        val current = _filesState.value
        if (current.path.isBlank()) return
        val parent = current.path.substringBeforeLast('/', missingDelimiterValue = "")
        _filesState.update { it.copy(path = parent, error = null) }
        refreshFiles()
    }

    /** 重新加载当前目录列表。workspace 尚未就绪或授权失效时写 error。 */
    fun refreshFiles() {
        val ws = workspace.value ?: return
        _filesState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (rootDoc == null) {
                        rootDoc = repository.resolveRoot(ws.treeUri)
                    }
                    val root = rootDoc
                    if (root == null) {
                        _filesState.update { it.copy(loading = false, error = "workspace not accessible") }
                        return@withContext
                    }
                    val entries = safRepository.listChildren(root, _filesState.value.path)
                    _filesState.update { it.copy(loading = false, entries = entries, error = null) }
                }
            }.onFailure { e ->
                _filesState.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** 导入文件到当前目录。重名自动改名。失败时写 error。 */
    fun importFile(inputStream: InputStream, displayName: String) {
        val ws = workspace.value
        if (ws == null) {
            inputStream.close()
            return
        }
        val destDir = _filesState.value.path
        _filesState.update { it.copy(loading = true) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val root = rootDoc ?: repository.resolveRoot(ws.treeUri).also { rootDoc = it }
                    if (root == null) {
                        _filesState.update { it.copy(loading = false, error = "workspace not accessible") }
                        return@withContext
                    }
                    val entry = safRepository.importFromUri(root, destDir, inputStream, displayName)
                    if (entry == null) {
                        _filesState.update { it.copy(loading = false, error = "import failed") }
                    } else {
                        refreshFiles()
                    }
                }
            }.onFailure { e ->
                _filesState.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /** 删除条目（目录递归）。 */
    fun deleteFile(entry: WorkspaceFileEntry) {
        val root = rootDoc ?: return
        _filesState.update { it.copy(loading = true) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val ok = safRepository.delete(root, entry.path)
                    if (!ok) {
                        _filesState.update { it.copy(loading = false, error = "delete failed") }
                    } else {
                        refreshFiles()
                    }
                }
            }.onFailure { e ->
                _filesState.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    /**
     * 解析文件条目为可被外部应用打开的 SAF Uri。仅文件有效；目录/未授权返回 null。
     * 供 Page 构造 ACTION_VIEW Intent 使用（VM 不碰 Context）。
     */
    suspend fun resolveFileUri(entry: WorkspaceFileEntry): android.net.Uri? = withContext(Dispatchers.IO) {
        if (entry.isDirectory) return@withContext null
        val ws = workspace.value ?: return@withContext null
        val root = rootDoc ?: repository.resolveRoot(ws.treeUri).also { rootDoc = it } ?: return@withContext null
        safRepository.resolve(root, entry.path)?.uri
    }

    fun setToolApproval(toolName: String, needsApproval: Boolean) {
        _toolApprovals.update { it + (toolName to needsApproval) }
        persist()
    }

    /** approveAll=true 表示全部需审批（needsApproval=true）；false 表示全部免审批。 */
    fun setAll(approveAll: Boolean) {
        val allTools = WORKSPACE_TOOL_NAMES
        _toolApprovals.update { current ->
            allTools.associateWith { approveAll } + current.filterKeys { it !in allTools }
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

    /**
     * 文件管理页状态。
     * @param path 相对工作区根的路径，"" = 根目录
     * @param entries 当前目录下的条目（目录优先排序）
     * @param loading 是否正在加载
     * @param error 失败时的原始错误信息，null 表示无错误
     */
    data class FilesState(
        val path: String = "",
        val entries: List<WorkspaceFileEntry> = emptyList(),
        val loading: Boolean = false,
        val error: String? = null,
    )
}