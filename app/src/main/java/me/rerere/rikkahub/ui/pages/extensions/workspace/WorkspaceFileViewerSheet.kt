package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Skill
import me.rerere.rikkahub.data.repository.WorkspaceFileEntry
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.components.richtext.HighlightCodeBlock
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.ui.components.richtext.buildDocxPreviewHtml
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.utils.SkillZipImport
import me.rerere.rikkahub.utils.WorkspaceFileClassifier
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.File
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * 工作区文件查看器的状态：记录当前要查看的文件，以及工作区 id（用于读文本与安装技能）。
 *
 * 工作区详情页与聊天引用卡片各自持有一个实例，点击文件时调用 [show]。
 */
class WorkspaceFileViewerState {
    private var target by mutableStateOf<ViewerTarget?>(null)
    val current: ViewerTarget? get() = target

    /** 工作区详情页：用 [WorkspaceFileEntry] + 工作区 id + 当前存储区域。 */
    fun showWorkspaceEntry(
        workspaceId: String,
        entry: WorkspaceFileEntry,
        area: me.rerere.rikkahub.workspace.SandboxStorageArea = me.rerere.rikkahub.workspace.SandboxStorageArea.FILES,
    ) {
        target = ViewerTarget.WorkspaceEntry(workspaceId = workspaceId, entry = entry, area = area)
    }

    /** 聊天引用：用路径 + 文件名（无完整 entry）。 */
    fun showReference(workspaceId: String, path: String, fileName: String) {
        target = ViewerTarget.Reference(workspaceId = workspaceId, path = path, fileName = fileName)
    }

    fun dismiss() {
        target = null
    }
}

sealed interface ViewerTarget {
    val workspaceId: String
    val fileName: String

    data class WorkspaceEntry(
        override val workspaceId: String,
        val entry: WorkspaceFileEntry,
        /** 沙盒工作区存储区域；查看器读取/导出时需用同一区域，否则 ROOTFS 文件会读不到。 */
        val area: me.rerere.rikkahub.workspace.SandboxStorageArea = me.rerere.rikkahub.workspace.SandboxStorageArea.FILES,
    ) : ViewerTarget {
        override val fileName: String get() = entry.name
    }

    data class Reference(
        override val workspaceId: String,
        val path: String,
        override val fileName: String,
    ) : ViewerTarget
}

