package me.rerere.rikkahub.ui.pages.logs

import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import me.rerere.common.android.appTempFolder
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.AIRequestSource
import me.rerere.rikkahub.data.db.entity.AIRequestLogEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.utils.JsonInstant
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun RequestLogsOverviewPage(vm: RequestLogsVM = koinViewModel()) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val haptics = rememberPremiumHaptics()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()

    val listState = rememberSaveable(saver = LazyListState.Saver) {
        LazyListState()
    }

    val logs by vm.logs.collectAsStateWithLifecycle()
    val sourceFilter by vm.sourceFilter.collectAsStateWithLifecycle()
    val sources by vm.availableSources.collectAsStateWithLifecycle()
    val errorOnly by vm.errorOnly.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.developer_page_tab_request_logs),
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        onClick = {
                            haptics.perform(HapticPattern.Pop)
                            if (logs.isEmpty()) {
                                toaster.show(message = context.getString(R.string.request_logs_export_empty))
                                return@IconButton
                            }

                            scope.launch {
                                runCatching {
                                    val file = withContext(Dispatchers.IO) {
                                        val exportItems = logs.map { it.toExportItem() }
                                        val json = JsonInstant.encodeToString(
                                            serializer = ListSerializer(RequestLogExportItem.serializer()),
                                            value = exportItems,
                                        )

                                        val timestamp = LocalDateTime.now()
                                            .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                                        val out = File(context.appTempFolder, "request-logs-$timestamp.json")
                                        out.writeText(json, Charsets.UTF_8)
                                        out
                                    }

                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file,
                                    )
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/json"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(
                                        Intent.createChooser(
                                            intent,
                                            context.getString(R.string.chat_page_export_share_via)
                                        )
                                    )
                                }.onSuccess {
                                    haptics.perform(HapticPattern.Success)
                                }.onFailure { e ->
                                    haptics.perform(HapticPattern.Error)
                                    toaster.show(
                                        message = context.getString(
                                            R.string.request_logs_export_failed,
                                            e.message ?: "Unknown error"
                                        )
                                    )
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Rounded.Share, contentDescription = null)
                    }
                    IconButton(
                        onClick = {
                            haptics.perform(HapticPattern.Pop)
                            vm.clearAll()
                        }
                    ) {
                        Icon(Icons.Rounded.DeleteForever, contentDescription = null)
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (sources.isNotEmpty()) {
                item(key = "filters") {
                    SourceFilterRow(
                        selected = sourceFilter,
                        sources = sources,
                        onSelect = { vm.setSourceFilter(it) },
                        errorOnly = errorOnly,
                        onToggleErrorOnly = { vm.toggleErrorOnly() },
                    )
                }
            }

            if (logs.isEmpty()) {
                item(key = "empty") {
                    RequestLogEmptyState(
                        icon = Icons.Rounded.History,
                        title = stringResource(R.string.request_logs_empty),
                        modifier = Modifier.fillParentMaxSize(),
                    )
                }
            } else {
                items(
                    items = logs,
                    key = { it.id }
                ) { log ->
                    RequestLogOverviewItem(
                        log = log,
                        onClick = {
                            navController.navigate(Screen.RequestLogDetail(log.id))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceFilterRow(
    selected: AIRequestSource?,
    sources: List<AIRequestSource>,
    onSelect: (AIRequestSource?) -> Unit,
    errorOnly: Boolean,
    onToggleErrorOnly: () -> Unit,
) {
    val haptics = rememberPremiumHaptics()

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = {
                haptics.perform(HapticPattern.Pop)
                onSelect(null)
            },
            label = { Text(stringResource(R.string.request_logs_filter_all)) },
        )
        FilterChip(
            selected = errorOnly,
            onClick = {
                haptics.perform(HapticPattern.Pop)
                onToggleErrorOnly()
            },
            label = {
                Text(
                    text = stringResource(R.string.request_logs_filter_error),
                    color = if (errorOnly) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        sources.forEach { source ->
            FilterChip(
                selected = selected == source,
                onClick = {
                    haptics.perform(HapticPattern.Pop)
                    onSelect(source)
                },
                label = { Text(source.displayName()) },
            )
        }
    }
}

@Composable
private fun RequestLogOverviewItem(
    log: AIRequestLogEntity,
    onClick: () -> Unit,
) {
    val haptics = rememberPremiumHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "log_item_scale",
    )

    val sourceLabel = resolveSourceLabel(log.source)
    val time = remember(log.createdAt) { formatLogTime(log.createdAt, "HH:mm:ss") }
    val durationText = log.durationMs?.let { "${it}ms" }.orEmpty()

    val statusText = if (log.error != null) {
        stringResource(R.string.request_log_status_error)
    } else {
        stringResource(R.string.request_log_status_ok)
    }
    val statusColor = if (log.error != null) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    haptics.perform(HapticPattern.Pop)
                    onClick()
                }
            )
            .animateContentSize(animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f)),
        shape = AppShapes.CardMedium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceTag(text = sourceLabel)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (durationText.isNotBlank()) {
                    Text(
                        text = durationText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = "${log.providerName} · ${log.modelDisplayName.ifBlank { log.modelId }}",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = log.requestUrl.ifBlank { "-" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                )
                if (log.stream) {
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "stream",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Serializable
private data class RequestLogExportItem(
    val id: Long,
    val createdAt: Long,
    val latencyMs: Long?,
    val durationMs: Long?,
    val source: String,
    val providerName: String,
    val providerType: String,
    val modelId: String,
    val modelDisplayName: String,
    val stream: Boolean,
    val paramsJson: String,
    val requestBodyJson: String,
    val requestUrl: String,
    val requestPreview: String,
    val responsePreview: String,
    val responseText: String,
    val responseRawText: String,
    val error: String?,
)

private fun AIRequestLogEntity.toExportItem(): RequestLogExportItem = RequestLogExportItem(
    id = id,
    createdAt = createdAt,
    latencyMs = latencyMs,
    durationMs = durationMs,
    source = source,
    providerName = providerName,
    providerType = providerType,
    modelId = modelId,
    modelDisplayName = modelDisplayName,
    stream = stream,
    paramsJson = paramsJson,
    requestBodyJson = requestMessagesJson,
    requestUrl = requestUrl,
    requestPreview = requestPreview,
    responsePreview = responsePreview,
    responseText = responseText,
    responseRawText = responseRawText,
    error = error,
)
