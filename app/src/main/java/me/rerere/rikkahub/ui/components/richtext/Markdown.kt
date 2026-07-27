package me.rerere.rikkahub.ui.components.richtext

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.core.net.toUri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.foundation.Image as ComposeImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.datastore.RpStyleRule
import me.rerere.rikkahub.data.datastore.getEffectiveDisplaySetting
import me.rerere.rikkahub.ui.components.table.DataTable
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import me.rerere.rikkahub.ui.components.ui.permission.PermissionReadExternalStorage
import me.rerere.rikkahub.ui.components.ui.permission.PermissionReadMediaImages
import me.rerere.rikkahub.ui.components.ui.permission.PermissionReadMediaVideo
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.utils.LocalFileUrlUtils
import me.rerere.rikkahub.utils.toDp
import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.CompositeASTNode
import org.intellij.markdown.ast.LeafASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser
import kotlin.math.max
import kotlin.math.roundToInt

private val flavour by lazy {
    GFMFlavourDescriptor(
        makeHttpsAutoLinks = true, useSafeLinks = true
    )
}

private val parser by lazy {
    MarkdownParser(flavour)
}

private val INLINE_LATEX_REGEX = Regex("\\\\\\((.+?)\\\\\\)")
private val BLOCK_LATEX_REGEX = Regex("\\\\\\[(.+?)\\\\\\]", RegexOption.DOT_MATCHES_ALL)
// Matches <think>...</think> or <thinking>...</thinking> with optional closing tag
val THINKING_REGEX = Regex("<think(?:ing)?>([\\s\\S]*?)(?:</think(?:ing)?>|$)", RegexOption.DOT_MATCHES_ALL)
// Matches orphaned closing tags: content followed by </think> or </thinking> without opening tag
private val ORPHAN_CLOSE_TAG_REGEX = Regex("^([\\s\\S]*?)</think(?:ing)?>", RegexOption.DOT_MATCHES_ALL)
private val CODE_BLOCK_REGEX = Regex("```[\\s\\S]*?```|`[^`\n]*`", RegexOption.DOT_MATCHES_ALL)
private val BREAK_LINE_REGEX = Regex("(?i)<br\\s*/?>")
private const val MARKDOWN_LAZY_RENDER_MIN_CHARS = 4_000
private const val MARKDOWN_LAZY_RENDER_MIN_BLOCKS = 8
private const val MARKDOWN_LAZY_INITIAL_RENDER_BLOCKS = 3
// 向上翻历史时消息从底部先进入视口，尾部块也需要立即渲染，避免先看到占位再闪现内容
private const val MARKDOWN_LAZY_INITIAL_RENDER_TAIL_BLOCKS = 2
private const val MARKDOWN_ESTIMATED_CHARS_PER_LINE = 42
private val MarkdownLazyViewportPadding = 1_200.dp

// 惰性分块的实测高度缓存：消息条目滚出屏幕后组合被销毁，滚回来时若仍用估算高度作占位，
// 与真实高度的偏差会造成滚动位置跳动；按消息键共享实测高度即可让占位精确到像素。
// meta 用于失效判定：消息被编辑或切换到非前缀增长的内容、字号/可用宽度等布局环境变化后，
// 旧实测值比估算值更具迷惑性，必须整表清空重测。
// 只存长度+哈希而非全文，避免缓存长期持有整篇消息文本；前缀判定用"新文本取旧长度的前缀再哈希"完成。
// meta 与 heights 都是快照状态：组合被放弃时两者一起回滚，不会出现"元数据已推进、清空被回滚"的错位。
private data class LazyBlockHeightsMeta(
    val contentLength: Int,
    val contentHash: Int,
    val layoutFingerprint: String,
    val blockCount: Int,
)

private class LazyBlockHeightsEntry(initialMeta: LazyBlockHeightsMeta) {
    val meta = mutableStateOf(initialMeta)
    val heights: SnapshotStateMap<String, Int> = mutableStateMapOf()
}

private val lazyBlockHeightsCache = object : android.util.LruCache<String, LazyBlockHeightsEntry>(64) {
    override fun sizeOf(key: String, value: LazyBlockHeightsEntry): Int = 1
}

/**
 * CompositionLocal for RP style rules - enables color customization throughout the markdown tree
 */
val LocalRpStyleRules = compositionLocalOf<List<RpStyleRule>> { emptyList() }

/**
 * Safely get color from RP style rule for a given pattern.
 * Returns null if pattern not found, not enabled, or color parsing fails.
 */
@Composable
private fun getRpColor(pattern: String): Color? {
    val rules = LocalRpStyleRules.current
    val rule = rules.find { it.pattern == pattern && it.enabled } ?: return null
    return runCatching { Color(android.graphics.Color.parseColor(rule.colorHex)) }.getOrNull()
}

// Standard markdown patterns that are handled by the AST parser.
// Note: some edge cases (e.g. **"quoted"**) may not be parsed as STRONG by the markdown parser,
// so we keep a small fallback set of standard *wrapping* delimiters to handle them in plain text nodes.
private val STANDARD_PATTERNS = setOf("*", "**", "***", "~~", "`", "#", "##", "###", "####", "#####", "######", ">")
private val FALLBACK_WRAPPING_PATTERNS = listOf("***", "**", "*", "~~", "`")

private data class PatternRegexStyle(
    val regex: Regex,
    val style: SpanStyle
)

private fun parseRpColor(colorHex: String): Color? {
    return runCatching { Color(android.graphics.Color.parseColor(colorHex)) }.getOrNull()
}

private fun spanStyleForPattern(pattern: String, color: Color?): SpanStyle {
    return when (pattern) {
        "*" -> SpanStyle(fontStyle = FontStyle.Italic, color = color ?: Color.Unspecified)
        "**" -> SpanStyle(fontWeight = FontWeight.SemiBold, color = color ?: Color.Unspecified)
        "***" -> SpanStyle(fontWeight = FontWeight.SemiBold, fontStyle = FontStyle.Italic, color = color ?: Color.Unspecified)
        "~~" -> SpanStyle(textDecoration = TextDecoration.LineThrough, color = color ?: Color.Unspecified)
        "`" -> SpanStyle(fontFamily = FontFamily.Monospace, color = color ?: Color.Unspecified)
        else -> SpanStyle(color = color ?: Color.Unspecified)
    }
}

private fun buildWrappingRegex(pattern: String): Regex {
    val dotAll = setOf(RegexOption.DOT_MATCHES_ALL)
    return when (pattern) {
        "***" -> Regex("(?<!\\*)\\*\\*\\*(?![\\s*])(.+?)(?<![\\s*])\\*\\*\\*(?!\\*)", dotAll)
        "**" -> Regex("(?<!\\*)\\*\\*(?![\\s*])(.+?)(?<![\\s*])\\*\\*(?!\\*)", dotAll)
        "*" -> Regex("(?<!\\*)\\*(?![\\s*])(.+?)(?<![\\s*])\\*(?!\\*)", dotAll)
        "~~" -> Regex("(?<!~)~~(?![\\s~])(.+?)(?<![\\s~])~~(?!~)", dotAll)
        "`" -> Regex("(?<!`)`([^`\\n]+?)`(?!`)")
        else -> {
            val escaped = Regex.escape(pattern)
            if (pattern.length >= 2 && pattern.all { it == pattern.first() }) {
                val escapedChar = Regex.escape(pattern.first().toString())
                Regex("(?<!$escapedChar)$escaped(.+?)$escaped(?!$escapedChar)", dotAll)
            } else {
                Regex("$escaped(.+?)$escaped", dotAll)
            }
        }
    }
}

// 规则列表几乎不变，而本函数在流式渲染中每个文本节点都会调用；
// 缓存最近一份编译结果，避免每次重新 Pattern.compile。
private val patternRegexCache =
    java.util.concurrent.atomic.AtomicReference<Pair<List<RpStyleRule>, List<PatternRegexStyle>>?>(null)

private fun buildPatternRegexes(rpStyleRules: List<RpStyleRule>): List<PatternRegexStyle> {
    patternRegexCache.get()?.let { (cachedRules, cachedRegexes) ->
        if (cachedRules == rpStyleRules) return cachedRegexes
    }
    val result = computePatternRegexes(rpStyleRules)
    patternRegexCache.set(rpStyleRules to result)
    return result
}

