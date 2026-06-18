package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import me.rerere.ai.provider.Model
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantMemory
import me.rerere.rikkahub.data.repository.AssistantMemoryStats
import me.rerere.rikkahub.ui.components.ui.Select
import me.rerere.rikkahub.ui.hooks.EditStateContent
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.theme.AppShapes
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.toLocalString
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Memory mode based on current settings
 */
private fun getMemoryMode(assistant: Assistant): MemoryMode {
    return when {
        !assistant.enableMemory -> MemoryMode.OFF
        assistant.enableMemoryConsolidation -> MemoryMode.ADVANCED
        assistant.useRagMemoryRetrieval -> MemoryMode.BASIC_RAG
        else -> MemoryMode.BASIC
    }
}

private enum class MemoryMode(
    @androidx.annotation.StringRes val titleRes: Int,
    @androidx.annotation.StringRes val descriptionRes: Int,
) {
    OFF(R.string.assistant_page_memory_mode_off_name, R.string.assistant_page_memory_mode_off_desc),
    BASIC(R.string.assistant_page_memory_mode_basic_name, R.string.assistant_page_memory_mode_basic_desc),
    BASIC_RECENT(R.string.assistant_page_memory_mode_basic_recent_name, R.string.assistant_page_memory_mode_basic_recent_desc),
    BASIC_RAG(R.string.assistant_page_memory_mode_basic_rag_name, R.string.assistant_page_memory_mode_basic_rag_desc),
    ADVANCED(R.string.assistant_page_memory_mode_advanced_name, R.string.assistant_page_memory_mode_advanced_desc),
}