/**
 * 工作区文件查看器弹窗 + 技能安装对话框。
 *
 * 根据 [WorkspaceFileClassifier] 分流：
 * - 文本类（Markdown/代码/纯文本）→ 应用内底部弹窗预览，底部三按钮（打开 / 分享 / 导出）。
 * - .skill 技能包 → 预解析后弹安装确认对话框。
 * - 其他 → 通用底部弹窗，只显示文件名 + 三按钮。
 *
 * 用法：在页面根部挂一个实例，点击文件时 state.showXxx(...)，组件自行决定显示哪种弹窗。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceFileViewerSheet(
    state: WorkspaceFileViewerState,
    resolveFileUri: suspend (ViewerTarget) -> android.net.Uri?,
) {
    val target = state.current
    if (target == null) return

    val classification = remember(target.fileName) {
        WorkspaceFileClassifier.classify(target.fileName)
    }

    when (classification.category) {
        WorkspaceFileClassifier.Category.MARKDOWN,
        WorkspaceFileClassifier.Category.CODE,
        WorkspaceFileClassifier.Category.TEXT -> {
            TextFileViewerSheet(
                target = target,
                classification = classification,
                resolveFileUri = resolveFileUri,
                onDismiss = state::dismiss,
            )
        }
        WorkspaceFileClassifier.Category.SKILL_PACKAGE -> {
            SkillInstallDialog(
                target = target,
                resolveFileUri = resolveFileUri,
                onDismiss = state::dismiss,
            )
        }
        WorkspaceFileClassifier.Category.DOCX -> {
            DocxViewerSheet(
                target = target,
                resolveFileUri = resolveFileUri,
                onDismiss = state::dismiss,
            )
        }
        WorkspaceFileClassifier.Category.OTHER -> {
            GenericFileViewerSheet(
                target = target,
                resolveFileUri = resolveFileUri,
                onDismiss = state::dismiss,
            )
        }
    }
}

// --------------------------------------------------------------------------------
// 文本查看器
// --------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextFileViewerSheet(
    target: ViewerTarget,
    classification: WorkspaceFileClassifier.Classification,
    resolveFileUri: suspend (ViewerTarget) -> android.net.Uri?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val repository = koinInject<WorkspaceRepository>()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var loadState by remember(target) {
        mutableStateOf<ContentLoadState>(ContentLoadState.Loading)
    }

    LaunchedEffect(target) {
        loadState = ContentLoadState.Loading
        loadState = runCatching {
            val result = when (target) {
                is ViewerTarget.WorkspaceEntry -> {
                    if (target.entry.isDirectory) {
                        WorkspaceRepository.ReadTextResult.Unavailable
                    } else {
                        repository.readWorkspaceFileText(target.workspaceId, target.entry.path, area = target.area)
                    }
                }
                is ViewerTarget.Reference -> {
                    repository.readWorkspaceFileText(target.workspaceId, target.path)
                }
            }
            ContentLoadState.fromResult(result)
        }.getOrElse { ContentLoadState.Error }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 标题行：文件名
            Text(
                text = target.fileName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )

            // 内容区：内容少时按自然高度，多时封顶滚动
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
            ) {
                when (val s = loadState) {
                    ContentLoadState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                    ContentLoadState.Error, ContentLoadState.Unavailable -> {
                        Text(
                            text = stringResource(R.string.workspace_viewer_load_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is ContentLoadState.Binary -> {
                        Text(
                            text = stringResource(R.string.workspace_viewer_binary_fallback),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is ContentLoadState.Content -> {
                        ContentView(
                            content = s.content,
                            truncated = s.truncated,
                            encodingSuspect = s.encodingSuspect,
                            classification = classification,
                        )
                    }
                }
            }

            // 底部三按钮
            FileViewerActions(
                target = target,
                resolveFileUri = resolveFileUri,
            )
        }
    }
}

// --------------------------------------------------------------------------------
// DOCX 查看器
// --------------------------------------------------------------------------------

private sealed interface DocxLoadState {
    data object Loading : DocxLoadState
    data object Error : DocxLoadState
    data class Success(val html: String) : DocxLoadState
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalEncodingApi::class)
@Composable
private fun DocxViewerSheet(
    target: ViewerTarget,
    resolveFileUri: suspend (ViewerTarget) -> android.net.Uri?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val repository = koinInject<WorkspaceRepository>()
    val colorScheme = MaterialTheme.colorScheme
    // sheetGesturesEnabled=false 关掉弹窗拖拽手势，让 WebView 独占上下滑动避免抢手势。
    // 做法对齐模型选择页（ModelList）。弹窗靠点遮罩/返回键关闭。
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var loadState by remember(target) {
        mutableStateOf<DocxLoadState>(DocxLoadState.Loading)
    }

    LaunchedEffect(target) {
        loadState = DocxLoadState.Loading
        loadState = runCatching {
            // 读字节 + base64 编码 + 构建 HTML 都在 IO 线程，避免大文档阻塞主线程导致 ANR。
            withContext(Dispatchers.IO) {
                val bytes = when (target) {
                    is ViewerTarget.WorkspaceEntry -> {
                        if (target.entry.isDirectory) null
                        else repository.readWorkspaceFileBytes(target.workspaceId, target.entry.path, target.area)
                    }
                    is ViewerTarget.Reference -> {
                        repository.readWorkspaceFileBytes(target.workspaceId, target.path)
                    }
                } ?: return@withContext DocxLoadState.Error
                val html = buildDocxPreviewHtml(
                    context = context,
                    docxBase64 = Base64.encode(bytes),
                    colorScheme = colorScheme,
                )
                DocxLoadState.Success(html)
            }
        }.getOrElse { DocxLoadState.Error }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 标题行：文件名
            Text(
                text = target.fileName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )

            // 内容区：WebView 渲染 docx。WebView 无可靠固有高度，需给最小高度避免被量成 0；
            // weight(fill=false) 让内容多时封顶滚动、少时保持最小高度。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 360.dp)
                    .weight(1f, fill = false),
            ) {
                when (val s = loadState) {
                    DocxLoadState.Loading -> {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                    DocxLoadState.Error -> {
                        Text(
                            text = stringResource(R.string.workspace_viewer_load_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    is DocxLoadState.Success -> {
                        val webState = rememberWebViewState(
                            data = s.html,
                            baseUrl = "about:blank",
                            mimeType = "text/html",
                            encoding = "utf-8",
                        )
                        WebView(
                            state = webState,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // 底部三按钮
            FileViewerActions(
                target = target,
                resolveFileUri = resolveFileUri,
            )
        }
    }
}

/**
 * 通用文件查看器：不预览内容，只显示文件名 + 三按钮（打开/分享/导出）。
 * 用于图片、压缩包等非文本、非 .skill 类型。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenericFileViewerSheet(
    target: ViewerTarget,
    resolveFileUri: suspend (ViewerTarget) -> android.net.Uri?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 标题行：文件名
            Text(
                text = target.fileName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )

            // 三按钮
            FileViewerActions(
                target = target,
                resolveFileUri = resolveFileUri,
            )
        }
    }
}

/**
 * 文件查看器的三按钮操作区：其他应用打开 / 分享 / 导出。
 * 封装 SAF 导出选择器与外部打开/分享的 Uri 解析，供文本查看器和通用查看器共用。
 */