private fun computePatternRegexes(rpStyleRules: List<RpStyleRule>): List<PatternRegexStyle> {
    val enabledRulesByPattern = linkedMapOf<String, RpStyleRule>()
    rpStyleRules.forEach { rule ->
        if (rule.enabled && rule.pattern.isNotBlank() && !enabledRulesByPattern.containsKey(rule.pattern)) {
            enabledRulesByPattern[rule.pattern] = rule
        }
    }

    val fallbackRegexes = FALLBACK_WRAPPING_PATTERNS.map { pattern ->
        PatternRegexStyle(
            regex = buildWrappingRegex(pattern),
            style = spanStyleForPattern(pattern, enabledRulesByPattern[pattern]?.let { parseRpColor(it.colorHex) })
        )
    }

    val customRegexes = enabledRulesByPattern.values
        .asSequence()
        .filter { rule -> rule.pattern !in STANDARD_PATTERNS }
        .mapNotNull { rule ->
            val color = parseRpColor(rule.colorHex) ?: return@mapNotNull null
            PatternRegexStyle(
                regex = buildWrappingRegex(rule.pattern),
                style = spanStyleForPattern(rule.pattern, color)
            )
        }
        .toList()

    return fallbackRegexes + customRegexes
}

/**
 * Append text to AnnotatedString.Builder, scanning for RP patterns in plain text nodes.
 * Normally, standard markdown patterns are handled by the AST parser, but we also provide a fallback
 * for standard *wrapping* delimiters (e.g. `**...**`) in case the parser doesn't recognize them.
 * For each custom pattern, builds a regex like `pattern(.+?)pattern` and applies the color.
 */
private fun AnnotatedString.Builder.appendTextWithCustomPatterns(
    text: String,
    rpStyleRules: List<RpStyleRule>
) {
    val patternRegexes = buildPatternRegexes(rpStyleRules)
    
    if (patternRegexes.isEmpty()) {
        append(text)
        return
    }
    
    // Find all matches from all patterns
    data class Match(val range: IntRange, val content: String, val style: SpanStyle)
    val allMatches = mutableListOf<Match>()
    
    patternRegexes.forEach { patternRegex ->
        val regex = patternRegex.regex
        val style = patternRegex.style
        regex.findAll(text).forEach { matchResult ->
            val content = matchResult.groups[1]?.value ?: return@forEach
            allMatches.add(Match(
                range = matchResult.range,
                content = content,
                style = style
            ))
        }
    }
    
    // Sort by start position, then prefer longer matches at the same start (e.g. "**" over "*")
    allMatches.sortWith(compareBy<Match>({ it.range.first }, { -it.range.last }))
    
    // Remove overlapping matches (keep earlier ones)
    val nonOverlapping = mutableListOf<Match>()
    var lastEnd = -1
    allMatches.forEach { match ->
        if (match.range.first > lastEnd) {
            nonOverlapping.add(match)
            lastEnd = match.range.last
        }
    }
    
    // Build the annotated string
    var currentIndex = 0
    nonOverlapping.forEach { match ->
        // Append text before this match
        if (match.range.first > currentIndex) {
            append(text.substring(currentIndex, match.range.first))
        }
        // Append the styled content (without the pattern delimiters)
        withStyle(match.style) {
            append(match.content)
        }
        currentIndex = match.range.last + 1
    }
    
    // Append remaining text
    if (currentIndex < text.length) {
        append(text.substring(currentIndex))
    }
}

private fun AnnotatedString.Builder.appendInlineChildrenWithFallback(
    nodes: List<ASTNode>,
    content: String,
    trim: Boolean,
    inlineContents: MutableMap<String, InlineTextContent>,
    colorScheme: ColorScheme,
    density: Density,
    style: TextStyle,
    onClickCitation: (String) -> Unit,
    rpStyleRules: List<RpStyleRule>,
) {
    val textBuffer = StringBuilder()

    fun flushTextBuffer() {
        if (textBuffer.isEmpty()) return
        val text = textBuffer
            .toString()
            .let { source -> if (trim) source.trim() else source }
            .replace(BREAK_LINE_REGEX, "\n")
        appendTextWithCustomPatterns(text, rpStyleRules)
        textBuffer.clear()
    }

    nodes.fastForEach { child ->
        if (child is LeafASTNode) {
            textBuffer.append(child.getTextInNode(content))
        } else {
            flushTextBuffer()
            appendMarkdownNodeContent(
                node = child,
                content = content,
                trim = trim,
                inlineContents = inlineContents,
                colorScheme = colorScheme,
                density = density,
                style = style,
                onClickCitation = onClickCitation,
                rpStyleRules = rpStyleRules
            )
        }
    }

    flushTextBuffer()
}

internal fun buildAnnotatedStringWithCustomPatternsForTest(
    text: String,
    rpStyleRules: List<RpStyleRule> = emptyList()
): AnnotatedString {
    return buildAnnotatedString {
        appendTextWithCustomPatterns(text, rpStyleRules)
    }
}

internal fun buildAnnotatedStringWithMarkdownParserForTest(
    content: String,
    rpStyleRules: List<RpStyleRule> = emptyList()
): AnnotatedString {
    val preprocessed = preProcess(content)
    val astTree = parser.buildMarkdownTreeFromString(preprocessed)
    val paragraph = astTree.findChildOfTypeRecursive(MarkdownElementTypes.PARAGRAPH)
        ?: return AnnotatedString(preprocessed)

    return buildAnnotatedString {
        appendInlineChildrenWithFallback(
            nodes = paragraph.children,
            content = preprocessed,
            trim = false,
            inlineContents = mutableMapOf(),
            colorScheme = lightColorScheme(),
            density = Density(1f),
            style = TextStyle.Default,
            onClickCitation = {},
            rpStyleRules = rpStyleRules
        )
    }
}

// 短内容同步解析几乎无感；超过该长度的首次解析放到后台，避免长消息滚回屏幕时主线程掉帧
private const val MARKDOWN_SYNC_PARSE_MAX_CHARS = 4_000

// 纯文本兜底帧的渲染长度上限
private const val MARKDOWN_FALLBACK_MAX_CHARS = 6_000

// 解析结果缓存：LazyColumn 中消息滚出屏幕会销毁组合，滚回来直接复用，免去整篇重新解析。
// 按源文本长度计权（全文 + 各块子串拷贝约两倍），总量封顶防止无界增长。
private val markdownAstCache = object : android.util.LruCache<String, MarkdownRenderData>(256 * 1024) {
    override fun sizeOf(key: String, value: MarkdownRenderData): Int = (key.length * 2).coerceAtLeast(1)
}

/**
 * 单个顶层 Markdown 块的渲染快照。node 是经过"重定基"的独立子树：
 * 偏移以块起点为 0、与整棵解析树断开，渲染时以 [blockText] 作为源文本，
 * 因此快照只持有本块的文本与子树，不会钉住某一版全文字符串或整棵旧 AST。
 *
 * equals 只比较块源文本与"是否存在后继节点"：流式追加时，虽然每次全文重解析会产生
 * 全新的 AST 实例，但源文本未变的块快照依然相等，让对应的组合作用域整体跳过重组，
 * 每 tick 实际只重建正在增长的尾部块。
 *
 * hasNextSibling 参与比较是因为段落渲染依赖"后面是否还有节点"决定底部间距，
 * 一个块从"最后一块"变成"非最后一块"时必须重建，否则会沿用旧的兄弟关系。
 */
@Immutable
internal class MarkdownBlockSnapshot(
    val blockText: String,
    val hasNextSibling: Boolean,
    val node: ASTNode,
    // 合成父节点（为 node 提供"后面还有内容"的兄弟关系）。渲染不直接读取它，
    // 显式持有是为了让 parent 链的存活不依赖构造副作用与 GC 时序
    val syntheticParent: ASTNode?,
) {
    override fun equals(other: Any?): Boolean = this === other ||
        (other is MarkdownBlockSnapshot &&
            other.blockText == blockText &&
            other.hasNextSibling == hasNextSibling)

    override fun hashCode(): Int = 31 * blockText.hashCode() + hasNextSibling.hashCode()
}

internal class MarkdownRenderData(
    val preprocessed: String,
    val snapshots: List<MarkdownBlockSnapshot>,
)

private fun parseMarkdown(content: String, previous: MarkdownRenderData?): MarkdownRenderData {
    val preprocessed = preProcess(content)
    val astTree = parser.buildMarkdownTreeFromString(preprocessed)
    return MarkdownRenderData(
        preprocessed = preprocessed,
        snapshots = buildBlockSnapshots(previous?.snapshots, preprocessed, astTree),
    )
}

// 与上一版快照逐位比对：源文本与"是否有后继"都没变的块直接复用旧快照，
// 免去重复的子树深拷贝；流式追加时通常只有尾部块需要新建
private fun buildBlockSnapshots(
    previous: List<MarkdownBlockSnapshot>?,
    preprocessed: String,
    astTree: ASTNode,
): List<MarkdownBlockSnapshot> {
    val children = astTree.children
    return children.mapIndexed { index, child ->
        val start = child.startOffset
        val end = child.endOffset
        val hasNextSibling = index < children.lastIndex
        val prev = previous?.getOrNull(index)
        if (
            prev != null &&
            prev.hasNextSibling == hasNextSibling &&
            prev.node.type == child.type &&
            prev.blockText.length == end - start &&
            preprocessed.startsWith(prev.blockText, start)
        ) {
            prev
        } else {
            createBlockSnapshot(
                blockText = preprocessed.substring(start, end),
                hasNextSibling = hasNextSibling,
                sourceNode = child,
            )
        }
    }
}