@Composable
fun AssistantMemorySettings(
    assistant: Assistant,
    memories: List<AssistantMemory>,
    memoryStats: AssistantMemoryStats,
    onUpdateAssistant: (Assistant) -> Unit,
    onAddMemory: (AssistantMemory) -> Unit,
    onUpdateMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
    onRegenerateEmbeddings: (() -> Unit)? = null,
    embeddingProgress: EmbeddingProgress? = null,
    onTestRetrieval: ((String) -> Unit)? = null,
    retrievalResults: List<Pair<AssistantMemory, Float>> = emptyList(),
    assistantDetailVM: AssistantDetailVM,
    estimatedMemoryCapacity: Int,
    needsEmbeddingRegeneration: Boolean = false,
    initialMemoryTab: Int? = null,  // 0 = Core, 1 = Episodic
    scrollToMemoryId: Int? = null
) {
    val memoryDialogState = useEditState<AssistantMemory> {
        if (it.id == 0) {
            onAddMemory(it)
        } else {
            onUpdateMemory(it)
        }
    }
    
    // Embedding progress dialog
    if (embeddingProgress != null && embeddingProgress.isRunning) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Generating Embeddings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Processing ${embeddingProgress.current} of ${embeddingProgress.total} items...")
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { embeddingProgress.current.toFloat() / embeddingProgress.total.coerceAtLeast(1) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = { }
        )
    }

    // Memory edit dialog
    memoryDialogState.EditStateContent { memory, update ->
        val haptics = rememberPremiumHaptics()
        val canPin = memory.type == 0 && memory.id >= 0
        val canSaveMemory = memory.content.trim().isNotEmpty()
        val pinInteractionSource = remember { MutableInteractionSource() }
        val isPinPressed by pinInteractionSource.collectIsPressedAsState()
        val pinScale by animateFloatAsState(
            targetValue = if (isPinPressed) 0.85f else 1f,
            animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
            label = "memory_pin_scale",
        )
        AlertDialog(
            onDismissRequest = { memoryDialogState.dismiss() },
            title = { Text(stringResource(R.string.assistant_page_manage_memory_title)) },
            text = {
                TextField(
                    value = memory.content,
                    onValueChange = { update(memory.copy(content = it)) },
                    label = { Text(stringResource(R.string.assistant_page_manage_memory_title)) },
                    minLines = 1,
                    maxLines = 8
                )
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (canPin) {
                        FilterChip(
                            selected = memory.pinned,
                            onClick = {
                                haptics.perform(HapticPattern.Pop)
                                update(memory.copy(pinned = !memory.pinned))
                            },
                            label = { Text(stringResource(R.string.assistant_page_memory_pinned_badge)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.PushPin,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            interactionSource = pinInteractionSource,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.graphicsLayer {
                                scaleX = pinScale
                                scaleY = pinScale
                            },
                        )
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { memoryDialogState.dismiss() }) {
                            Text(stringResource(R.string.assistant_page_cancel))
                        }
                        TextButton(
                            onClick = { memoryDialogState.confirm() },
                            enabled = canSaveMemory
                        ) {
                            Text(stringResource(R.string.assistant_page_save))
                        }
                    }
                }
            }
        )
    }

    val memorySearchQuery by assistantDetailVM.memorySearchQuery.collectAsState()
    val currentEmbeddingModelId by assistantDetailVM.currentEmbeddingModelId.collectAsState()
    val currentMode = getMemoryMode(assistant)
    
    // Get all models for summarizer picker
    val providers by assistantDetailVM.providers.collectAsStateWithLifecycle()
    val allModels = remember(providers) { providers.flatMap { it.models } }
    val defaultModel = Model("default", "Default (Background Model)")
    val modelOptions = listOf(defaultModel) + allModels
    val selectedModel = allModels.find { it.id == assistant.summarizerModelId } ?: defaultModel

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Mode Indicator
        MemoryModeIndicator(mode = currentMode)
        
        // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺?
        // SETTINGS GROUP
        // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺?
        SettingsGroupHeader(title = stringResource(R.string.assistant_page_memory_settings_title))
        
        Column(
            modifier = Modifier.clip(RoundedCornerShape(24.dp)),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Master Toggle - Enable Memory (always visible)
            MemorySettingsItem(
                title = stringResource(R.string.assistant_page_memory),
                subtitle = stringResource(R.string.assistant_page_memory_desc),
                position = "FIRST",
                trailing = {
                    HapticSwitch(
                        checked = assistant.enableMemory,
                        onCheckedChange = { onUpdateAssistant(assistant.copy(enableMemory = it)) }
                    )
                }
            )

            MemorySettingsItem(
                title = stringResource(R.string.assistant_page_session_memory),
                subtitle = stringResource(R.string.assistant_page_session_memory_desc),
                position = if (assistant.enableMemory) "MIDDLE" else "LAST",
                trailing = {
                    HapticSwitch(
                        checked = assistant.enableSessionMemory,
                        onCheckedChange = { onUpdateAssistant(assistant.copy(enableSessionMemory = it)) }
                    )
                }
            )

            // Recent Chats Toggle (only under advanced/consolidation memory; otherwise the
            // title-only injection adds noise without useful content)
            AnimatedVisibility(
                visible = assistant.enableMemory && assistant.enableMemoryConsolidation,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                val isLockedByConsolidation = assistant.enableMemoryConsolidation

                MemorySettingsItem(
                    title = stringResource(R.string.assistant_page_recent_chats),
                    subtitle = stringResource(R.string.assistant_page_recent_chats_desc),
                    // RAG toggle is always visible below when memory is on, so this is always MIDDLE
                    position = "MIDDLE",
                    trailing = {
                        // Use 0.75f alpha for disabled state - subtle but visible
                        val toggleAlpha by animateFloatAsState(
                            targetValue = if (isLockedByConsolidation) 0.75f else 1f,
                            animationSpec = spring(stiffness = 300f),
                            label = "toggle_alpha"
                        )
                        Box(modifier = Modifier.graphicsLayer { alpha = toggleAlpha }) {
                            HapticSwitch(
                                checked = assistant.enableRecentChatsReference || isLockedByConsolidation,
                                onCheckedChange = { 
                                    if (!isLockedByConsolidation) {
                                        onUpdateAssistant(assistant.copy(enableRecentChatsReference = it))
                                    }
                                },
                                enabled = !isLockedByConsolidation
                            )
                        }
                    }
                )
            }

            // RAG Toggle (when memory enabled)
            AnimatedVisibility(
                visible = assistant.enableMemory,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                MemorySettingsItem(
                    title = stringResource(R.string.assistant_page_rag_retrieval_title),
                    subtitle = stringResource(R.string.assistant_page_rag_retrieval_desc),
                    position = if (!assistant.useRagMemoryRetrieval) "LAST" else "MIDDLE",
                    trailing = {
                        HapticSwitch(
                            checked = assistant.useRagMemoryRetrieval,
                            onCheckedChange = { enabled ->
                                if (!enabled) {
                                    onUpdateAssistant(assistant.copy(
                                        useRagMemoryRetrieval = false,
                                        enableMemoryConsolidation = false
                                    ))
                                } else {
                                    onUpdateAssistant(assistant.copy(useRagMemoryRetrieval = true))
                                }
                            }
                        )
                    }
                )
            }

            // Memory Consolidation Toggle (requires RAG)
            AnimatedVisibility(
                visible = assistant.enableMemory && assistant.useRagMemoryRetrieval,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                MemorySettingsItem(
                    title = stringResource(R.string.assistant_page_rag_advanced_memory_title),
                    subtitle = stringResource(R.string.assistant_page_rag_advanced_memory_desc),
                    position = "LAST",
                    trailing = {
                        HapticSwitch(
                            checked = assistant.enableMemoryConsolidation,
                            onCheckedChange = { enabled ->
                                if (!enabled) {
                                    onUpdateAssistant(assistant.copy(
                                        enableMemoryConsolidation = false
                                    ))
                                } else {
                                    onUpdateAssistant(assistant.copy(
                                        enableMemoryConsolidation = true,
                                        enableRecentChatsReference = true
                                    ))
                                }
                            }
                        )
                    }
                )
            }
        }

        // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺?
        // RAG SETTINGS (when RAG is enabled)
        // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺?
        AnimatedVisibility(
            visible = assistant.enableMemory && assistant.useRagMemoryRetrieval,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsGroupHeader(title = stringResource(R.string.assistant_page_rag_settings_title))
                RagSettingsCard(assistant = assistant, onUpdateAssistant = onUpdateAssistant)
            }
        }

        // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺?
        // CONSOLIDATION SETTINGS (when consolidation is enabled)
        // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺?
        AnimatedVisibility(
            visible = assistant.enableMemory && assistant.enableMemoryConsolidation,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsGroupHeader(title = stringResource(R.string.assistant_page_advanced_memory_settings_title))
                ConsolidationSettingsCard(
                    assistant = assistant,
                    onUpdateAssistant = onUpdateAssistant,
                    modelOptions = modelOptions,
                    selectedModel = selectedModel,
                    onConsolidate = { assistantDetailVM.consolidateMemories(true) }
                )
            }
        }

        // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺?
        // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺?
        // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺?
        // MEMORY STATISTICS (when memory is enabled)
        // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺?
        AnimatedVisibility(
            visible = assistant.enableMemory,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            MemoryStatisticsCard(
                assistant = assistant,
                memoryStats = memoryStats,
                estimatedMemoryCapacity = estimatedMemoryCapacity
            )
        }

        // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺?
        // MANAGE MEMORIES (when memory is enabled)
        // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺?
        AnimatedVisibility(
            visible = assistant.enableMemory,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            ManageMemoriesSection(
                previewMemories = memories,
                memoryStats = memoryStats,
                assistant = assistant,
                onAddMemory = { memoryDialogState.open(AssistantMemory(0, "")) },
                onEditMemory = {
                    assistantDetailVM.resolveMemoryForEditing(it) { resolved ->
                        memoryDialogState.open(resolved)
                    }
                },
                onDeleteMemory = onDeleteMemory,
                onRegenerateEmbeddings = onRegenerateEmbeddings,
                needsEmbeddingRegeneration = needsEmbeddingRegeneration,
                memorySearchQuery = memorySearchQuery,
                onSearchQueryChange = { assistantDetailVM.updateMemorySearchQuery(it) },
                assistantDetailVM = assistantDetailVM,
                currentEmbeddingModelId = currentEmbeddingModelId,
                showMemoryTypes = assistant.enableMemoryConsolidation,
                initialMemoryTab = initialMemoryTab,
                scrollToMemoryId = scrollToMemoryId
            )
        }

        // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺?
        // MEMORY DEBUGGER (RAG only)
        // 鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺愨晲鈺?
        AnimatedVisibility(
            visible = assistant.enableMemory && assistant.useRagMemoryRetrieval && onTestRetrieval != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            if (onTestRetrieval != null) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SettingsGroupHeader(title = stringResource(R.string.assistant_page_memory_debugger_title))
                    MemoryDebugger(
                        onTestRetrieval = onTestRetrieval,
                        retrievalResults = retrievalResults
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsGroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
    )
}