@Composable
private fun FileViewerActions(
    target: ViewerTarget,
    resolveFileUri: suspend (ViewerTarget) -> android.net.Uri?,
) {
    val context = LocalContext.current
    val repository = koinInject<WorkspaceRepository>()
    val toaster = LocalToaster.current
    val haptics = rememberPremiumHaptics()
    val scope = rememberCoroutineScope()

    // 导出：SAF CreateDocument
    var exportTarget by remember(target) { mutableStateOf<ViewerTarget?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val t = exportTarget
        exportTarget = null
        if (uri == null || t == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val output = withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri) }
                    ?: error("Unable to open save destination")
                withContext(Dispatchers.IO) {
                    output.use { out ->
                        when (t) {
                            is ViewerTarget.WorkspaceEntry ->
                                repository.exportWorkspaceFile(t.workspaceId, t.entry.path, out, t.area)
                            is ViewerTarget.Reference ->
                                repository.exportWorkspaceFile(t.workspaceId, t.path, out)
                        }
                    }
                }
                haptics.perform(HapticPattern.Success)
                toaster.show(context.getString(R.string.workspace_viewer_export_success))
            }.onFailure {
                if (it is CancellationException) throw it
                haptics.perform(HapticPattern.Error)
                toaster.show(context.getString(R.string.workspace_viewer_export_failed))
            }
        }
    }

    // 打开 / 分享：需要先解析出一个可外部访问的 Uri
    val launchExternal: (android.net.Uri.() -> Unit) -> Unit = { action ->
        scope.launch {
            val uri = withContext(Dispatchers.IO) { resolveFileUri(target) }
            if (uri == null) {
                haptics.perform(HapticPattern.Error)
                toaster.show(context.getString(R.string.workspace_detail_open_failed))
                return@launch
            }
            runCatching { uri.action() }.onFailure {
                if (it is CancellationException) throw it
                haptics.perform(HapticPattern.Error)
                toaster.show(context.getString(R.string.workspace_detail_open_failed))
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ViewerActionButton(
            modifier = Modifier.weight(1f),
            icon = Icons.AutoMirrored.Rounded.OpenInNew,
            label = stringResource(R.string.workspace_viewer_open_external),
            onClick = {
                haptics.perform(HapticPattern.Pop)
                launchExternal { openExternal(context, this, guessMime(target.fileName)) }
            },
        )
        ViewerActionButton(
            modifier = Modifier.weight(1f),
            icon = Icons.Rounded.IosShare,
            label = stringResource(R.string.workspace_viewer_share),
            onClick = {
                haptics.perform(HapticPattern.Pop)
                launchExternal { shareFile(context, this, guessMime(target.fileName)) }
            },
        )
        ViewerActionButton(
            modifier = Modifier.weight(1f),
            icon = Icons.Rounded.Download,
            label = stringResource(R.string.workspace_viewer_export),
            onClick = {
                haptics.perform(HapticPattern.Pop)
                exportTarget = target
                exportLauncher.launch(target.fileName)
            },
        )
    }
}

@Composable
private fun ViewerActionButton(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = AppShapes.ButtonPill,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1)
    }
}