private fun createBlockSnapshot(
    blockText: String,
    hasNextSibling: Boolean,
    sourceNode: ASTNode,
): MarkdownBlockSnapshot {
    val rebased = rebaseNode(sourceNode, -sourceNode.startOffset)
    // 段落底部间距依赖 nextSibling() 判断。给重定基后的节点补一个合成父节点和
    // 零长度 EOL 兄弟节点（CompositeASTNode 构造时会为子节点建立 parent 链），
    // 保留"后面还有内容"的语义；父节点本身不参与渲染
    val syntheticParent = if (hasNextSibling) {
        CompositeASTNode(
            MarkdownElementTypes.MARKDOWN_FILE,
            listOf(rebased, LeafASTNode(MarkdownTokenTypes.EOL, blockText.length, blockText.length)),
        )
    } else {
        null
    }
    return MarkdownBlockSnapshot(
        blockText = blockText,
        hasNextSibling = hasNextSibling,
        node = rebased,
        syntheticParent = syntheticParent,
    )
}

// 深拷贝子树并把偏移平移到以块起点为 0。CompositeASTNode 的范围由子节点推导，
// 解析器构造复合节点的方式相同，因此平移后范围与原节点严格一致。
// 空复合节点保持复合类型（范围恒为 0..0），叶子偏移下限钳到 0，双保险防越界
private fun rebaseNode(node: ASTNode, shift: Int): ASTNode {
    val children = node.children
    return when {
        children.isNotEmpty() -> CompositeASTNode(node.type, children.map { rebaseNode(it, shift) })
        node is CompositeASTNode -> CompositeASTNode(node.type, emptyList())
        else -> LeafASTNode(
            node.type,
            (node.startOffset + shift).coerceAtLeast(0),
            (node.endOffset + shift).coerceAtLeast(0),
        )
    }
}

internal fun parseMarkdownForTest(content: String): MarkdownRenderData =
    parseMarkdown(content, previous = null)

// 流式输出时每次全文重解析的开销随长度增长，限频间隔相应放宽；短消息保持逐帧即时感
private fun markdownParseThrottleIntervalMs(contentLength: Int): Long = when {
    contentLength < 2_000 -> 0L
    contentLength < 8_000 -> 50L
    else -> 120L
}

// 预处理markdown内容
private fun preProcess(content: String): String {
    // 先找出所有代码块的位置
    val codeBlocks = mutableListOf<IntRange>()
    CODE_BLOCK_REGEX.findAll(content).forEach { match ->
        codeBlocks.add(match.range)
    }

    // 检查位置是否在代码块内
    fun isInCodeBlock(position: Int): Boolean {
        return codeBlocks.any { range -> position in range }
    }

    // 替换行内公式 \( ... \) 到 $ ... $，但跳过代码块内的内容
    var result = INLINE_LATEX_REGEX.replace(content) { matchResult ->
        if (isInCodeBlock(matchResult.range.first)) {
            matchResult.value // 保持原样
        } else {
            "$" + matchResult.groupValues[1] + "$"
        }
    }

    // 替换块级公式 \[ ... \] 到 $$ ... $$，但跳过代码块内的内容
    result = BLOCK_LATEX_REGEX.replace(result) { matchResult ->
        if (isInCodeBlock(matchResult.range.first)) {
            matchResult.value // 保持原样
        } else {
            "$$" + matchResult.groupValues[1] + "$$"
        }
    }

    // 替换思考 - handles both <think> and <thinking> tags
    result = result.replace(THINKING_REGEX) { matchResult ->
        matchResult.groupValues[1].lines().filter { it.isNotBlank() }.joinToString("\n") { ">$it" }
    }

    // Handle orphaned closing tags (missing opening tag) - common with some models
    result = result.replace(ORPHAN_CLOSE_TAG_REGEX) { matchResult ->
        matchResult.groupValues[1].lines().filter { it.isNotBlank() }.joinToString("\n") { ">$it" }
    }

    return result
}


@Preview(showBackground = true)
@Composable
private fun MarkdownPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MarkdownBlock(
                content = "Hi there!", modifier = Modifier.background(Color.Red)
            )
            MarkdownBlock(
                content = """
                    ### 🌍 This is Markdown Test This Markdown Test
                    1. How many roads must a man walk down
                        * the slings and arrows of outrageous fortune, Or to take arms against a sea of troubles,
                        * by opposing end them.
                            * How many times must a man look up, Before he can see the sky?
                            * How many times $ f(x) = \sum_{n=0}^{\infty} \frac{f^{(n)}(a)}{n!}(x-a)^n$
                    2. How many times must a man look up, Before he can see the sky?

                    * [ ] Before they're allowed to be free? Yes, 'n' how many times can a man turn his head
                    * [x] Before they're allowed to be free? Yes, 'n' how many times can a man turn his head

                    4. For in that sleep of death what dreams may come [citation](1)

                    This is Markdown Test, This <br/> is Markdown Test.
                    ha<br/>ha

                    ***
                    This is Markdown Test, This is Markdown Test.

                    | Name | Age | Address | Email | Job | Homepage |
                    | ---- | --- | ------- | ----- | --- | -------- |
                    | John | 25  | New York | john@example.com | Software Engineer | john.com |
                    | Jane | 26  | London   | jane@example.com | Data Scientist | jane.com |

                    ## HTML Escaping
                    This is a &gt;  test

                """.trimIndent()
            )
        }
    }
}