@Composable
private fun MemorySettingsItem(
    title: String,
    subtitle: String? = null,
    position: String = "MIDDLE", // ONLY, FIRST, MIDDLE, LAST
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val haptics = rememberPremiumHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
        label = "scale"
    )
    
    val topCorner by animateDpAsState(
        targetValue = when (position) {
            "ONLY", "FIRST" -> 24.dp
            else -> 10.dp
        },
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 200f),
        label = "topCorner"
    )
    val bottomCorner by animateDpAsState(
        targetValue = when (position) {
            "ONLY", "LAST" -> 24.dp
            else -> 10.dp
        },
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 200f),
        label = "bottomCorner"
    )
    
    Surface(
        onClick = {
            if (onClick != null) {
                haptics.perform(HapticPattern.Pop)
                onClick()
            }
        },
        enabled = onClick != null,
        color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(
            topStart = topCorner,
            topEnd = topCorner,
            bottomStart = bottomCorner,
            bottomEnd = bottomCorner
        ),
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (trailing != null) {
                trailing()
            }
        }
    }
}

@Composable
private fun MemoryModeIndicator(mode: MemoryMode) {
    val backgroundColor by animateColorAsState(
        targetValue = if (mode == MemoryMode.OFF)
            if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh
        else
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
        animationSpec = spring(),
        label = "modeColor"
    )
    
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = backgroundColor,
        modifier = Modifier.animateContentSize()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Psychology,
                contentDescription = null,
                tint = if (mode == MemoryMode.OFF)
                    MaterialTheme.colorScheme.onSurfaceVariant
                else
                    MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Column {
                AnimatedContent(
                    targetState = mode.titleRes,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "modeName"
                ) { titleRes ->
                    Text(
                        text = stringResource(R.string.assistant_page_memory_mode_format, stringResource(titleRes)),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                AnimatedContent(
                    targetState = mode.descriptionRes,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "modeDesc"
                ) { descRes ->
                    Text(
                        text = stringResource(descRes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private enum class MemorySortOrder(@androidx.annotation.StringRes val displayNameRes: Int) {
    NEWEST_FIRST(R.string.assistant_page_sort_newest),
    OLDEST_FIRST(R.string.assistant_page_sort_oldest),
    ALPHABETICAL(R.string.assistant_page_sort_alphabetical),
}

@Composable
private fun RagSettingsCard(
    assistant: Assistant,
    onUpdateAssistant: (Assistant) -> Unit
) {
    Column(
        modifier = Modifier.clip(RoundedCornerShape(24.dp)),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Similarity Threshold
        Surface(
            color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 10.dp, bottomEnd = 10.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                var threshold by remember(assistant.ragSimilarityThreshold) {
                    mutableFloatStateOf(assistant.ragSimilarityThreshold)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.assistant_page_rag_similarity_threshold), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = String.format("%.2f", threshold),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = threshold,
                    onValueChange = { newValue ->
                        threshold = newValue
                        onUpdateAssistant(assistant.copy(ragSimilarityThreshold = newValue))
                    },
                    valueRange = 0f..1f,
                    steps = 19,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.assistant_page_rag_similarity_all), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.assistant_page_rag_similarity_exact), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Top-K
        Surface(
            color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 24.dp, bottomEnd = 24.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                var topK by remember(assistant.ragLimit) {
                    mutableFloatStateOf(assistant.ragLimit.coerceIn(0, 50).toFloat())
                }
                val topKInt = topK.roundToInt().coerceIn(0, 50)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.assistant_page_rag_topk), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = topKInt.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = stringResource(R.string.assistant_page_rag_topk_desc, topKInt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = topK,
                    onValueChange = { newValue ->
                        val newLimit = newValue.roundToInt().coerceIn(0, 50)
                        topK = newLimit.toFloat()
                        onUpdateAssistant(assistant.copy(ragLimit = newLimit))
                    },
                    valueRange = 0f..50f,
                    steps = 49,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun ConsolidationSettingsCard(
    assistant: Assistant,
    onUpdateAssistant: (Assistant) -> Unit,
    modelOptions: List<Model>,
    selectedModel: Model,
    onConsolidate: () -> Unit
) {
    Column(
        modifier = Modifier.clip(RoundedCornerShape(24.dp)),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Summarizer Model
        Surface(
            color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 10.dp, bottomEnd = 10.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.assistant_page_consolidation_summarizer_model), style = MaterialTheme.typography.titleMedium)
                Select(
                    options = modelOptions,
                    selectedOption = selectedModel,
                    onOptionSelected = { model ->
                        if (model.id.toString() == "default") {
                            onUpdateAssistant(assistant.copy(summarizerModelId = null))
                        } else {
                            onUpdateAssistant(assistant.copy(summarizerModelId = model.id))
                        }
                    },
                    optionToString = { it.displayName },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Consolidation Delay
        Surface(
            color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.assistant_page_consolidation_delay), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(R.string.assistant_page_minutes_format, assistant.consolidationDelayMinutes),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = stringResource(R.string.assistant_page_consolidation_delay_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = assistant.consolidationDelayMinutes.toFloat(),
                    onValueChange = { onUpdateAssistant(assistant.copy(consolidationDelayMinutes = it.toInt())) },
                    valueRange = 0f..240f,
                    steps = 23,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        // Manual consolidation
        Surface(
            color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp, topStart = 10.dp, topEnd = 10.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onConsolidate,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.Psychology, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.assistant_page_memory_consolidate_now))
                }
                
                if (assistant.lastConsolidationTime > 0) {
                    val time = java.time.Instant.ofEpochMilli(assistant.lastConsolidationTime)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDateTime()
                        .toLocalString()
                    Text(
                        text = stringResource(R.string.assistant_page_memory_last_run, time),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryStatisticsCard(
    assistant: Assistant,
    memoryStats: AssistantMemoryStats,
    estimatedMemoryCapacity: Int
) {
    val coreMemories = memoryStats.coreCount
    val episodicMemories = memoryStats.episodicCount
    val withEmbeddings = memoryStats.embeddedCount
    val totalMemories = memoryStats.totalCount

    Surface(
        color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.assistant_page_memory_statistics_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Only show Core/Episodic split when consolidation is enabled
                if (assistant.enableMemoryConsolidation) {
                    StatItem(
                        value = coreMemories.toString(),
                        label = stringResource(R.string.assistant_page_badge_core), // Using shorter badge text for stats
                        color = MaterialTheme.colorScheme.primary
                    )
                    StatItem(
                        value = episodicMemories.toString(),
                        label = stringResource(R.string.assistant_page_badge_episodic), // Using shorter badge text for stats
                        color = MaterialTheme.colorScheme.secondary
                    )
                } else {
                    StatItem(
                        value = totalMemories.toString(),
                        label = stringResource(R.string.assistant_page_memory_stats_total),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                // Show embeddings when RAG is enabled
                AnimatedVisibility(visible = assistant.useRagMemoryRetrieval) {
                    StatItem(
                        value = withEmbeddings.toString(),
                        label = stringResource(R.string.assistant_page_memory_stats_embedded),
                        color = if (withEmbeddings < totalMemories)
                            MaterialTheme.colorScheme.error 
                        else 
                            MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            AnimatedVisibility(visible = assistant.useRagMemoryRetrieval) {
                Text(
                    text = stringResource(R.string.assistant_page_memory_estimated_capacity, estimatedMemoryCapacity),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    value: String,
    label: String,
    color: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ManageMemoriesSection(
    previewMemories: List<AssistantMemory>,
    memoryStats: AssistantMemoryStats,
    assistant: Assistant,
    onAddMemory: () -> Unit,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
    onRegenerateEmbeddings: (() -> Unit)?,
    needsEmbeddingRegeneration: Boolean,
    memorySearchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    assistantDetailVM: AssistantDetailVM,
    currentEmbeddingModelId: String,
    showMemoryTypes: Boolean,
    initialMemoryTab: Int? = null,
    scrollToMemoryId: Int? = null
) {
    var selectedTab by remember { mutableIntStateOf(initialMemoryTab ?: 0) }
    var sortOrder by remember { mutableStateOf(MemorySortOrder.NEWEST_FIRST) }
    var showBottomSheet by remember { mutableStateOf(false) }

    LaunchedEffect(initialMemoryTab) {
        if (initialMemoryTab != null) {
            selectedTab = initialMemoryTab
        }
    }

    LaunchedEffect(scrollToMemoryId, initialMemoryTab) {
        if (scrollToMemoryId != null) {
            showBottomSheet = true
            assistantDetailVM.resolveMemoryByRoute(scrollToMemoryId, initialMemoryTab) { resolved ->
                if (resolved != null) {
                    onEditMemory(resolved)
                }
            }
        }
    }

    val displayPreviewMemories = if (showMemoryTypes) {
        when (selectedTab) {
            0 -> previewMemories.filter { it.type == 0 }
            else -> previewMemories.filter { it.type == 1 }
        }.sortedByDescending { it.timestamp }
    } else {
        previewMemories.sortedByDescending { it.timestamp }
    }.take(3)

    val tabCoreText = stringResource(R.string.assistant_page_badge_core) + " (${memoryStats.coreCount})"
    val tabEpisodicText = stringResource(R.string.assistant_page_badge_episodic) + " (${memoryStats.episodicCount})"

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.assistant_page_recent_memories_title),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (onRegenerateEmbeddings != null && assistant.useRagMemoryRetrieval && needsEmbeddingRegeneration) {
                    IconButton(onClick = onRegenerateEmbeddings) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.assistant_page_regenerate_embeddings_content_desc))
                    }
                }
                IconButton(onClick = onAddMemory) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.assistant_page_add_memory_content_desc))
                }
            }
        }

        if (displayPreviewMemories.isEmpty()) {
            Surface(
                color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.assistant_page_no_memories),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .animateContentSize(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                displayPreviewMemories.forEachIndexed { index, memory ->
                    val position = when {
                        displayPreviewMemories.size == 1 -> "ONLY"
                        index == 0 -> "FIRST"
                        index == displayPreviewMemories.size - 1 -> "LAST"
                        else -> "MIDDLE"
                    }
                    MemoryItem(
                        memory = memory,
                        onEditMemory = onEditMemory,
                        onDeleteMemory = onDeleteMemory,
                        useRagMemoryRetrieval = assistant.useRagMemoryRetrieval,
                        currentEmbeddingModelId = currentEmbeddingModelId,
                        showType = showMemoryTypes,
                        position = position
                    )
                }
            }
        }

        Button(
            onClick = { showBottomSheet = true },
            modifier = Modifier.fillMaxWidth(),
            shape = AppShapes.ButtonPill
        ) {
            Text(stringResource(R.string.assistant_page_view_all_memories))
        }
    }

    if (showBottomSheet) {
        val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var showSortMenu by remember { mutableStateOf(false) }
        val memoryType = if (showMemoryTypes) {
            when (selectedTab) {
                0 -> 0
                else -> 1
            }
        } else {
            -1
        }
        val pagingFlow = remember(memorySearchQuery, sortOrder, memoryType) {
            assistantDetailVM.getPagedMemories(
                memoryType = memoryType,
                sortOrder = sortOrder.toDatabaseSortOrder(),
                searchQuery = memorySearchQuery
            )
        }
        val pagedMemories = pagingFlow.collectAsLazyPagingItems()

        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = {
                scope.launch {
                    sheetState.hide()
                    showBottomSheet = false
                }
            },
            sheetState = sheetState,
            sheetGesturesEnabled = false,
            shape = AppShapes.BottomSheet,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxSize(0.95f)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.assistant_page_manage_memory_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.Rounded.Sort, contentDescription = stringResource(R.string.assistant_page_sort_content_desc))
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                MemorySortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(order.displayNameRes)) },
                                        onClick = {
                                            sortOrder = order
                                            showSortMenu = false
                                        },
                                        leadingIcon = {
                                            if (sortOrder == order) {
                                                Icon(Icons.Rounded.Checklist, null, tint = MaterialTheme.colorScheme.primary)
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        IconButton(onClick = onAddMemory) {
                            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.assistant_page_add_memory_content_desc))
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showMemoryTypes,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = Color.Transparent,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text(tabCoreText) },
                            icon = { Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(18.dp)) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text(tabEpisodicText) },
                            icon = { Icon(Icons.Rounded.History, null, modifier = Modifier.size(18.dp)) }
                        )
                    }
                }

                TextField(
                    value = memorySearchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.assistant_page_memory_search_placeholder)) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )

                val refreshState = pagedMemories.loadState.refresh
                when {
                    refreshState is androidx.paging.LoadState.Loading && pagedMemories.itemCount == 0 -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.CircularProgressIndicator()
                        }
                    }

                    pagedMemories.itemCount == 0 -> {
                        Surface(
                            color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            Text(
                                text = if (memorySearchQuery.isBlank()) stringResource(R.string.assistant_page_no_memories) else stringResource(R.string.assistant_page_no_matching_memories),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(24.dp)
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(24.dp))
                                .animateContentSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(
                                count = pagedMemories.itemCount,
                                key = pagedMemories.itemKey { item -> item.id }
                            ) { index ->
                                val memory = pagedMemories[index] ?: return@items
                                val position = when {
                                    pagedMemories.itemCount == 1 -> "ONLY"
                                    index == 0 -> "FIRST"
                                    index == pagedMemories.itemCount - 1 -> "LAST"
                                    else -> "MIDDLE"
                                }
                                MemoryItem(
                                    memory = memory,
                                    onEditMemory = onEditMemory,
                                    onDeleteMemory = onDeleteMemory,
                                    useRagMemoryRetrieval = assistant.useRagMemoryRetrieval,
                                    currentEmbeddingModelId = currentEmbeddingModelId,
                                    showType = showMemoryTypes,
                                    position = position
                                )
                            }

                            if (pagedMemories.loadState.append is androidx.paging.LoadState.Loading) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        androidx.compose.material3.CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun MemorySortOrder.toDatabaseSortOrder(): Int = when (this) {
    MemorySortOrder.NEWEST_FIRST -> 0
    MemorySortOrder.OLDEST_FIRST -> 1
    MemorySortOrder.ALPHABETICAL -> 2
}

@Composable
private fun MemoryItem(
    memory: AssistantMemory,
    onEditMemory: (AssistantMemory) -> Unit,
    onDeleteMemory: (AssistantMemory) -> Unit,
    useRagMemoryRetrieval: Boolean = false,
    currentEmbeddingModelId: String = "",
    showType: Boolean = false,
    position: String = "MIDDLE"
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }
    val haptics = rememberPremiumHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
        label = "scale"
    )
    
    val topCorner by animateDpAsState(
        targetValue = when (position) {
            "ONLY", "FIRST" -> 24.dp
            else -> 10.dp
        },
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 200f),
        label = "topCorner"
    )
    val bottomCorner by animateDpAsState(
        targetValue = when (position) {
            "ONLY", "LAST" -> 24.dp
            else -> 10.dp
        },
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 200f),
        label = "bottomCorner"
    )
    
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text(stringResource(R.string.assistant_page_delete)) },
            text = { 
                Text(
                    text = stringResource(R.string.delete_memory_confirmation) + "\n\n\"${memory.content.take(100)}${if (memory.content.length > 100) "..." else ""}\""
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        onDeleteMemory(memory)
                    }
                ) {
                    Text(stringResource(R.string.assistant_page_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text(stringResource(R.string.assistant_page_cancel))
                }
            }
        )
    }
    
    Surface(
        onClick = { onEditMemory(memory) },
        color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(
            topStart = topCorner,
            topEnd = topCorner,
            bottomStart = bottomCorner,
            bottomEnd = bottomCorner
        ),
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Show type and embedding badges only when needed
                val showBadges = memory.pinned || showType || (useRagMemoryRetrieval && !memory.hasEmbedding)
                AnimatedVisibility(
                    visible = showBadges,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (memory.pinned) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    text = stringResource(R.string.assistant_page_memory_pinned_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    color = MaterialTheme.colorScheme.onTertiaryContainer
                                )
                            }
                        }

                        if (showType) {
                            Surface(
                                color = if (memory.type == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    text = if (memory.type == 0) stringResource(R.string.assistant_page_badge_core) else stringResource(R.string.assistant_page_badge_episodic),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    color = if (memory.type == 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                        
                        if (useRagMemoryRetrieval && !memory.hasEmbedding) {
                            Surface(
                                color = Color(0xFFC62828),
                                shape = MaterialTheme.shapes.extraSmall
                            ) {
                                Text(
                                    text = stringResource(R.string.assistant_page_badge_no_embedding),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
                
                Text(
                    text = memory.content,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            IconButton(onClick = {
                haptics.perform(HapticPattern.Pop)
                showDeleteConfirmation = true
            }) {
                Icon(Icons.Rounded.Delete, stringResource(R.string.assistant_page_delete))
            }
        }
    }
}

@Composable
private fun MemoryDebugger(
    onTestRetrieval: (String) -> Unit,
    retrievalResults: List<Pair<AssistantMemory, Float>>
) {
    val (query, setQuery) = remember { mutableStateOf("") }

    Surface(
        color = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.assistant_page_debugger_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = query,
                    onValueChange = setQuery,
                    placeholder = { Text(stringResource(R.string.assistant_page_debugger_query_placeholder)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
                Button(
                    onClick = { onTestRetrieval(query) },
                    enabled = query.isNotBlank()
                ) {
                    Text(stringResource(R.string.assistant_page_debugger_test_button))
                }
            }

            AnimatedVisibility(
                visible = retrievalResults.isNotEmpty(),
                enter = fadeIn() + expandVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.assistant_page_debugger_results_format, retrievalResults.size),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    retrievalResults.forEachIndexed { index, (memory, score) ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHighest,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("#${index + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    Text(
                                        stringResource(R.string.assistant_page_debugger_score_format, String.format("%.4f", score)),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (score >= 0.5f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = memory.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
