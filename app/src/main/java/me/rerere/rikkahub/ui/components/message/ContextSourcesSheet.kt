package me.rerere.rikkahub.ui.components.message

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import me.rerere.ai.ui.ContextCompositionKind
import me.rerere.ai.ui.ContextCompositionSlice
import me.rerere.ai.ui.UsedLorebookEntry
import me.rerere.ai.ui.UsedMemory
import me.rerere.ai.ui.UsedMemorySummary
import me.rerere.ai.ui.UsedMode
import me.rerere.ai.ui.UsedSessionMemory
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.SessionMemory
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemorySummaryRepository
import me.rerere.rikkahub.ui.components.richtext.MarkdownBlock
import me.rerere.rikkahub.data.datastore.getEffectiveDisplaySetting
import me.rerere.rikkahub.data.ai.compositionPercentages
import me.rerere.rikkahub.data.ai.compositionSegmentCenterFraction
import me.rerere.rikkahub.data.ai.compositionSegmentIndexAt
import me.rerere.rikkahub.data.ai.formatCompositionTokenMagnitude
import me.rerere.rikkahub.data.ai.scaleCompositionTokens
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import org.koin.compose.koinInject

private val json = Json { ignoreUnknownKeys = true }
private const val SESSION_MEMORY_EDITOR_MAX_CHARS = 1000

private data class ContextSourcePreview(
    val titleRes: Int,
    val content: String,
)

// Corner radius values matching PhysicsSwipeToDelete
private val groupCornerRadius = 24.dp
private val itemCornerRadius = 10.dp

private class CompositionOverlayBridge {
    var dismiss: (() -> Unit)? = null
    var sheetOrigin: Offset = Offset.Zero
    var barBounds: Rect = Rect.Zero
    var tooltipBounds: Rect = Rect.Zero
}

/**
 * Bottom sheet displaying all context sources used in a message:
 * modes, memories, and lorebook entries.
 */