@Composable
fun MarkdownBlock(
    content: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    onClickCitation: (String) -> Unit = {},
    exportAssets: MermaidExportAssets? = null,
    lazyRenderOffscreen: Boolean = false,
    lazyBlockHeightsCacheKey: String? = null,
    lazyKeepRenderedBlocks: Boolean = false,
) {
    // Read rpStyleRules from settings
    val settings = LocalSettings.current
    val rpStyleRules = settings.displaySetting.rpStyleRules
    val effectiveDisplaySetting = settings.getEffectiveDisplaySetting()
    val heightCacheLayoutKey = remember(effectiveDisplaySetting) {
        listOf(
            effectiveDisplaySetting.fontSizeRatio,
            effectiveDisplaySetting.fontSettings,
            effectiveDisplaySetting.codeBlockAutoWrap,
            effectiveDisplaySetting.codeBlockAutoCollapse,
        ).hashCode()
    }
    
    val dataState = remember {
        mutableStateOf(
            value = markdownAstCache.get(content) ?: run {
                // 导出走离屏组合 + 定时截图，兜底帧会被截进图片，必须同步解析
                if (exportAssets != null || content.length <= MARKDOWN_SYNC_PARSE_MAX_CHARS) {
                    parseMarkdown(content, previous = null).also { markdownAstCache.put(content, it) }
                } else {
                    // 长内容首次解析放后台（下方 LaunchedEffect 的首次发射会立即解析），
                    // 此处先返回 null，渲染一帧纯文本兜底
                    null
                }
            },
            policy = referentialEqualityPolicy(),
        )
    }
    val data = dataState.value
    // 引用点击回调收敛为常驻实例：上层重组产生新 lambda 时不再连带使所有块作用域失效
    val currentOnClickCitation by rememberUpdatedState(onClickCitation)
    val stableOnClickCitation = remember { { citationId: String -> currentOnClickCitation(citationId) } }

    // 监听内容变化，重新解析AST树
    // 这里在后台线程解析AST树, 防止频繁更新的时候掉帧
    val updatedContent by rememberUpdatedState(content)
    LaunchedEffect(Unit) {
        var lastParsedText: String? = null
        // conflate + 处理后延时 = 限频：流式输出时跳过中间态，最新内容总会被解析，不丢字
        snapshotFlow { updatedContent }.distinctUntilChanged().conflate().collect { text ->
            // 缓存命中时若与当前值为同一实例，referentialEqualityPolicy 保证不触发重组，
            // 也因此消除了旧实现中"首帧同步解析后、初始发射又重复解析一次"的双重开销
            val cached = markdownAstCache.get(text)
            if (cached != null) {
                dataState.value = cached
                lastParsedText = text
                return@collect
            }
            // 传入上一版结果供快照逐位复用，流式追加时只有尾部块需要重建
            val previous = dataState.value
            val parsed = withContext(Dispatchers.Default) {
                runCatching { parseMarkdown(text, previous) }
                    .onFailure { it.printStackTrace() }
                    .getOrNull()
            } ?: return@collect
            // 流式增长（新文本以旧文本为前缀）时，旧内容只是中间态，从缓存移除避免挤占
            // 其他消息的条目；分支切换/编辑等非前缀变化不移除，保留双方的完整解析结果
            lastParsedText
                ?.takeIf { it != text && text.startsWith(it) }
                ?.let { markdownAstCache.remove(it) }
            markdownAstCache.put(text, parsed)
            lastParsedText = text
            dataState.value = parsed
            val intervalMs = markdownParseThrottleIntervalMs(text.length)
            if (intervalMs > 0) delay(intervalMs)
        }
    }

    if (data == null) {
        // 后台解析完成前的兜底：以纯文本渲染，通常只出现一帧。
        // 截断超长文本，避免为一帧占位付出全文布局开销
        ProvideTextStyle(style) {
            Column(modifier = modifier.padding(start = 4.dp)) {
                Text(text = content.take(MARKDOWN_FALLBACK_MAX_CHARS))
            }
        }
        return
    }
    val shouldLazyRenderOffscreen = lazyRenderOffscreen &&
        exportAssets == null &&
        data.preprocessed.length >= MARKDOWN_LAZY_RENDER_MIN_CHARS &&
        data.snapshots.size >= MARKDOWN_LAZY_RENDER_MIN_BLOCKS
    // Provide rpStyleRules to entire tree via CompositionLocal
    CompositionLocalProvider(LocalRpStyleRules provides rpStyleRules) {
        ProvideTextStyle(style) {
            Column(
                modifier = modifier.padding(start = 4.dp)
            ) {
                when {
                    // 导出走离屏一次性渲染，无流式增量需求，保持直接遍历
                    exportAssets != null -> {
                        data.snapshots.fastForEach { snapshot ->
                            MarkdownNode(
                                node = snapshot.node,
                                content = snapshot.blockText,
                                onClickCitation = stableOnClickCitation,
                                exportAssets = exportAssets,
                            )
                        }
                    }

                    shouldLazyRenderOffscreen -> {
                        // BoxWithConstraints 提供真实可用宽度（受多选勾选框、分屏、旋转影响），
                        // 供实测高度缓存做布局指纹；宽度变化即换行变化，旧实测高度必须失效
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val layoutWidth = maxWidth
                            Column {
                                LazyMarkdownChildren(
                                    snapshots = data.snapshots,
                                    content = data.preprocessed,
                                    heightsCacheKey = lazyBlockHeightsCacheKey,
                                    keepRenderedBlocks = lazyKeepRenderedBlocks,
                                    layoutWidth = layoutWidth,
                                    heightCacheLayoutKey = heightCacheLayoutKey,
                                    onClickCitation = stableOnClickCitation,
                                )
                            }
                        }
                    }

                    else -> {
                        data.snapshots.fastForEach { snapshot ->
                            MarkdownTopLevelBlock(
                                snapshot = snapshot,
                                onClickCitation = stableOnClickCitation,
                            )
                        }
                    }
                }
            }
        }
    }
}

// 顶层块的独立重组作用域：快照相等（按块源文本比较）时整块跳过重组，
// 流式追加每 tick 只会真正重建正在增长的尾部块
@Composable
private fun MarkdownTopLevelBlock(
    snapshot: MarkdownBlockSnapshot,
    onClickCitation: (String) -> Unit,
) {
    MarkdownNode(
        node = snapshot.node,
        content = snapshot.blockText,
        onClickCitation = onClickCitation,
    )
}

@Composable
private fun LazyMarkdownChildren(
    snapshots: List<MarkdownBlockSnapshot>,
    content: String,
    heightsCacheKey: String?,
    keepRenderedBlocks: Boolean,
    layoutWidth: Dp,
    heightCacheLayoutKey: Int,
    onClickCitation: (String) -> Unit,
) {
    // 可用宽度、密度、系统字体缩放、字号、字体族、字重任一变化都会改变换行/像素结果，实测高度随之失效
    val textStyle = LocalTextStyle.current
    val density = LocalDensity.current
    val layoutFingerprint =
        "$layoutWidth:${density.density}:${density.fontScale}:${textStyle.hashCode()}:$heightCacheLayoutKey"
    // EOL 等空白 token 渲染高度为 0，若走惰性包装，估算函数会把它们当两行高造成占位系统性虚高；
    // 直接渲染（零成本）并跳过惰性包装。尾部初始渲染按"最后 N 个非空白块"计——消息以换行结尾时
    // 末尾是零高度 EOL，只按下标取尾会让真正的最后一个内容块错过初始渲染。
    val (blankNodeFlags, tailRenderStartIndex) = remember(snapshots) {
        val flags = BooleanArray(snapshots.size) { index ->
            snapshots[index].blockText.isBlank()
        }
        var remaining = MARKDOWN_LAZY_INITIAL_RENDER_TAIL_BLOCKS
        var startIndex = snapshots.size
        for (index in snapshots.indices.reversed()) {
            if (!flags[index]) {
                startIndex = index
                remaining--
                if (remaining == 0) break
            }
        }
        flags to startIndex
    }
    // 无键调用方（如思维链正文）沿用组合内局部表：不带任何 remember 键，
    // 流式内容变化时保留已测高度，与旧行为一致
    val localHeightsPx = remember { mutableStateMapOf<String, Int>() }
    // 有稳定键（聊天消息）时使用共享缓存，让实测高度跨组合销毁复用。
    // 缓存键按"消息 ID + 渲染块序号"生成，同一时刻仅有一处组合持有，组合期内的失效清理是单写者操作。
    val sharedEntry = if (heightsCacheKey == null) {
        null
    } else {
        remember(heightsCacheKey, content, layoutFingerprint) {
            val newMeta = LazyBlockHeightsMeta(
                contentLength = content.length,
                contentHash = content.hashCode(),
                layoutFingerprint = layoutFingerprint,
                blockCount = snapshots.size,
            )
            val cached = lazyBlockHeightsCache.get(heightsCacheKey)
            val entry = cached ?: LazyBlockHeightsEntry(newMeta)
            if (cached != null) {
                val oldMeta = entry.meta.value
                // 新内容以旧内容为前缀（流式增长/续写）时高度大体有效；否则视为编辑/换分支，整表重测
                val contentCompatible = content.length >= oldMeta.contentLength &&
                    content.substring(0, oldMeta.contentLength).hashCode() == oldMeta.contentHash
                when {
                    oldMeta.layoutFingerprint != layoutFingerprint || !contentCompatible -> {
                        entry.heights.clear()
                    }

                    content.length > oldMeta.contentLength -> {
                        // 前缀增长时旧内容边界处的块可能已继续变长，旧实测值"自信但偏小"，
                        // 丢弃旧末块附近的条目让它们退回估算并重测
                        val staleStartIndex = (oldMeta.blockCount - 3).coerceAtLeast(0)
                        entry.heights.keys
                            .filter { key ->
                                key.substringBefore(':').toIntOrNull()?.let { it >= staleStartIndex } == true
                            }
                            .forEach { key -> entry.heights.remove(key) }
                    }
                }
                entry.meta.value = newMeta
            }
            entry
        }
    }
    if (heightsCacheKey != null && sharedEntry != null) {
        // 入缓存放在 SideEffect：组合成功应用后才执行。若在组合期 put，被放弃的组合会把
        // "从未应用的快照中创建的状态对象"留在进程级缓存里，后续读取会抛快照一致性异常
        SideEffect {
            if (lazyBlockHeightsCache.get(heightsCacheKey) !== sharedEntry) {
                lazyBlockHeightsCache.put(heightsCacheKey, sharedEntry)
            }
        }
    }
    val persistentHeightsPx = sharedEntry?.heights
    val measuredHeightsPx = persistentHeightsPx ?: localHeightsPx

    snapshots.forEachIndexed { index, snapshot ->
        val nodeKey = "$index:${snapshot.node.type}"
        key(nodeKey) {
            val hasDynamicHeight = remember(snapshot) { snapshot.node.hasDynamicMarkdownHeight() }
            // 动态块跨列表只保存首次默认高度；展开、图片加载等后续变化只更新当前组合。
            // 这样重新进入时既有准确占位，也不会把上一次的临时交互状态误当成默认高度。
            val nodeHeightsPx = if (hasDynamicHeight) localHeightsPx else measuredHeightsPx
            val initialNodeHeightsPx = if (hasDynamicHeight) persistentHeightsPx else null
            if (blankNodeFlags[index]) {
                MarkdownNode(
                    node = snapshot.node,
                    content = snapshot.blockText,
                    onClickCitation = onClickCitation,
                    exportAssets = null,
                )
            } else {
                LazyMarkdownNode(
                    snapshot = snapshot,
                    nodeKey = nodeKey,
                    measuredHeightsPx = nodeHeightsPx,
                    initialMeasuredHeightsPx = initialNodeHeightsPx,
                    renderInitially = index < MARKDOWN_LAZY_INITIAL_RENDER_BLOCKS ||
                        index >= tailRenderStartIndex,
                    keepRendered = keepRenderedBlocks,
                    onClickCitation = onClickCitation,
                )
            }
        }
    }
}