@Composable
private fun ContentView(
    content: String,
    truncated: Boolean,
    encodingSuspect: Boolean,
    classification: WorkspaceFileClassifier.Classification,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (truncated) {
            InfoChip(text = stringResource(R.string.workspace_viewer_truncated), warning = true)
        }
        if (encodingSuspect) {
            InfoChip(text = stringResource(R.string.workspace_viewer_encoding_suspect), warning = true)
        }

        when (classification.category) {
            WorkspaceFileClassifier.Category.MARKDOWN -> {
                // 与聊天界面一致：SelectionContainer 包裹 MarkdownBlock 启用部分复制
                SelectionContainer {
                    MarkdownBlock(content = content)
                }
            }
            WorkspaceFileClassifier.Category.CODE -> {
                // HighlightCodeBlock 内部已自带 SelectionContainer，无需再包一层
                HighlightCodeBlock(
                    code = content,
                    language = classification.prismLanguage ?: "text",
                    completeCodeBlock = true,
                )
            }
            else -> {
                // 纯文本：等宽只读，SelectionContainer 启用部分复制
                SelectionContainer {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        softWrap = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoChip(text: String, warning: Boolean) {
    Surface(
        color = if (warning) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = AppShapes.Indicator,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (warning) MaterialTheme.colorScheme.onErrorContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private sealed interface ContentLoadState {
    data object Loading : ContentLoadState
    data object Error : ContentLoadState
    data object Unavailable : ContentLoadState
    data object Binary : ContentLoadState
    data class Content(
        val content: String,
        val truncated: Boolean,
        val encodingSuspect: Boolean,
    ) : ContentLoadState

    companion object {
        fun fromResult(result: WorkspaceRepository.ReadTextResult): ContentLoadState = when (result) {
            is WorkspaceRepository.ReadTextResult.Success -> Content(
                content = result.content,
                truncated = result.truncated,
                encodingSuspect = result.encodingSuspect,
            )
            WorkspaceRepository.ReadTextResult.Binary -> Binary
            WorkspaceRepository.ReadTextResult.Unavailable -> Unavailable
        }
    }
}

// --------------------------------------------------------------------------------
// 技能安装对话框
// --------------------------------------------------------------------------------

@Composable
private fun SkillInstallDialog(
    target: ViewerTarget,
    resolveFileUri: suspend (ViewerTarget) -> android.net.Uri?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val repository = koinInject<WorkspaceRepository>()
    val settingVM = koinViewModel<SettingVM>()
    val settings = LocalSettings.current
    val toaster = LocalToaster.current
    val haptics = rememberPremiumHaptics()
    val scope = rememberCoroutineScope()

    var previewState by remember(target) {
        mutableStateOf<SkillPreviewState>(SkillPreviewState.Loading)
    }
    var installing by remember(target) { mutableStateOf(false) }

    LaunchedEffect(target) {
        previewState = SkillPreviewState.Loading
        previewState = runCatching {
            val uri = withContext(Dispatchers.IO) { resolveFileUri(target) }
                ?: return@runCatching SkillPreviewState.Error(
                    context.getString(R.string.workspace_detail_open_failed),
                )
            when (val r = SkillZipImport.previewFromUri(context, uri)) {
                is SkillZipImport.PreviewResult.Success -> {
                    val installedNames = settings.skills.map { it.name }.toSet()
                    SkillPreviewState.Success(
                        skills = r.skills,
                        conflictNames = r.skills.map { it.name }.filter { it in installedNames }.toSet(),
                    )
                }
                is SkillZipImport.PreviewResult.Error -> SkillPreviewState.Error(r.message)
            }
        }.getOrElse { SkillPreviewState.Error(it.message ?: "Unknown error") }
    }

    val doInstall: () -> Unit = install@{
        if (installing) return@install
        val s = previewState
        if (s !is SkillPreviewState.Success) return@install
        // 与现有导入逻辑一致：存在任何重名即拒绝，不部分安装。
        if (s.conflictNames.isNotEmpty()) {
            haptics.perform(HapticPattern.Error)
            toaster.show(context.getString(
                R.string.workspace_skill_install_conflict,
                s.conflictNames.joinToString(", "),
            ))
            return@install
        }
        installing = true
        scope.launch {
            try {
                val uri = withContext(Dispatchers.IO) { resolveFileUri(target) }
                if (uri == null) {
                    haptics.perform(HapticPattern.Error)
                    toaster.show(context.getString(R.string.workspace_detail_open_failed))
                    return@launch
                }
                when (val result = SkillZipImport.importFromUri(
                    context = context,
                    uri = uri,
                    existingSkillNames = settings.skills.map { it.name }.toSet(),
                )) {
                    is SkillZipImport.ImportResult.Success -> {
                        registerInstalledSkills(settingVM, context, result)
                        haptics.perform(HapticPattern.Success)
                        toaster.show(context.getString(
                            R.string.skills_import_success,
                            result.skills.size,
                        ))
                        onDismiss()
                    }
                    is SkillZipImport.ImportResult.Error -> {
                        haptics.perform(HapticPattern.Error)
                        toaster.show(result.message)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                haptics.perform(HapticPattern.Error)
                toaster.show(e.message ?: context.getString(R.string.workspace_skill_install_failed))
            } finally {
                installing = false
            }
        }
    }

    when (val s = previewState) {
        SkillPreviewState.Loading -> {
            // 扫描很快，期间不显示任何弹窗，避免"闪一下 Loading 再出安装弹窗"的跳变。
            // 扫描完成直接进入 Error/Success，用户只看到最终结果。
        }
        is SkillPreviewState.Error -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.workspace_skill_install_title)) },
                text = { Text(s.message) },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.confirm)) }
                },
                dismissButton = {},
            )
        }
        is SkillPreviewState.Success -> {
            AlertDialog(
                onDismissRequest = { if (!installing) onDismiss() },
                title = { Text(stringResource(R.string.workspace_skill_install_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(
                                R.string.workspace_skill_install_contains,
                                s.skills.size,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        s.skills.forEach { skill ->
                            SkillPreviewRow(
                                name = skill.name,
                                description = skill.description,
                                conflict = skill.name in s.conflictNames,
                            )
                        }
                    }
                },
                confirmButton = {
                    val canInstall = s.conflictNames.isEmpty() && !installing
                    TextButton(
                        enabled = canInstall,
                        onClick = doInstall,
                    ) {
                        if (installing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(stringResource(R.string.workspace_skill_install_action))
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        enabled = !installing,
                        onClick = onDismiss,
                    ) { Text(stringResource(R.string.cancel)) }
                },
            )
        }
    }
}

@Composable
private fun SkillPreviewRow(name: String, description: String, conflict: Boolean) {
    Surface(
        color = if (conflict) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = AppShapes.ButtonSquared,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Extension,
                contentDescription = null,
                tint = if (conflict) MaterialTheme.colorScheme.onErrorContainer
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (conflict) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                if (conflict) {
                    Text(
                        text = stringResource(R.string.workspace_skill_install_already_installed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

private sealed interface SkillPreviewState {
    data object Loading : SkillPreviewState
    data class Error(val message: String) : SkillPreviewState
    data class Success(
        val skills: List<SkillZipImport.SkillPreview>,
        val conflictNames: Set<String>,
    ) : SkillPreviewState
}

/**
 * 把安装结果写回设置：与设置页 zip 导入保持一致的分组逻辑。
 * 多技能包按 archiveName 分组；archiveName 为空时用默认分组名（对齐 skills_import_folder_default）。
 */
private fun registerInstalledSkills(
    settingVM: SettingVM,
    context: Context,
    result: SkillZipImport.ImportResult.Success,
) {
    settingVM.updateSettings { old ->
        val installed = result.skills
        if (installed.size <= 1) {
            old.copy(skills = old.skills + installed)
        } else {
            val folderName = result.archiveName?.trim()?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.skills_import_folder_default)

            val existingFolder = old.skillFolders.firstOrNull { folder ->
                folder.name.trim().equals(folderName, ignoreCase = true)
            }

            val folderId = existingFolder?.id ?: kotlin.uuid.Uuid.random()
            val updatedFolders = if (existingFolder != null) {
                old.skillFolders
            } else {
                old.skillFolders + me.rerere.rikkahub.data.model.SkillFolder(
                    id = folderId,
                    name = folderName,
                )
            }

            old.copy(
                skillFolders = updatedFolders,
                skills = old.skills + installed.map { it.copy(folderId = folderId) },
            )
        }
    }
}

// --------------------------------------------------------------------------------
// 外部打开 / 分享工具
// --------------------------------------------------------------------------------

private fun guessMime(fileName: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        ?: "*/*"
}

private fun openExternal(context: Context, uri: android.net.Uri, mime: String) {
    val openMime = mime.takeUnless { it == "application/octet-stream" } ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, openMime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, null))
    } catch (_: ActivityNotFoundException) {
        throw ActivityNotFoundException()
    }
}

private fun shareFile(context: Context, uri: android.net.Uri, mime: String) {
    val shareMime = mime.takeUnless { it == "application/octet-stream" } ?: "*/*"
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = shareMime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, null))
    } catch (_: ActivityNotFoundException) {
        throw ActivityNotFoundException()
    }
}