@Composable
fun ContextSourcesSheet(
    modes: List<UsedMode> = emptyList(),
    memories: List<UsedMemory> = emptyList(),
    sessionMemories: List<UsedSessionMemory> = emptyList(),
    memorySummary: UsedMemorySummary? = null,
    assistantId: String? = null,
    currentSessionMemories: List<SessionMemory> = emptyList(),
    entries: List<UsedLorebookEntry> = emptyList(),
    contextComposition: List<ContextCompositionSlice>? = null,
    promptTokens: Int? = null,
    onModeClick: ((UsedMode) -> Unit)? = null,
    onSessionMemorySave: ((memoryId: Int, content: String) -> Unit)? = null,
    onSessionMemoryDelete: ((memoryId: Int) -> Unit)? = null,
    onEntryClick: ((UsedLorebookEntry) -> Unit)? = null,
    onDismissRequest: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val memoryRepository: MemoryRepository = koinInject()
    val memorySummaryRepository: MemorySummaryRepository = koinInject()
    var editingSessionMemory by remember { mutableStateOf<UsedSessionMemory?>(null) }
    var preview by remember { mutableStateOf<ContextSourcePreview?>(null) }
    var summaryPreviewContent by remember { mutableStateOf<String?>(null) }
    
    val sortedModes = remember(modes) { modes.sortedByDescending { it.priority } }
    val currentSessionMemoryById = remember(currentSessionMemories) {
        currentSessionMemories.associateBy { it.id }
    }
    val sortedSessionMemories = remember(sessionMemories, currentSessionMemoryById) {
        sessionMemories
            .map { usedMemory ->
                currentSessionMemoryById[usedMemory.memoryId]
                    ?.let { currentMemory -> usedMemory.copy(memoryContent = currentMemory.content) }
                    ?: usedMemory
            }
            .sortedByDescending { it.priority }
    }
    val sortedMemories = remember(memories) { memories.sortedByDescending { it.priority } }
    val sortedEntries = remember(entries) { entries.sortedByDescending { it.priority } }
    val compositionSlices = remember(contextComposition) {
        contextComposition.orEmpty().filter { it.estimatedTokens > 0 }
    }
    val compositionOverlay = remember { CompositionOverlayBridge() }

    editingSessionMemory?.let { memory ->
        SessionMemoryEditDialog(
            memory = memory,
            canEdit = onSessionMemorySave != null && memory.memoryId in currentSessionMemoryById,
            canDelete = onSessionMemoryDelete != null && memory.memoryId in currentSessionMemoryById,
            onDismiss = { editingSessionMemory = null },
            onSave = { content ->
                onSessionMemorySave?.invoke(memory.memoryId, content)
                editingSessionMemory = null
            },
            onDelete = {
                onSessionMemoryDelete?.invoke(memory.memoryId)
                editingSessionMemory = null
            }
        )
    }

    preview?.let { source ->
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text(stringResource(source.titleRes)) },
            text = {
                SelectionContainer {
                    Text(
                        text = source.content,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 520.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { preview = null }) {
                    Text(stringResource(R.string.done))
                }
            },
        )
    }

    summaryPreviewContent?.let { content ->
        val summarySheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { summaryPreviewContent = null },
            sheetState = summarySheetState,
            shape = AppShapes.BottomSheet,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.context_sources_section_memory_summary),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SelectionContainer {
                        MarkdownBlock(
                            content = content.ifBlank {
                                stringResource(R.string.assistant_page_memory_summary_empty)
                            },
                        )
                    }
                }
            }
        }
    }
    
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        sheetGesturesEnabled = false,
        dragHandle = {
            IconButton(
                onClick = {
                    scope.launch {
                        sheetState.hide()
                        onDismissRequest()
                    }
                }
            ) {
                Icon(Icons.Rounded.KeyboardArrowDown, null)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .onGloballyPositioned { compositionOverlay.sheetOrigin = it.positionInWindow() }
                .pointerInput(compositionOverlay) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val down = event.changes.firstOrNull { it.changedToDown() } ?: continue
                            val dismiss = compositionOverlay.dismiss ?: continue
                            if (compositionOverlay.barBounds == Rect.Zero) continue
                            val point = compositionOverlay.sheetOrigin + down.position
                            val onBar = compositionOverlay.barBounds.contains(point)
                            val onTooltip = compositionOverlay.tooltipBounds.contains(point)
                            if (!onBar && !onTooltip) dismiss()
                        }
                    }
                },
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header - centered
            Text(
                text = stringResource(R.string.context_sources_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (compositionSlices.isNotEmpty()) {
                    item {
                        ContextCompositionSection(
                            slices = compositionSlices,
                            promptTokens = promptTokens,
                            overlay = compositionOverlay,
                        )
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }

                // Modes Section
                if (sortedModes.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.context_sources_section_modes)) }
                    itemsIndexed(sortedModes) { index, mode ->
                        val shape = getGroupedShape(index, sortedModes.size)
                        ModeItem(
                            mode = mode,
                            shape = shape,
                            isFirst = index == 0,
                            isLast = index == sortedModes.lastIndex,
                            onClick = { onModeClick?.invoke(mode) }
                        )
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }

                // Session Memories Section
                if (sortedSessionMemories.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.context_sources_section_session_memories)) }
                    itemsIndexed(sortedSessionMemories) { index, memory ->
                        val shape = getGroupedShape(index, sortedSessionMemories.size)
                        SessionMemoryItem(
                            memory = memory,
                            shape = shape,
                            isFirst = index == 0,
                            isLast = index == sortedSessionMemories.lastIndex,
                            onClick = { editingSessionMemory = memory }
                        )
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
                
                // Memories Section
                memorySummary?.let { summary ->
                    item { SectionHeader(stringResource(R.string.context_sources_section_memory_summary)) }
                    item {
                        MemorySummaryItem(
                            summary = summary,
                            onClick = {
                                scope.launch {
                                    val versionContent = summary.versionId?.let { versionId ->
                                        assistantId?.let { id ->
                                            withContext(Dispatchers.IO) {
                                                memorySummaryRepository.getVersion(id, versionId)?.content
                                            }
                                        }
                                    }
                                    summaryPreviewContent = versionContent ?: summary.content
                                }
                            },
                        )
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }

                if (sortedMemories.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.context_sources_section_memories)) }
                    itemsIndexed(sortedMemories) { index, memory ->
                        val shape = getGroupedShape(index, sortedMemories.size)
                        MemoryItem(
                            memory = memory,
                            shape = shape,
                            isFirst = index == 0,
                            isLast = index == sortedMemories.lastIndex,
                            onClick = {
                                scope.launch {
                                    val currentContent = withContext(Dispatchers.IO) {
                                        if (memory.memoryType == 0) {
                                            memoryRepository.getCoreMemoryById(memory.memoryId)?.content
                                        } else {
                                            memoryRepository.getEpisodeMemoryById(
                                                kotlin.math.abs(memory.memoryId)
                                            )?.content
                                        }
                                    }
                                    preview = ContextSourcePreview(
                                        titleRes = when {
                                            memory.memoryType == 0 -> R.string.memory_type_core
                                            memory.memoryId < 0 -> R.string.memory_type_recent_chat
                                            else -> R.string.memory_type_episodic
                                        },
                                        content = currentContent ?: memory.memoryContent,
                                    )
                                }
                            },
                        )
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
                
                // Lorebook Entries Section
                if (sortedEntries.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.context_sources_section_lorebook_entries)) }
                    itemsIndexed(sortedEntries) { index, entry ->
                        val shape = getGroupedShape(index, sortedEntries.size)
                        LorebookEntryItem(
                            entry = entry,
                            shape = shape,
                            isFirst = index == 0,
                            isLast = index == sortedEntries.lastIndex,
                            onClick = { onEntryClick?.invoke(entry) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemorySummaryItem(
    summary: UsedMemorySummary,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(groupCornerRadius),
        color = if (LocalDarkMode.current) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(45.dp)
                    .height(60.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Text(
                text = summary.content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Calculates the corner shape for a grouped item based on position
 */
private fun getGroupedShape(index: Int, total: Int): RoundedCornerShape {
    return when {
        total == 1 -> RoundedCornerShape(groupCornerRadius)
        index == 0 -> RoundedCornerShape(
            topStart = groupCornerRadius, topEnd = groupCornerRadius,
            bottomStart = itemCornerRadius, bottomEnd = itemCornerRadius
        )
        index == total - 1 -> RoundedCornerShape(
            topStart = itemCornerRadius, topEnd = itemCornerRadius,
            bottomStart = groupCornerRadius, bottomEnd = groupCornerRadius
        )
        else -> RoundedCornerShape(itemCornerRadius)
    }
}

@Composable
private fun ContextCompositionSection(
    slices: List<ContextCompositionSlice>,
    promptTokens: Int?,
    overlay: CompositionOverlayBridge,
) {
    val weights = remember(slices) { slices.map { it.estimatedTokens } }
    val hasMeasuredTotal = promptTokens != null && promptTokens > 0
    val displayedTokens = remember(weights, promptTokens) {
        scaleCompositionTokens(weights, promptTokens)
    }
    val percentages = remember(weights) { compositionPercentages(weights) }
    val totalTokens = promptTokens?.takeIf { it > 0 } ?: weights.sum()
    val settings = LocalSettings.current
    val haptics = rememberPremiumHaptics(enabled = settings.getEffectiveDisplaySetting().enableUIHaptics)
    var selectedIndex by remember(slices) { mutableStateOf<Int?>(null) }
    SideEffect {
        overlay.dismiss = if (selectedIndex != null) {
            { selectedIndex = null }
        } else {
            overlay.tooltipBounds = Rect.Zero
            null
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionHeader(stringResource(R.string.context_composition_title))
        Column(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CompositionBar(
                slices = slices,
                weights = weights,
                selectedIndex = selectedIndex,
                tooltip = selectedIndex?.let { index ->
                    val slice = slices.getOrNull(index) ?: return@let null
                    val kindLabel = stringResource(compositionKindLabel(slice.kind))
                    val percent = percentages.getOrElse(index) { 0 }
                    CompositionTooltipModel(
                        label = kindLabel,
                        percent = percent,
                        value = stringResource(
                            R.string.context_composition_token_estimate,
                            formatCompositionTokenMagnitude(displayedTokens.getOrElse(index) { 0 }),
                        ),
                        centerFraction = compositionSegmentCenterFraction(weights, index),
                    )
                },
                onBarBounds = { overlay.barBounds = it },
                onTooltipBounds = { overlay.tooltipBounds = it },
                onSelect = { index ->
                    val updated = index.takeUnless { it == selectedIndex }
                    if (updated != selectedIndex) haptics.perform(HapticPattern.Pop)
                    selectedIndex = updated
                },
            )
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                slices.forEachIndexed { index, slice ->
                    val percent = percentages.getOrElse(index) { 0 }
                    CompositionDetailRow(
                        label = stringResource(compositionKindLabel(slice.kind)),
                        percent = percent,
                        tokens = stringResource(
                            R.string.context_composition_token_estimate,
                            formatCompositionTokenMagnitude(displayedTokens.getOrElse(index) { 0 }),
                        ),
                        color = compositionColor(slice.kind),
                        emphasized = selectedIndex == index,
                    )
                }
                CompositionDetailRow(
                    label = stringResource(R.string.context_composition_total),
                    tokens = stringResource(
                        if (hasMeasuredTotal) {
                            R.string.context_composition_token_count
                        } else {
                            R.string.context_composition_token_estimate
                        },
                        formatCompositionTokenMagnitude(totalTokens),
                    ),
                    color = null,
                    emphasized = false,
                )
            }
        }
    }
}

private data class CompositionTooltipModel(
    val label: String,
    val percent: Int,
    val value: String,
    val centerFraction: Float,
)

@Composable
private fun CompositionBar(
    slices: List<ContextCompositionSlice>,
    weights: List<Int>,
    selectedIndex: Int?,
    tooltip: CompositionTooltipModel?,
    onBarBounds: (Rect) -> Unit,
    onTooltipBounds: (Rect) -> Unit,
    onSelect: (Int) -> Unit,
) {
    val thickness by animateFloatAsState(
        targetValue = if (selectedIndex != null) 1.35f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "composition_bar_thickness",
    )
    var barWidthPx by remember { mutableIntStateOf(0) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .onSizeChanged { barWidthPx = it.width }
            .onGloballyPositioned { onBarBounds(it.boundsInWindow()) }
            .pointerInput(weights) {
                detectTapGestures { tap ->
                    val index = compositionSegmentIndexAt(tap.x, size.width.toFloat(), weights)
                    if (index >= 0) onSelect(index)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(14.dp)
                .graphicsLayer { scaleY = thickness }
                .clip(AppShapes.ButtonPill),
        ) {
            slices.forEachIndexed { index, slice ->
                val alpha by animateFloatAsState(
                    targetValue = if (selectedIndex == null || selectedIndex == index) 1f else 0.38f,
                    animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
                    label = "composition_segment_alpha",
                )
                Box(
                    modifier = Modifier
                        .weight(slice.estimatedTokens.coerceAtLeast(1).toFloat())
                        .fillMaxHeight()
                        .graphicsLayer { this.alpha = alpha }
                        .background(compositionColor(slice.kind)),
                )
            }
        }
        if (tooltip != null) {
            CompositionSegmentTooltip(
                model = tooltip,
                barWidthPx = barWidthPx,
                onBounds = onTooltipBounds,
            )
        }
    }
}

@Composable
private fun CompositionSegmentTooltip(
    model: CompositionTooltipModel,
    barWidthPx: Int,
    onBounds: (Rect) -> Unit,
) {
    var tooltipWidth by remember { mutableIntStateOf(0) }
    var tooltipHeight by remember { mutableIntStateOf(0) }
    val scale = remember { Animatable(0.85f) }
    LaunchedEffect(model.label, model.value) {
        scale.snapTo(0.85f)
        scale.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 300f))
    }
    val density = LocalDensity.current
    val lift = with(density) { 8.dp.roundToPx() }
    val x = (barWidthPx * model.centerFraction - tooltipWidth / 2f).roundToInt()
        .coerceIn(0, (barWidthPx - tooltipWidth).coerceAtLeast(0))
    val y = -(tooltipHeight + lift)
    Popup(
        alignment = Alignment.TopStart,
        offset = IntOffset(x, y),
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            clippingEnabled = false,
        ),
    ) {
        Surface(
            modifier = Modifier
                .onSizeChanged {
                    tooltipWidth = it.width
                    tooltipHeight = it.height
                }
                .onGloballyPositioned { onBounds(it.boundsInWindow()) }
                .graphicsLayer {
                    alpha = if (tooltipWidth > 0 && tooltipHeight > 0) 1f else 0f
                    scaleX = scale.value
                    scaleY = scale.value
                },
            shape = AppShapes.ButtonPill,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 4.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val percentColor = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.60f)
                Text(
                    text = buildAnnotatedString {
                        append(model.label)
                        withStyle(SpanStyle(color = percentColor)) {
                            append(" (${model.percent}%)")
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = model.value,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CompositionDetailRow(
    label: String,
    tokens: String,
    color: Color?,
    emphasized: Boolean,
    percent: Int? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (color != null) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        } else {
            Spacer(Modifier.size(8.dp))
        }
        val percentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        Text(
            text = buildAnnotatedString {
                append(label)
                if (percent != null) {
                    withStyle(SpanStyle(color = percentColor, fontWeight = FontWeight.Normal)) {
                        append(" ($percent%)")
                    }
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = tokens,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun compositionColor(kind: ContextCompositionKind): Color {
    val scheme = MaterialTheme.colorScheme
    return when (kind) {
        ContextCompositionKind.CONVERSATION -> scheme.primary
        ContextCompositionKind.MEMORY -> scheme.tertiary
        ContextCompositionKind.MEMORY_SUMMARY -> scheme.secondary
        ContextCompositionKind.SESSION_MEMORY -> scheme.onTertiaryContainer
        ContextCompositionKind.TOOL_PROMPT -> scheme.inversePrimary
        ContextCompositionKind.SKILL -> scheme.onSecondaryContainer
        ContextCompositionKind.ASSISTANT_PROMPT -> scheme.onPrimaryContainer
        ContextCompositionKind.LOREBOOK -> scheme.onTertiary
        ContextCompositionKind.PRESET -> scheme.onSecondary
        ContextCompositionKind.OTHER -> scheme.outline
    }
}

private fun compositionKindLabel(kind: ContextCompositionKind): Int {
    return when (kind) {
        ContextCompositionKind.CONVERSATION -> R.string.context_composition_kind_conversation
        ContextCompositionKind.MEMORY -> R.string.context_composition_kind_memory
        ContextCompositionKind.MEMORY_SUMMARY -> R.string.context_composition_kind_memory_summary
        ContextCompositionKind.SESSION_MEMORY -> R.string.context_composition_kind_session_memory
        ContextCompositionKind.TOOL_PROMPT -> R.string.context_composition_kind_tool_prompt
        ContextCompositionKind.SKILL -> R.string.context_composition_kind_skill
        ContextCompositionKind.ASSISTANT_PROMPT -> R.string.context_composition_kind_assistant_prompt
        ContextCompositionKind.LOREBOOK -> R.string.context_composition_kind_lorebook
        ContextCompositionKind.PRESET -> R.string.context_composition_kind_preset
        ContextCompositionKind.OTHER -> R.string.context_composition_kind_other
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp, start = 4.dp)
    )
}

@Composable
private fun ModeItem(
    mode: UsedMode,
    shape: RoundedCornerShape,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit
) {
    val isDarkMode = LocalDarkMode.current
    
    // Optical roundness for the cover
    val opticalRadius = 12.dp
    val defaultRadius = 6.dp
    val coverShape = RoundedCornerShape(
        topStart = if (isFirst) opticalRadius else defaultRadius,
        topEnd = defaultRadius,
        bottomStart = if (isLast) opticalRadius else defaultRadius,
        bottomEnd = defaultRadius
    )
    
    Surface(
        onClick = onClick,
        shape = shape,
        color = if (isDarkMode) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mode icon cover
            Box(
                modifier = Modifier
                    .width(45.dp)
                    .height(60.dp)
                    .clip(coverShape)
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        shape = coverShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = mode.modeName.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            
            // Mode info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.context_sources_mode_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = mode.modeName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                mode.activationReason?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionMemoryItem(
    memory: UsedSessionMemory,
    shape: RoundedCornerShape,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
) {
    val isDarkMode = LocalDarkMode.current
    val opticalRadius = 12.dp
    val defaultRadius = 6.dp
    val coverShape = RoundedCornerShape(
        topStart = if (isFirst) opticalRadius else defaultRadius,
        topEnd = defaultRadius,
        bottomStart = if (isLast) opticalRadius else defaultRadius,
        bottomEnd = defaultRadius
    )

    Surface(
        onClick = onClick,
        shape = shape,
        color = if (isDarkMode) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(45.dp)
                    .height(60.dp)
                    .clip(coverShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        shape = coverShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Bookmark,
                    contentDescription = stringResource(R.string.memory_type_session),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.memory_type_session),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = memory.memoryContent,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                memory.activationReason?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionMemoryEditDialog(
    memory: UsedSessionMemory,
    canEdit: Boolean,
    canDelete: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var content by remember(memory.memoryId, memory.memoryContent) {
        mutableStateOf(memory.memoryContent)
    }
    val trimmedContent = content.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = stringResource(R.string.memory_type_session))
        },
        text = {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it.take(SESSION_MEMORY_EDITOR_MAX_CHARS) },
                modifier = Modifier.fillMaxWidth(),
                readOnly = !canEdit,
                minLines = 6,
                maxLines = 12,
                label = {
                    Text(text = stringResource(R.string.memory_type_session))
                },
                supportingText = {
                    Text(
                        text = "${content.length}/$SESSION_MEMORY_EDITOR_MAX_CHARS",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.End
                    )
                }
            )
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (canDelete) {
                    TextButton(onClick = onDelete) {
                        Text(
                            text = stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.cancel))
                }
                TextButton(
                    onClick = { onSave(trimmedContent) },
                    enabled = canEdit && trimmedContent.isNotBlank()
                ) {
                    Text(text = stringResource(R.string.save))
                }
            }
        }
    )
}

@Composable
private fun MemoryItem(
    memory: UsedMemory,
    shape: RoundedCornerShape,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit
) {
    val isDarkMode = LocalDarkMode.current
    val isCore = memory.memoryType == 0
    
    val backgroundColor = if (isCore) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = if (isCore) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }
    
    // Optical roundness for the cover
    val opticalRadius = 12.dp
    val defaultRadius = 6.dp
    val coverShape = RoundedCornerShape(
        topStart = if (isFirst) opticalRadius else defaultRadius,
        topEnd = defaultRadius,
        bottomStart = if (isLast) opticalRadius else defaultRadius,
        bottomEnd = defaultRadius
    )
    
    Surface(
        onClick = onClick,
        shape = shape,
        color = if (isDarkMode) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Memory type icon
            Box(
                modifier = Modifier
                    .width(45.dp)
                    .height(60.dp)
                    .clip(coverShape)
                    .background(backgroundColor)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        shape = coverShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isCore -> {
                        Icon(
                            imageVector = Icons.Rounded.Memory,
                            contentDescription = stringResource(R.string.memory_type_core),
                            modifier = Modifier.size(24.dp),
                            tint = contentColor
                        )
                    }
                    memory.memoryId < 0 -> {
                        // Recent chat reference (non-RAG mode)
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = stringResource(R.string.memory_type_recent_chat),
                            modifier = Modifier.size(24.dp),
                            tint = contentColor
                        )
                    }
                    else -> {
                        // True episodic memory (RAG mode)
                        Image(
                            painter = painterResource(R.drawable.search_activity_24),
                            contentDescription = stringResource(R.string.memory_type_episodic),
                            modifier = Modifier.size(24.dp),
                            colorFilter = ColorFilter.tint(contentColor)
                        )
                    }
                }
            }
            
            // Memory info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = when {
                        isCore -> stringResource(R.string.memory_type_core)
                        memory.memoryId < 0 -> stringResource(R.string.memory_type_recent_chat) // Recent chat reference (non-RAG mode)
                        else -> stringResource(R.string.memory_type_episodic) // True episodic memory (RAG mode)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = memory.memoryContent,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                memory.activationReason?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun LorebookEntryItem(
    entry: UsedLorebookEntry,
    shape: RoundedCornerShape,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit
) {
    val isDarkMode = LocalDarkMode.current
    
    val cover = remember(entry.lorebookCover) {
        entry.lorebookCover?.let { coverJson ->
            try {
                json.decodeFromString(Avatar.serializer(), coverJson)
            } catch (e: Exception) {
                null
            }
        }
    }
    
    // Optical roundness for the cover
    val opticalRadius = 12.dp
    val defaultRadius = 6.dp
    val coverShape = RoundedCornerShape(
        topStart = if (isFirst) opticalRadius else defaultRadius,
        topEnd = defaultRadius,
        bottomStart = if (isLast) opticalRadius else defaultRadius,
        bottomEnd = defaultRadius
    )
    
    Surface(
        onClick = onClick,
        shape = shape,
        color = if (isDarkMode) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Book cover
            Box(
                modifier = Modifier
                    .width(45.dp)
                    .height(60.dp)
                    .clip(coverShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                        shape = coverShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                when (cover) {
                    is Avatar.Image -> {
                        AsyncImage(
                            model = cover.url,
                            contentDescription = entry.lorebookName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    is Avatar.Emoji -> {
                        Text(text = cover.content, fontSize = 24.sp)
                    }
                    is Avatar.Resource -> {
                        AsyncImage(
                            model = cover.id,
                            contentDescription = entry.lorebookName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                    else -> {
                        Text(
                            text = entry.lorebookName.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                
                // Entry number
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(24.dp)
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.8f)
                                )
                            )
                        )
                        .padding(bottom = 2.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Text(
                        text = "#${entry.entryIndex + 1}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = Color.White
                    )
                }
            }
            
            // Entry info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                val entryTitle = entry.entryName.takeIf(String::isNotBlank)
                    ?: stringResource(R.string.lorebook_entry_numbered, entry.entryIndex + 1)
                Text(
                    text = entry.lorebookName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = entryTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                entry.activationReason?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