private fun ASTNode.hasDynamicMarkdownHeight(): Boolean =
    findChildOfTypeRecursive(
        MarkdownElementTypes.CODE_BLOCK,
        MarkdownElementTypes.CODE_FENCE,
        MarkdownElementTypes.IMAGE,
        MarkdownElementTypes.HTML_BLOCK,
        GFMElementTypes.BLOCK_MATH,
    ) != null

@Composable
private fun LazyMarkdownNode(
    snapshot: MarkdownBlockSnapshot,
    nodeKey: String,
    measuredHeightsPx: MutableMap<String, Int>,
    initialMeasuredHeightsPx: MutableMap<String, Int>? = null,
    renderInitially: Boolean,
    keepRendered: Boolean,
    onClickCitation: (String) -> Unit,
) {
    val density = LocalDensity.current
    val view = LocalView.current
    val textStyle = LocalTextStyle.current
    val lineHeightPx = with(density) {
        when {
            textStyle.lineHeight != TextUnit.Unspecified -> textStyle.lineHeight.toPx()
            textStyle.fontSize != TextUnit.Unspecified -> textStyle.fontSize.toPx() * 1.35f
            else -> 18.sp.toPx()
        }
    }
    val estimatedHeightPx = remember(snapshot, lineHeightPx, density) {
        estimateMarkdownNodeHeightPx(
            node = snapshot.node,
            content = snapshot.blockText,
            lineHeightPx = lineHeightPx,
            extraPaddingPx = with(density) { 8.dp.toPx() },
        )
    }
    var isNearViewport by remember(nodeKey) { mutableStateOf(renderInitially) }
    val cachedHeightPx = measuredHeightsPx[nodeKey] ?: initialMeasuredHeightsPx?.get(nodeKey)
    val placeholderHeightPx = cachedHeightPx ?: estimatedHeightPx
    val shouldRender = isNearViewport

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (shouldRender) {
                    Modifier
                } else {
                    Modifier.height(with(density) { placeholderHeightPx.toDp() })
                }
            )
            .onGloballyPositioned { coordinates ->
                val viewportHeightPx = view.height
                    .takeIf { it > 0 }
                    ?.toFloat()
                    ?: with(density) { 800.dp.toPx() }
                val viewportPaddingPx = with(density) { MarkdownLazyViewportPadding.toPx() }
                val bounds = coordinates.boundsInWindow()
                val nearViewport = bounds.bottom >= -viewportPaddingPx &&
                    bounds.top <= viewportHeightPx + viewportPaddingPx
                // keepRendered（完成态消息）下渲染过的块不再退回占位：块内 Text 不会随滚动
                // 反复注册/注销，规避 SelectionContainer 选择态下的并发修改风险，
                // 也避免占位与实测的高度往返；流式路径仍允许退回，限制每 tick 的重组范围
                if (nearViewport) {
                    if (!isNearViewport) isNearViewport = true
                } else if (!keepRendered && isNearViewport) {
                    isNearViewport = false
                }
            }
    ) {
        if (shouldRender) {
            Box(
                modifier = Modifier.onGloballyPositioned { coordinates ->
                    val height = coordinates.size.height
                    if (height > 0 && measuredHeightsPx[nodeKey] != height) {
                        measuredHeightsPx[nodeKey] = height
                    }
                    if (
                        height > 0 &&
                        initialMeasuredHeightsPx != null &&
                        nodeKey !in initialMeasuredHeightsPx
                    ) {
                        initialMeasuredHeightsPx[nodeKey] = height
                    }
                }
            ) {
                MarkdownNode(
                    node = snapshot.node,
                    content = snapshot.blockText,
                    onClickCitation = onClickCitation,
                    exportAssets = null,
                )
            }
        }
    }
}

private fun estimateMarkdownNodeHeightPx(
    node: ASTNode,
    content: String,
    lineHeightPx: Float,
    extraPaddingPx: Float,
): Int {
    val text = node.getTextInNode(content)
    val explicitLines = text.count { it == '\n' } + 1
    val wrappedLines = (text.length / MARKDOWN_ESTIMATED_CHARS_PER_LINE) + 1
    val lineCount = max(explicitLines, wrappedLines)
    val typeMultiplier = when (node.type) {
        MarkdownElementTypes.ATX_1,
        MarkdownElementTypes.ATX_2,
        MarkdownElementTypes.ATX_3,
        MarkdownElementTypes.ATX_4,
        MarkdownElementTypes.ATX_5,
        MarkdownElementTypes.ATX_6,
            -> 1.35f

        GFMElementTypes.TABLE,
        MarkdownElementTypes.CODE_FENCE,
            -> 1.15f

        else -> 1f
    }
    return (lineCount * lineHeightPx * typeMultiplier + extraPaddingPx)
        .roundToInt()
        .coerceAtLeast((lineHeightPx + extraPaddingPx).roundToInt())
}

// for debug
private fun dumpAst(node: ASTNode, text: String, indent: String = "") {
    println("$indent${node.type} ${if (node.children.isEmpty()) node.getTextInNode(text) else ""} | ${node.javaClass.simpleName}")
    node.children.fastForEach {
        dumpAst(it, text, "$indent  ")
    }
}

object HeaderStyle {
    val H1 = TextStyle(
        fontStyle = FontStyle.Normal, fontWeight = FontWeight.Bold, fontSize = 24.sp
    )

    val H2 = TextStyle(
        fontStyle = FontStyle.Normal, fontWeight = FontWeight.Bold, fontSize = 20.sp
    )

    val H3 = TextStyle(
        fontStyle = FontStyle.Normal, fontWeight = FontWeight.Bold, fontSize = 18.sp
    )

    val H4 = TextStyle(
        fontStyle = FontStyle.Normal, fontWeight = FontWeight.Bold, fontSize = 16.sp
    )

    val H5 = TextStyle(
        fontStyle = FontStyle.Normal, fontWeight = FontWeight.Bold, fontSize = 14.sp
    )

    val H6 = TextStyle(
        fontStyle = FontStyle.Normal, fontWeight = FontWeight.Bold, fontSize = 12.sp
    )
}

