package me.rerere.rikkahub.ui.components.richtext

import android.graphics.Rect
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import ru.noties.jlatexmath.JLatexMathDrawable

// 尺寸完全由「公式文本 + 字号」决定；缓存测量结果，避免流式输出时每个 token 都在主线程完整排版一次公式。
// 只缓存成功的测量——瞬时失败（如内存紧张）不能被永久记住，否则该公式会一直塌缩成 0×0。
// Rect 可变，读写都用副本，防止调用方或后续写入污染缓存。
private val latexSizeCache = LruCache<String, Rect>(256)

fun assumeLatexSize(latex: String, fontSize: Float): Rect {
    // 先归一化再作为缓存 key：$x$、\(x\) 等等价写法归一后相同，共用一条缓存
    val processedLatex = processLatex(latex)
    val cacheKey = "$fontSize:$processedLatex"
    latexSizeCache.get(cacheKey)?.let { return Rect(it) }
    return runCatching {
        val size = JLatexMathDrawable.builder(processedLatex)
            .textSize(fontSize)
            .padding(0)
            .build()
            .bounds
        latexSizeCache.put(cacheKey, Rect(size))
        Rect(size)
    }.getOrElse { Rect(0, 0, 0, 0) }
}

@Composable
fun LatexText(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current
) {
    val style = style.merge(
        fontSize = fontSize,
        color = color
    )
    val density = LocalDensity.current

    val drawable = remember(latex, fontSize, style) {
        runCatching {
            with(density) {
                getLatexDrawable(
                    latex = processLatex(latex),
                    fontSize = fontSize.toPx(),
                    color = style.color.toArgb(),
                    background = style.background.toArgb()
                )
            }
        }.onFailure {
            it.printStackTrace()
        }.getOrNull()
    }

    if (drawable != null) {
        with(density) {
            Canvas(
                modifier = modifier
                    .size(
                        width = drawable.bounds.width().toDp(),
                        height = drawable.bounds.height().toDp()
                    )
            ) {
                drawable.draw(drawContext.canvas.nativeCanvas)
            }
        }
    } else {
        Text(
            text = latex,
            style = style,
            modifier = modifier
        )
    }
}

fun getLatexDrawable(
    latex: String,
    fontSize: Float,
    color: Int,
    background: Int
): JLatexMathDrawable? {
    return runCatching {
        JLatexMathDrawable.builder(processLatex(latex))
            .textSize(fontSize)
            .color(color)
            .background(background)
            .padding(0)
            .align(JLatexMathDrawable.ALIGN_LEFT)
            .build()
    }.onFailure {
        it.printStackTrace()
    }.getOrNull()
}

private val inlineDollarRegex = Regex("""^\$(.*?)\$""", RegexOption.DOT_MATCHES_ALL)
private val displayDollarRegex = Regex("""^\$\$(.*?)\$\$""", RegexOption.DOT_MATCHES_ALL)
private val inlineParenRegex = Regex("""^\\\((.*?)\\\)""", RegexOption.DOT_MATCHES_ALL)
private val displayBracketRegex = Regex("""^\\\[(.*?)\\\]""", RegexOption.DOT_MATCHES_ALL)

private fun processLatex(latex: String): String {
    val trimmed = latex.trim()
    return when {
        displayDollarRegex.matches(trimmed) ->
            displayDollarRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        inlineDollarRegex.matches(trimmed) ->
            inlineDollarRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        displayBracketRegex.matches(trimmed) ->
            displayBracketRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        inlineParenRegex.matches(trimmed) ->
            inlineParenRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        else -> trimmed
    }
}
