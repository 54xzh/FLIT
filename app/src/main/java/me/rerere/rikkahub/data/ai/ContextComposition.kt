package me.rerere.rikkahub.data.ai

import me.rerere.ai.ui.ContextCompositionKind
import me.rerere.ai.ui.ContextCompositionSlice
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isStandaloneInterruptedAppContextMarker
import me.rerere.ai.ui.stripInterruptedAppContextForDisplay
import me.rerere.rikkahub.service.welcomeAppContextPrefix

internal class ContextCompositionAccumulator {
    private val totals = LinkedHashMap<ContextCompositionKind, Int>()

    fun add(kind: ContextCompositionKind, text: String) {
        if (text.isEmpty()) return
        val tokens = ContextTokenEstimator.estimate(text)
        if (tokens <= 0) return
        totals[kind] = (totals[kind] ?: 0) + tokens
    }

    fun addHistoryMessage(message: UIMessage) {
        if (message.isStandaloneInterruptedAppContextMarker()) {
            add(ContextCompositionKind.OTHER, message.toContentText())
            return
        }
        message.parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> {
                    val (conversation, marker) = partitionHistoryText(part.text)
                    add(ContextCompositionKind.CONVERSATION, conversation)
                    add(ContextCompositionKind.OTHER, marker)
                }
                is UIMessagePart.QuotedFollowUp -> add(ContextCompositionKind.CONVERSATION, part.text)
                is UIMessagePart.ToolCall -> add(
                    ContextCompositionKind.CONVERSATION,
                    part.toolName + "\n" + part.arguments,
                )
                is UIMessagePart.ToolResult -> add(
                    ContextCompositionKind.CONVERSATION,
                    part.toolName + "\n" + part.content.toString(),
                )
                else -> Unit
            }
        }
    }

    fun slices(): List<ContextCompositionSlice>? {
        val result = ContextCompositionKind.entries.mapNotNull { kind ->
            val tokens = totals[kind] ?: return@mapNotNull null
            if (tokens <= 0) null else ContextCompositionSlice(kind, tokens)
        }
        return result.ifEmpty { null }
    }
}

internal fun partitionHistoryText(text: String): Pair<String, String> {
    val conversation = text.stripInterruptedAppContextForDisplay()
    if (conversation == text) return text to ""
    if (conversation.isEmpty()) return "" to text
    val start = text.indexOf(conversation)
    if (start < 0) return conversation to ""
    val marker = text.removeRange(start, start + conversation.length)
    return conversation to marker
}

internal fun List<ContextCompositionSlice>?.includingWelcomePrefixes(
    messages: List<UIMessage>,
): List<ContextCompositionSlice>? {
    var welcomeTokens = 0
    messages.forEach { message ->
        message.parts.forEach { part ->
            if (part is UIMessagePart.Text) {
                val prefix = welcomeAppContextPrefix(part.text) ?: return@forEach
                welcomeTokens += ContextTokenEstimator.estimate(prefix)
            }
        }
    }
    if (welcomeTokens <= 0) return this
    val merged = this.orEmpty().associate { it.kind to it.estimatedTokens }.toMutableMap()
    merged[ContextCompositionKind.OTHER] =
        (merged[ContextCompositionKind.OTHER] ?: 0) + welcomeTokens
    return ContextCompositionKind.entries.mapNotNull { kind ->
        val tokens = merged[kind] ?: return@mapNotNull null
        if (tokens <= 0) null else ContextCompositionSlice(kind, tokens)
    }.ifEmpty { null }
}

internal fun scaleCompositionTokens(weights: List<Int>, totalInput: Int?): List<Int> {
    if (weights.isEmpty()) return emptyList()
    val weightSum = weights.sum().toLong()
    if (weightSum <= 0L) return List(weights.size) { 0 }
    val total = totalInput?.takeIf { it > 0 }?.toLong() ?: weightSum
    val floors = IntArray(weights.size)
    val fractions = ArrayList<Pair<Int, Double>>(weights.size)
    var assigned = 0L
    weights.forEachIndexed { index, weight ->
        val exact = weight.toDouble() * total.toDouble() / weightSum.toDouble()
        val floor = exact.toLong()
        floors[index] = floor.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        assigned += floor
        fractions += index to (exact - floor)
    }
    var left = (total - assigned).coerceAtLeast(0L)
    fractions.sortedByDescending { it.second }.forEach { (index, _) ->
        if (left <= 0L) return@forEach
        floors[index] += 1
        left -= 1L
    }
    return floors.toList()
}

internal fun compositionSegmentCenterFraction(weights: List<Int>, index: Int): Float {
    val total = weights.sum().coerceAtLeast(1)
    val start = weights.take(index.coerceAtLeast(0)).sum()
    val size = weights.getOrElse(index) { 0 }.coerceAtLeast(0)
    return (start + size / 2f) / total.toFloat()
}

internal fun compositionSegmentIndexAt(x: Float, width: Float, weights: List<Int>): Int {
    if (width <= 0f || weights.isEmpty()) return -1
    val total = weights.sum().toFloat().coerceAtLeast(1f)
    var cursor = 0f
    weights.forEachIndexed { index, weight ->
        cursor += width * (weight.coerceAtLeast(0) / total)
        if (x < cursor || index == weights.lastIndex) return index
    }
    return weights.lastIndex
}

internal fun formatCompositionTokenMagnitude(count: Int): String {
    val safe = count.coerceAtLeast(0)
    if (safe < 1_000) return safe.toString()
    val rounded = kotlin.math.round(safe / 100.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        "${rounded.toLong()}K"
    } else {
        String.format(java.util.Locale.US, "%.1fK", rounded)
    }
}

internal fun compositionPercentages(tokens: List<Int>): List<Int> {
    if (tokens.isEmpty()) return emptyList()
    val total = tokens.sum()
    if (total <= 0) return List(tokens.size) { 0 }
    val floors = IntArray(tokens.size)
    val fractions = ArrayList<Pair<Int, Double>>(tokens.size)
    var assigned = 0
    tokens.forEachIndexed { index, weight ->
        val exact = weight * 100.0 / total
        val floor = exact.toInt()
        floors[index] = floor
        assigned += floor
        fractions += index to (exact - floor)
    }
    var left = 100 - assigned
    fractions.sortedByDescending { it.second }.forEach { (index, _) ->
        if (left <= 0) return@forEach
        floors[index] += 1
        left -= 1
    }
    return floors.toList()
}