@Composable
private fun MarkdownNode(
    node: ASTNode,
    content: String,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
    listLevel: Int = 0,
    exportAssets: MermaidExportAssets? = null,
) {
    when (node.type) {
        // 文件根节点
        MarkdownElementTypes.MARKDOWN_FILE -> {
            node.children.fastForEach { child ->
                MarkdownNode(
                    node = child,
                    content = content,
                    modifier = modifier,
                    onClickCitation = onClickCitation,
                    exportAssets = exportAssets,
                )
            }
        }

        // 段落
        MarkdownElementTypes.PARAGRAPH -> {
            Paragraph(
                node = node,
                content = content,
                modifier = modifier,
                onClickCitation = onClickCitation,
                exportAssets = exportAssets,
            )
        }

        // 标题
        MarkdownElementTypes.ATX_1, MarkdownElementTypes.ATX_2, MarkdownElementTypes.ATX_3, MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6 -> {
            val (baseStyle, pattern) = when (node.type) {
                MarkdownElementTypes.ATX_1 -> HeaderStyle.H1 to "#"
                MarkdownElementTypes.ATX_2 -> HeaderStyle.H2 to "##"
                MarkdownElementTypes.ATX_3 -> HeaderStyle.H3 to "###"
                MarkdownElementTypes.ATX_4 -> HeaderStyle.H4 to "####"
                MarkdownElementTypes.ATX_5 -> HeaderStyle.H5 to "#####"
                MarkdownElementTypes.ATX_6 -> HeaderStyle.H6 to "######"
                else -> throw IllegalArgumentException("Unknown header type")
            }
            // Get RP color for this heading level
            val rpColor = getRpColor(pattern)
            val style = if (rpColor != null) baseStyle.copy(color = rpColor) else baseStyle
            ProvideTextStyle(value = style) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    node.children.fastForEach { node ->
                        if (node.type == MarkdownTokenTypes.ATX_CONTENT) {
                            Paragraph(
                                node = node,
                                content = content,
                                onClickCitation = onClickCitation,
                                modifier = modifier.padding(vertical = 16.dp),
                                trim = true,
                                exportAssets = exportAssets,
                            )
                        }
                    }
                }
            }
        }

        // 列表
        MarkdownElementTypes.UNORDERED_LIST -> {
            UnorderedListNode(
                node = node,
                content = content,
                modifier = modifier.padding(vertical = 4.dp),
                onClickCitation = onClickCitation,
                level = listLevel,
                exportAssets = exportAssets,
            )
        }

        MarkdownElementTypes.ORDERED_LIST -> {
            OrderedListNode(
                node = node,
                content = content,
                modifier = modifier.padding(vertical = 4.dp),
                onClickCitation = onClickCitation,
                level = listLevel,
                exportAssets = exportAssets,
            )
        }

        // Checkbox
        GFMTokenTypes.CHECK_BOX -> {
            val isChecked = node.getTextInNode(content).trim() == "[x]"
            Surface(
                shape = RoundedCornerShape(2.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                modifier = modifier,
            ) {
                Box(
                    modifier = Modifier
                        .padding(2.dp)
                        .size(LocalTextStyle.current.fontSize.toDp() * 0.8f),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isChecked) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // 引用块
        MarkdownElementTypes.BLOCK_QUOTE -> {
            // Get RP color for blockquotes
            val rpColor = getRpColor(">")
            val textStyle = LocalTextStyle.current.copy(
                fontStyle = FontStyle.Italic,
                color = rpColor ?: Color.Unspecified
            )
            ProvideTextStyle(textStyle) {
                val borderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                val bgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                Column(
                    modifier = Modifier
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                color = bgColor, size = size
                            )
                            drawRect(
                                color = borderColor, size = Size(10f, size.height)
                            )
                        }
                        .padding(8.dp)) {
                    node.children.fastForEach { child ->
                        MarkdownNode(
                            node = child,
                            content = content,
                            onClickCitation = onClickCitation,
                            exportAssets = exportAssets,
                        )
                    }
                }
            }
        }

        // 链接
        MarkdownElementTypes.INLINE_LINK -> {
            val linkText = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)
                ?.findChildOfTypeRecursive(GFMTokenTypes.GFM_AUTOLINK, MarkdownTokenTypes.TEXT)?.getTextInNode(content)
                ?: ""
            val linkDest =
                node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content) ?: ""
            val context = LocalContext.current
            Text(
                text = linkText,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
                modifier = modifier.clickable {
                    val intent = Intent(Intent.ACTION_VIEW, linkDest.toUri())
                    context.startActivity(intent)
                })
        }

        // 加粗和斜体
        MarkdownElementTypes.EMPH -> {
            ProvideTextStyle(TextStyle(fontStyle = FontStyle.Italic)) {
                node.children.fastForEach { child ->
                    MarkdownNode(
                        node = child,
                        content = content,
                        modifier = modifier,
                        onClickCitation = onClickCitation,
                        exportAssets = exportAssets,
                    )
                }
            }
        }

        MarkdownElementTypes.STRONG -> {
            ProvideTextStyle(TextStyle(fontWeight = FontWeight.SemiBold)) {
                node.children.fastForEach { child ->
                    MarkdownNode(
                        node = child,
                        content = content,
                        modifier = modifier,
                        onClickCitation = onClickCitation,
                        exportAssets = exportAssets,
                    )
                }
            }
        }

        // GFM 特殊元素
        GFMElementTypes.STRIKETHROUGH -> {
            Text(
                text = node.getTextInNode(content), textDecoration = TextDecoration.LineThrough, modifier = modifier
            )
        }

        GFMElementTypes.TABLE -> {
            TableNode(node = node, content = content, modifier = modifier)
        }

        MarkdownTokenTypes.HORIZONTAL_RULE -> {
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
        }

        // 图片
        MarkdownElementTypes.IMAGE -> {
            val altText = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)?.getTextInNode(content) ?: ""
            val imageUrl =
                node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content) ?: ""
            Column(
                modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PermissionGatedMarkdownImage(
                    imageUrl = imageUrl,
                    altText = altText,
                    modifier = Modifier
                        .clip(AppShapes.CardMedium)
                        .widthIn(min = 120.dp)
                        .heightIn(min = 120.dp),
                )
            }
        }

        GFMElementTypes.INLINE_MATH -> {
            val formula = node.getTextInNode(content)
            MathInline(
                formula, modifier = modifier.padding(horizontal = 1.dp)
            )
        }

        GFMElementTypes.BLOCK_MATH -> {
            val formula = node.getTextInNode(content)
            MathBlock(
                formula, modifier = modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            )
        }

        MarkdownElementTypes.CODE_SPAN -> {
            val code = node.getTextInNode(content).trim('`')
            Text(
                text = code, fontFamily = FontFamily.Monospace, modifier = modifier
            )
        }

        MarkdownElementTypes.CODE_BLOCK -> {
            val code = node.getTextInNode(content)
            HighlightCodeBlock(
                code = code,
                language = "plaintext",
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .fillMaxWidth(),
                completeCodeBlock = true
            )
        }

        // 代码块
        MarkdownElementTypes.CODE_FENCE -> {
            // 这里不能直接取CODE_FENCE_CONTENT的内容，因为首行indent没有包含在内
            // 因此，需要往上找到最后一个EOL元素，用它来作为代码块的起始offset
            val contentStartIndex = node.children.indexOfFirst { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }
            if (contentStartIndex == -1) return
            val eolElement =
                node.children.subList(0, contentStartIndex).findLast { it.type == MarkdownTokenTypes.EOL } ?: return
            val codeContentStartOffset = eolElement.endOffset
            val codeContentEndOffset =
                node.children.findLast { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT }?.endOffset ?: return
            val code = content.substring(
                codeContentStartOffset, codeContentEndOffset
            ).trimIndent()

            val language =
                node.findChildOfTypeRecursive(MarkdownTokenTypes.FENCE_LANG)?.getTextInNode(content) ?: "plaintext"
            val hasEnd = node.findChildOfTypeRecursive(MarkdownTokenTypes.CODE_FENCE_END) != null

            // Mermaid diagrams: render directly without HighlightCodeBlock wrapper
            if (hasEnd && language == "mermaid") {
                val mermaidImage = exportAssets?.images?.get(mermaidExportKey(code))
                if (mermaidImage != null) {
                    ComposeImage(
                        bitmap = mermaidImage.asImageBitmap(),
                        contentDescription = stringResource(R.string.mermaid_diagram),
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .fillMaxWidth()
                            .clip(AppShapes.CardLarge),
                        contentScale = ContentScale.Fit,
                    )
                } else if (exportAssets != null) {
                    HighlightCodeBlock(
                        code = code,
                        language = language,
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .fillMaxWidth(),
                        completeCodeBlock = hasEnd
                    )
                } else {
                    Mermaid(
                        code = code,
                        modifier = Modifier
                            .padding(bottom = 4.dp)
                            .fillMaxWidth(),
                    )
                }
            } else {
                HighlightCodeBlock(
                    code = code,
                    language = language,
                    modifier = Modifier
                        .padding(bottom = 4.dp)
                        .fillMaxWidth(),
                    completeCodeBlock = hasEnd
                )
            }
        }

        MarkdownTokenTypes.TEXT -> {
            val text = node.getTextInNode(content)
            Text(
                text = text,
                modifier = modifier,
            )
        }

        MarkdownElementTypes.HTML_BLOCK -> {
            val text = node.getTextInNode(content)
            SimpleHtmlBlock(
                html = text, modifier = modifier
            )
        }

        // 其他类型的节点，递归处理子节点
        else -> {
            // 递归处理其他节点的子节点
            node.children.fastForEach { child ->
                MarkdownNode(
                    node = child,
                    content = content,
                    modifier = modifier,
                    onClickCitation = onClickCitation,
                    exportAssets = exportAssets,
                )
            }
        }
    }
}

