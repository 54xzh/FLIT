package me.rerere.rikkahub.ui.pages.logs

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonElement
import me.rerere.highlight.HighlightText
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.AIRequestSource
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.ui.theme.AtomOneDarkPalette
import me.rerere.rikkahub.ui.theme.AtomOneLightPalette
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.JsonInstantPretty
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun SourceTag(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = AppShapes.Tag,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
fun KeyValueCard(
    title: String,
    items: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    emphasize: Boolean = false,
) {
    Card(
        modifier = modifier,
        shape = AppShapes.CardMedium,
        colors = CardDefaults.cardColors(
            containerColor = if (emphasize) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (emphasize) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
            )
            items.forEach { (k, v) ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = k,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (emphasize) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(110.dp),
                    )
                    Text(
                        text = v,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (emphasize) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
fun RequestLogCodeCard(
    title: String,
    code: String,
    modifier: Modifier = Modifier,
    language: String = "json",
) {
    val darkMode = LocalDarkMode.current
    val colorPalette = if (darkMode) AtomOneDarkPalette else AtomOneLightPalette
    val horizontalScrollState = rememberScrollState()
    val settings = LocalSettings.current
    val autoWrap = settings.displaySetting.codeBlockAutoWrap
    val displayCode = remember(code, language) {
        if (language == "json") formatJsonOrRaw(code) else code
    }

    Card(
        modifier = modifier,
        shape = AppShapes.CardMedium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
            )

            SelectionContainer {
                HighlightText(
                    code = displayCode.ifBlank { "-" },
                    language = language,
                    modifier = Modifier.then(
                        if (autoWrap) Modifier else Modifier.horizontalScroll(horizontalScrollState)
                    ),
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight,
                    colors = colorPalette,
                    overflow = TextOverflow.Visible,
                    softWrap = autoWrap,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
fun RequestLogEmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun formatLogTime(createdAt: Long, pattern: String): String {
    return Instant.ofEpochMilli(createdAt)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(pattern))
}

@Composable
fun AIRequestSource.displayName(): String {
    return when (this) {
        AIRequestSource.CHAT -> stringResource(R.string.request_log_source_chat)
        AIRequestSource.TITLE_SUMMARY -> stringResource(R.string.request_log_source_title_summary)
        AIRequestSource.CONTEXT_SUMMARY -> stringResource(R.string.request_log_source_context_summary)
        AIRequestSource.CHAT_SUGGESTION -> stringResource(R.string.request_log_source_chat_suggestion)
        AIRequestSource.GROUP_CHAT_ROUTING -> stringResource(R.string.request_log_source_group_chat_routing)
        AIRequestSource.WELCOME_PHRASES -> stringResource(R.string.request_log_source_welcome_phrases)
        AIRequestSource.MEMORY_CONSOLIDATION -> stringResource(R.string.request_log_source_memory_consolidation)
        AIRequestSource.MEMORY_EMBEDDING -> stringResource(R.string.request_log_source_memory_embedding)
        AIRequestSource.MEMORY_RETRIEVAL -> stringResource(R.string.request_log_source_memory_retrieval)
        AIRequestSource.TOOL_RESULT_EMBEDDING -> stringResource(R.string.request_log_source_tool_result_embedding)
        AIRequestSource.TOOL_RESULT_RAG -> stringResource(R.string.request_log_source_tool_result_rag)
        AIRequestSource.TRANSLATION -> stringResource(R.string.request_log_source_translation)
        AIRequestSource.OCR -> stringResource(R.string.request_log_source_ocr)
        AIRequestSource.DOCUMENT_SUMMARY -> stringResource(R.string.request_log_source_document_summary)
        AIRequestSource.SCHEDULED_MESSAGE -> stringResource(R.string.request_log_source_scheduled_message)
        AIRequestSource.SPONTANEOUS -> stringResource(R.string.request_log_source_spontaneous)
        AIRequestSource.MODEL_NAME_GENERATION -> stringResource(R.string.request_log_source_model_name_generation)
        AIRequestSource.SEARCH_AGENT -> stringResource(R.string.request_log_source_search_agent)
        AIRequestSource.OTHER -> stringResource(R.string.request_log_source_other)
    }
}

@Composable
fun resolveSourceLabel(raw: String): String {
    val source = runCatching { AIRequestSource.valueOf(raw) }.getOrNull()
    return source?.displayName() ?: raw
}

private fun formatJsonOrRaw(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return ""
    return runCatching {
        val element: JsonElement = JsonInstant.parseToJsonElement(trimmed)
        JsonInstantPretty.encodeToString(JsonElement.serializer(), element)
    }.getOrElse { raw }
}