@Composable
private fun PermissionGatedMarkdownImage(
    imageUrl: String,
    altText: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appOwnedDirPrefixes = remember(context) { buildAppOwnedDirPrefixes(context) }
    val needsMediaPermission = remember(imageUrl, appOwnedDirPrefixes) {
        LocalFileUrlUtils.needsExternalMediaPermission(imageUrl, appOwnedDirPrefixes)
    }

    if (!needsMediaPermission) {
        ZoomableAsyncImage(
            model = imageUrl,
            contentDescription = altText,
            modifier = modifier,
        )
        return
    }

    val hintText = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        stringResource(R.string.permission_read_media_images_desc)
    } else {
        stringResource(R.string.permission_read_external_storage_desc)
    }

    val activity = context as? ComponentActivity
    if (activity == null) {
        Card(
            modifier = modifier,
            shape = AppShapes.CardMedium,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.permission_diaog_title),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = hintText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        return
    }

    val permissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setOf(PermissionReadMediaImages, PermissionReadMediaVideo)
        } else {
            setOf(PermissionReadExternalStorage)
        }
    }
    val permissionState = rememberPermissionState(permissions = permissions)
    PermissionManager(permissionState = permissionState)

    if (permissionState.allRequiredPermissionsGranted) {
        ZoomableAsyncImage(
            model = imageUrl,
            contentDescription = altText,
            modifier = modifier,
        )
        return
    }

    val requiredPermanentlyDenied = permissions
        .filter { it.required }
        .any { it in permissionState.permanentlyDeniedPermissions }

    val haptics = rememberPremiumHaptics()
    val iconImage = if (requiredPermanentlyDenied) Icons.Rounded.Settings else Icons.Rounded.Image
    val iconTint = if (requiredPermanentlyDenied) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = modifier,
        shape = AppShapes.CardMedium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = iconImage,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(18.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.permission_diaog_title),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = hintText,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            PermissionActionButton(
                text = if (requiredPermanentlyDenied) {
                    stringResource(R.string.permission_go_to_settings)
                } else {
                    stringResource(R.string.permission_grant)
                },
                icon = if (requiredPermanentlyDenied) Icons.Rounded.Settings else Icons.Rounded.Check,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                onClick = {
                    haptics.perform(HapticPattern.Pop)
                    if (requiredPermanentlyDenied) {
                        permissionState.openAppSettings()
                    } else {
                        permissionState.requestPermissions()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PermissionActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "permission_action_button_scale",
    )

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        color = containerColor,
        contentColor = contentColor,
        shape = AppShapes.ButtonPill,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp, horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun buildAppOwnedDirPrefixes(context: Context): List<String> {
    return listOfNotNull(
        context.dataDir.absolutePath,
        context.filesDir.absolutePath,
        context.cacheDir.absolutePath,
        context.getExternalFilesDir(null)?.absolutePath,
        context.externalCacheDir?.absolutePath,
    )
}

@Composable
private fun UnorderedListNode(
    node: ASTNode,
    content: String,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
    level: Int = 0,
    exportAssets: MermaidExportAssets? = null,
) {
    val bulletStyle = when (level % 3) {
        0 -> "• "
        1 -> "◦ "
        else -> "▪ "
    }

    Column(
        modifier = modifier.padding(start = (level * 8).dp)
    ) {
        node.children.fastForEach { child ->
            if (child.type == MarkdownElementTypes.LIST_ITEM) {
                ListItemNode(
                    node = child,
                    content = content,
                    bulletText = bulletStyle,
                    onClickCitation = onClickCitation,
                    level = level,
                    exportAssets = exportAssets,
                )
            }
        }
    }
}

@Composable
private fun OrderedListNode(
    node: ASTNode,
    content: String,
    modifier: Modifier = Modifier,
    onClickCitation: (String) -> Unit = {},
    level: Int = 0,
    exportAssets: MermaidExportAssets? = null,
) {
    Column(modifier.padding(start = (level * 8).dp)) {
        var index = 1
        node.children.fastForEach { child ->
            if (child.type == MarkdownElementTypes.LIST_ITEM) {
                val numberText =
                    child.findChildOfTypeRecursive(MarkdownTokenTypes.LIST_NUMBER)?.getTextInNode(content) ?: "$index. "
                ListItemNode(
                    node = child,
                    content = content,
                    bulletText = numberText,
                    onClickCitation = onClickCitation,
                    level = level,
                    exportAssets = exportAssets,
                )
                index++
            }
        }
    }
}

@Composable
private fun ListItemNode(
    node: ASTNode,
    content: String,
    bulletText: String,
    onClickCitation: (String) -> Unit = {},
    level: Int,
    exportAssets: MermaidExportAssets? = null,
) {
    Column {
        // 分离列表项的直接内容和嵌套列表
        val (directContent, nestedLists) = separateContentAndLists(node)
        // directContent 渲染处理
        if (directContent.isNotEmpty()) {
            Row {
                Text(
                    text = bulletText, modifier = Modifier.alignByBaseline()
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    directContent.fastForEach { contentChild ->
                        MarkdownNode(
                            node = contentChild,
                            content = content,
                            onClickCitation = onClickCitation,
                            listLevel = level,
                            exportAssets = exportAssets,
                        )
                    }
                }
            }
        }
        // nestedLists 渲染处理
        nestedLists.fastForEach { nestedList ->
            MarkdownNode(
                node = nestedList,
                content = content,
                onClickCitation = onClickCitation,
                listLevel = level + 1, // 增加层级
                exportAssets = exportAssets,
            )
        }
    }
}

// 分离列表项的直接内容和嵌套列表
private fun separateContentAndLists(listItemNode: ASTNode): Pair<List<ASTNode>, List<ASTNode>> {
    val directContent = mutableListOf<ASTNode>()
    val nestedLists = mutableListOf<ASTNode>()
    listItemNode.children.fastForEach { child ->
        when (child.type) {
            MarkdownElementTypes.UNORDERED_LIST, MarkdownElementTypes.ORDERED_LIST -> {
                nestedLists.add(child)
            }

            else -> {
                directContent.add(child)
            }
        }
    }
    return directContent to nestedLists
}

@Composable
private fun Paragraph(
    node: ASTNode,
    content: String,
    trim: Boolean = false,
    onClickCitation: (String) -> Unit = {},
    modifier: Modifier,
    exportAssets: MermaidExportAssets? = null,
) {
    // dumpAst(node, content)
    if (node.findChildOfTypeRecursive(MarkdownElementTypes.IMAGE, GFMElementTypes.BLOCK_MATH) != null) {
        FlowRow(modifier = modifier) {
            node.children.fastForEach { child ->
                MarkdownNode(
                    node = child,
                    content = content,
                    onClickCitation = onClickCitation,
                    exportAssets = exportAssets,
                )
            }
        }
        return
    }

    val colorScheme = MaterialTheme.colorScheme
    val inlineContents = remember {
        mutableStateMapOf<String, InlineTextContent>()
    }
    val hasInlineMath = remember(node) {
        node.findChildOfTypeRecursive(GFMElementTypes.INLINE_MATH) != null
    }

    val textStyle = LocalTextStyle.current
    val density = LocalDensity.current
    val rpStyleRules = LocalSettings.current.displaySetting.rpStyleRules
    FlowRow(
        modifier = modifier.then(
            if (node.nextSibling() != null) Modifier.padding(bottom = 4.dp)
            else Modifier
        )
    ) {
        // 以段落自身文本为 key：流式追加时只有正在变化的段落重建，其余段落直接复用缓存结果
        val paragraphText = node.getTextInNode(content)
        val annotatedString = remember(paragraphText, rpStyleRules) {
            buildAnnotatedString {
                appendInlineChildrenWithFallback(
                    nodes = node.children,
                    content = content,
                    trim = trim,
                    inlineContents = inlineContents,
                    colorScheme = colorScheme,
                    density = density,
                    style = textStyle,
                    onClickCitation = onClickCitation,
                    rpStyleRules = rpStyleRules,
                )
            }
        }
        Text(
            text = annotatedString,
            modifier = Modifier,
            inlineContent = inlineContents,
            softWrap = true,
            overflow = TextOverflow.Visible,
            style = LocalTextStyle.current.copy(
                lineHeight = if (hasInlineMath) TextUnit.Unspecified else LocalTextStyle.current.lineHeight
            )
        )
    }
}

@Composable
private fun TableNode(node: ASTNode, content: String, modifier: Modifier = Modifier) {
    // 提取表格的标题行和数据行
    val headerNode = node.children.find { it.type == GFMElementTypes.HEADER }
    val rowNodes = node.children.filter { it.type == GFMElementTypes.ROW }

    // 计算列数（从标题行获取）
    val columnCount = headerNode?.children?.count { it.type == GFMTokenTypes.CELL } ?: 0

    // 检查是否有足够的列来显示表格
    if (columnCount == 0) return

    // 提取表头单元格文本
    val headerCells =
        headerNode?.children?.filter { it.type == GFMTokenTypes.CELL }?.map { it.getTextInNode(content).trim() }
            ?: emptyList()

    // 提取所有行的数据
    val rows = rowNodes.map { rowNode ->
        rowNode.children.filter { it.type == GFMTokenTypes.CELL }.map { it.getTextInNode(content).trim() }
    }

    // 创建表头composable列表
    val headers = List(columnCount) { columnIndex ->
        @Composable {
            MarkdownBlock(
                content = if (columnIndex < headerCells.size) headerCells[columnIndex] else "",
            )
        }
    }

    // 创建行数据composable列表
    val rowComposables = rows.map { rowData ->
        List(columnCount) { columnIndex ->
            @Composable {
                MarkdownBlock(
                    content = if (columnIndex < rowData.size) rowData[columnIndex] else "",
                )
            }
        }
    }

    // 渲染表格
    DataTable(
        headers = headers,
        rows = rowComposables,
        modifier = modifier.padding(vertical = 8.dp),
        columnMinWidths = List(columnCount) { 80.dp },
    )
}

private fun AnnotatedString.Builder.appendMarkdownNodeContent(
    node: ASTNode,
    content: String,
    trim: Boolean = false,
    inlineContents: MutableMap<String, InlineTextContent>,
    colorScheme: ColorScheme,
    density: Density,
    style: TextStyle,
    onClickCitation: (String) -> Unit = {},
    rpStyleRules: List<RpStyleRule> = emptyList(),
) {
    when {
        node.type == MarkdownTokenTypes.BLOCK_QUOTE -> {}

        node.type == GFMTokenTypes.GFM_AUTOLINK -> {
            val link = node.getTextInNode(content)
            withLink(LinkAnnotation.Url(link)) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(link)
                }
            }
        }

        node is LeafASTNode -> {
            val text = node.getTextInNode(content).let {
                if (trim) {
                    it.trim()
                } else {
                    it
                }.replace(BREAK_LINE_REGEX, "\n")
            }
            // Use custom pattern scanning for plain text
            appendTextWithCustomPatterns(text, rpStyleRules)
        }

        node.type == MarkdownElementTypes.EMPH -> {
            // Check for RP color rule for pattern "*" (single emphasis)
            val emphRule = rpStyleRules.find { it.pattern == "*" && it.enabled }
            val emphColor = emphRule?.let { runCatching { Color(android.graphics.Color.parseColor(it.colorHex)) }.getOrNull() }
            withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = emphColor ?: Color.Unspecified)) {
                node.children.trim(MarkdownTokenTypes.EMPH, 1).fastForEach {
                    appendMarkdownNodeContent(
                        node = it,
                        content = content,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        density = density,
                        style = style,
                        onClickCitation = onClickCitation,
                        rpStyleRules = rpStyleRules
                    )
                }
            }
        }

        node.type == MarkdownElementTypes.STRONG -> {
            // Check for RP color rule for pattern "**" (strong emphasis)
            val strongRule = rpStyleRules.find { it.pattern == "**" && it.enabled }
            val strongColor = strongRule?.let { runCatching { Color(android.graphics.Color.parseColor(it.colorHex)) }.getOrNull() }
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = strongColor ?: Color.Unspecified)) {
                node.children.trim(MarkdownTokenTypes.EMPH, 2).fastForEach {
                    appendMarkdownNodeContent(
                        node = it,
                        content = content,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        density = density,
                        style = style,
                        onClickCitation = onClickCitation,
                        rpStyleRules = rpStyleRules
                    )
                }
            }
        }

        node.type == GFMElementTypes.STRIKETHROUGH -> {
            // Check for RP color rule for pattern "~~" (strikethrough)
            val strikeRule = rpStyleRules.find { it.pattern == "~~" && it.enabled }
            val strikeColor = strikeRule?.let { runCatching { Color(android.graphics.Color.parseColor(it.colorHex)) }.getOrNull() }
            withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = strikeColor ?: Color.Unspecified)) {
                node.children.trim(GFMTokenTypes.TILDE, 2).fastForEach {
                    appendMarkdownNodeContent(
                        node = it,
                        content = content,
                        inlineContents = inlineContents,
                        colorScheme = colorScheme,
                        density = density,
                        style = style,
                        onClickCitation = onClickCitation,
                        rpStyleRules = rpStyleRules
                    )
                }
            }
        }

        node.type == MarkdownElementTypes.INLINE_LINK -> {
            val linkDest =
                node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_DESTINATION)?.getTextInNode(content) ?: ""
            val linkText = node.findChildOfTypeRecursive(MarkdownElementTypes.LINK_TEXT)?.getTextInNode(content)
                ?.trim { it == '[' || it == ']' } ?: linkDest
            if (linkText.startsWith("citation,")) {
                // 如果是引用，则特殊处理
                val domain = linkText.substringAfter("citation,")
                val id = linkDest
                if (id.length == 6) {
                    inlineContents.putIfAbsent(
                        "citation:$linkDest", InlineTextContent(
                            placeholder = Placeholder(
                                width = (domain.length * 7).sp,
                                height = 1.em,
                                placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                            ), children = {
                                Box(
                                    modifier = Modifier
                                        .clickable {
                                            onClickCitation(id.trim())
                                        }
                                        .fillMaxSize()
                                        .clip(CircleShape)
                                        .background(colorScheme.tertiaryContainer.copy(0.2f)),
                                    contentAlignment = Alignment.Center) {
                                    Text(
                                        text = domain,
                                        modifier = Modifier.wrapContentSize(),
                                        style = TextStyle(
                                            fontSize = 10.sp,
                                            lineHeight = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = colorScheme.onTertiaryContainer,
                                            fontWeight = FontWeight.Thin
                                        ),
                                    )
                                }
                            })
                    )
                    appendInlineContent("citation:$linkDest")
                }
            } else {
                withLink(LinkAnnotation.Url(linkDest)) {
                    withStyle(
                        SpanStyle(
                            color = colorScheme.primary, textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(linkText)
                    }
                }
            }
        }

        node.type == MarkdownElementTypes.AUTOLINK -> {
            val links = node.children.trim(MarkdownTokenTypes.LT, 1).trim(MarkdownTokenTypes.GT, 1)
            links.fastForEach { link ->
                withLink(LinkAnnotation.Url(link.getTextInNode(content))) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(link.getTextInNode(content))
                    }
                }
            }
        }

        node.type == MarkdownElementTypes.CODE_SPAN -> {
            val code = node.getTextInNode(content).trim('`')
            // Check for RP color rule for pattern "`" (inline code)
            val codeRule = rpStyleRules.find { it.pattern == "`" && it.enabled }
            val codeColor = codeRule?.let { runCatching { Color(android.graphics.Color.parseColor(it.colorHex)) }.getOrNull() }
            withStyle(
                SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 0.95.em,
                    background = colorScheme.secondaryContainer.copy(alpha = 0.2f),
                    color = codeColor ?: Color.Unspecified,
                )
            ) {
                append(code)
            }
        }

        node.type == GFMElementTypes.INLINE_MATH -> {
            // formula as id
            val formula = node.getTextInNode(content)
            appendInlineContent(formula, "[Latex]")
            val (width, height) = with(density) {
                assumeLatexSize(
                    latex = formula, fontSize = style.fontSize.toPx()
                ).let {
                    it.width().toSp() to it.height().toSp()
                }
            }
            inlineContents.putIfAbsent(/* key = */ formula,/* value = */ InlineTextContent(
                placeholder = Placeholder(
                    width = width, height = height, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter
                ), children = {
                    MathInline(
                        latex = formula, modifier = Modifier
                    )
                })
            )
        }

        // 其他类型继续递归处理
        else -> {
            node.children.fastForEach {
            appendMarkdownNodeContent(
                    node = it,
                    content = content,
                    inlineContents = inlineContents,
                    colorScheme = colorScheme,
                    density = density,
                    style = style,
                    onClickCitation = onClickCitation,
                    rpStyleRules = rpStyleRules
                )
            }
        }
    }
}

private fun ASTNode.getTextInNode(text: String): String {
    return text.substring(startOffset, endOffset)
}

private fun ASTNode.getTextInNode(text: String, type: IElementType): String {
    var startOffset = -1
    var endOffset = -1
    children.fastForEach {
        if (it.type == type) {
            if (startOffset == -1) {
                startOffset = it.startOffset
            }
            endOffset = it.endOffset
        }
    }
    if (startOffset == -1 || endOffset == -1) {
        return ""
    }
    return text.substring(startOffset, endOffset)
}

private fun ASTNode.nextSibling(): ASTNode? {
    val brother = this.parent?.children ?: return null
    for (i in brother.indices) {
        if (brother[i] == this) {
            if (i + 1 < brother.size) {
                return brother[i + 1]
            }
        }
    }
    return null
}

private fun ASTNode.findChildOfTypeRecursive(vararg types: IElementType): ASTNode? {
    if (this.type in types) return this
    for (child in children) {
        val result = child.findChildOfTypeRecursive(*types)
        if (result != null) return result
    }
    return null
}

private fun ASTNode.traverseChildren(
    action: (ASTNode) -> Unit
) {
    children.fastForEach { child ->
        action(child)
        child.traverseChildren(action)
    }
}

private fun List<ASTNode>.trim(type: IElementType, size: Int): List<ASTNode> {
    if (this.isEmpty() || size <= 0) return this
    var start = 0
    var end = this.size
    // 从头裁剪
    var trimmed = 0
    while (start < end && trimmed < size && this[start].type == type) {
        start++
        trimmed++
    }
    // 从尾裁剪
    trimmed = 0
    while (end > start && trimmed < size && this[end - 1].type == type) {
        end--
        trimmed++
    }
    return this.subList(start, end)
}
