package me.rerere.rikkahub.ui.pages.assistant.detail

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.hooks.heroAnimation
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import me.rerere.rikkahub.data.model.Tag as DataTag

// Sub-routes within assistant detail
private object AssistantDetailRoutes {
    const val HOME = "home"
    const val PROFILE = "profile"
    const val MODEL = "model"
    const val PROMPTS = "prompts"
    const val CONTEXT_MANAGEMENT = "context_management"
    const val LOREBOOKS = "lorebooks"
    const val TOOLS = "tools"
    const val MEMORY = "memory"
    const val UI = "ui"
    const val ADVANCED = "advanced"
}

@Composable
fun AssistantDetailPage(
    id: String,
    startRoute: String? = null,
    initialMemoryTab: Int? = null,
    scrollToMemoryId: Int? = null
) {
    val vm: AssistantDetailVM = koinViewModel(
        parameters = {
            parametersOf(id)
        }
    )

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val mcpServerConfigs by vm.mcpServerConfigs.collectAsStateWithLifecycle()
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val memories by vm.memories.collectAsStateWithLifecycle()
    val memoryStats by vm.memoryStats.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    val tags by vm.tags.collectAsStateWithLifecycle()
    val snackbarMessage by vm.snackbarMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            vm.clearSnackbarMessage()
        }
    }
    
    // Auto-navigate to start route if specified (e.g., for deep linking to memory)
    LaunchedEffect(startRoute) {
        if (startRoute == AssistantDetailRoutes.MEMORY) {
            navController.navigate(AssistantDetailRoutes.MEMORY) {
                popUpTo(AssistantDetailRoutes.HOME) { inclusive = false }
            }
        }
    }

    fun onUpdate(assistant: Assistant) {
        vm.update(assistant)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    // Show title only on sub-pages, not on home - fade in only, instant exit
                    AnimatedVisibility(
                        visible = currentRoute != null && currentRoute != AssistantDetailRoutes.HOME,
                        enter = fadeIn(),
                        exit = fadeOut(animationSpec = tween(0)) // Instant exit to avoid fade artifact
                    ) {
                        Text(
                            text = assistant.name.ifBlank {
                                stringResource(R.string.assistant_page_default_assistant)
                            },
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    BackButton()
                },
            )
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AssistantDetailRoutes.HOME,
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
            enterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300))
            },
            exitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300)
                ) + fadeOut(animationSpec = tween(300))
            },
            popEnterTransition = {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300)
                ) + fadeIn(animationSpec = tween(300))
            },
            popExitTransition = {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300)
                ) + fadeOut(animationSpec = tween(300))
            }
        ) {
            composable(AssistantDetailRoutes.HOME) {
                AssistantDetailHome(
                    assistant = assistant,
                    onNavigateToProfile = { navController.navigate(AssistantDetailRoutes.PROFILE) },
                    onNavigateToModel = { navController.navigate(AssistantDetailRoutes.MODEL) },
                    onNavigateToPrompts = { navController.navigate(AssistantDetailRoutes.PROMPTS) },
                    onNavigateToContextManagement = { navController.navigate(AssistantDetailRoutes.CONTEXT_MANAGEMENT) },
                    onNavigateToTools = { navController.navigate(AssistantDetailRoutes.TOOLS) },
                    onNavigateToMemory = { navController.navigate(AssistantDetailRoutes.MEMORY) },
                    onNavigateToUI = { navController.navigate(AssistantDetailRoutes.UI) },
                    onNavigateToAdvanced = { navController.navigate(AssistantDetailRoutes.ADVANCED) }
                )
            }

            // Profile (Identity, Tags, Appearance)
            composable(AssistantDetailRoutes.PROFILE) {
                AssistantProfileSubPage(
                    assistant = assistant,
                    tags = tags,
                    onUpdate = { onUpdate(it) },
                    vm = vm
                )
            }

            // Model (Chat model, parameters, reasoning)
            composable(AssistantDetailRoutes.MODEL) {
                AssistantModelSubPage(
                    assistant = assistant,
                    providers = providers,
                    onUpdate = { onUpdate(it) },
                    onApplyBackgroundPromptToAll = { vm.applyBackgroundPromptToAll(it) },
                    onApplyConsolidationPromptToAll = { vm.applyConsolidationPromptToAll(it) },
                    onApplyMemorySummaryPromptToAll = { vm.applyMemorySummaryPromptToAll(it) },
                    onApplyContextSummaryPromptToAll = { vm.applyContextSummaryPromptToAll(it) },
                )
            }

            // Prompts
            composable(AssistantDetailRoutes.PROMPTS) {
                AssistantPromptSubPage(
                    assistant = assistant,
                    onUpdate = { onUpdate(it) },
                    vm = vm
                )
            }

            // Context Management
            composable(AssistantDetailRoutes.CONTEXT_MANAGEMENT) {
                AssistantContextManagementSubPage(
                    assistant = assistant,
                    onUpdate = { onUpdate(it) },
                    onNavigateToLorebooks = { navController.navigate(AssistantDetailRoutes.LOREBOOKS) },
                    onNavigateToModels = { navController.navigate(AssistantDetailRoutes.MODEL) }
                )
            }

            // Lorebooks (nested under Context Management)
            composable(AssistantDetailRoutes.LOREBOOKS) {
                AssistantLorebooksSubPage(
                    assistant = assistant,
                    onUpdate = { onUpdate(it) },
                    vm = vm
                )
            }

            // Tools
            composable(AssistantDetailRoutes.TOOLS) {
                AssistantToolsSubPage(
                    assistant = assistant,
                    onUpdate = { onUpdate(it) },
                    vm = vm,
                    mcpServerConfigs = mcpServerConfigs
                )
            }

            // Memory
            composable(AssistantDetailRoutes.MEMORY) {
                val embeddingProgress by vm.embeddingProgress.collectAsStateWithLifecycle()
                val estimatedMemoryCapacity by vm.estimatedMemoryCapacity.collectAsStateWithLifecycle()
                val needsEmbeddingRegeneration by vm.needsEmbeddingRegeneration.collectAsStateWithLifecycle()
                val retrievalResults by vm.retrievalResults.collectAsStateWithLifecycle()
                AssistantMemorySettings(
                    assistant = assistant,
                    memories = memories,
                    memoryStats = memoryStats,
                    onUpdateAssistant = { assistant -> onUpdate(assistant) },
                    onDeleteMemory = { memory -> vm.deleteMemory(memory) },
                    onAddMemory = { memory -> vm.addMemory(memory) },
                    onUpdateMemory = { memory -> vm.updateMemory(memory) },
                    onRegenerateEmbeddings = { vm.regenerateEmbeddings() },
                    embeddingProgress = embeddingProgress,
                    onTestRetrieval = { query -> vm.testRetrieval(query) },
                    retrievalResults = retrievalResults,
                    assistantDetailVM = vm,
                    estimatedMemoryCapacity = estimatedMemoryCapacity,
                    needsEmbeddingRegeneration = needsEmbeddingRegeneration,
                    initialMemoryTab = initialMemoryTab,
                    scrollToMemoryId = scrollToMemoryId
                )
            }

            // UI Customization
            composable(AssistantDetailRoutes.UI) {
                AssistantUISubPage(
                    assistant = assistant,
                    onUpdate = { onUpdate(it) }
                )
            }

            // Advanced
            composable(AssistantDetailRoutes.ADVANCED) {
                AssistantAdvancedSubPage(
                    assistant = assistant,
                    onUpdate = { onUpdate(it) }
                )
            }
        }
    }
}

/**
 * Home screen with header and navigation cards
 */
@Composable
private fun AssistantDetailHome(
    assistant: Assistant,
    onNavigateToProfile: () -> Unit,
    onNavigateToModel: () -> Unit,
    onNavigateToPrompts: () -> Unit,
    onNavigateToContextManagement: () -> Unit,
    onNavigateToTools: () -> Unit,
    onNavigateToMemory: () -> Unit,
    onNavigateToUI: () -> Unit,
    onNavigateToAdvanced: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // ═══════════════════════════════════════════════════════════════════
        // HEADER SECTION - Avatar, Name, System Prompt Preview (2 lines, centered)
        // ═══════════════════════════════════════════════════════════════════
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Hero animation for smooth List ↔ Home transition
            UIAvatar(
                value = assistant.avatar,
                name = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                onUpdate = null, // Read-only in home view
                modifier = Modifier
                    .size(96.dp)
                    .heroAnimation(key = "assistant_avatar_${assistant.id}")
            )
            
            Text(
                text = assistant.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) },
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            
            if (assistant.systemPrompt.isNotBlank()) {
                Text(
                    text = assistant.systemPrompt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ═══════════════════════════════════════════════════════════════════
        // NAVIGATION CARDS - Grouped properly
        // ═══════════════════════════════════════════════════════════════════
        SettingsGroup(title = stringResource(R.string.assistant_page_group_configuration)) {
            NavigationCard(
                icon = Icons.Rounded.Person,
                title = stringResource(R.string.assistant_page_tab_profile),
                description = stringResource(R.string.assistant_page_tab_profile_desc),
                onClick = onNavigateToProfile
            )

            NavigationCard(
                icon = Icons.AutoMirrored.Rounded.Chat,
                title = stringResource(R.string.assistant_page_tab_prompt),
                description = stringResource(R.string.assistant_page_tab_prompt_desc),
                onClick = onNavigateToPrompts
            )

            NavigationCard(
                icon = Icons.Rounded.DataObject,
                title = stringResource(R.string.context_management_title),
                description = stringResource(R.string.context_management_desc),
                onClick = onNavigateToContextManagement
            )

            NavigationCard(
                icon = Icons.Rounded.Psychology,
                title = stringResource(R.string.assistant_page_group_models),
                description = stringResource(R.string.assistant_page_tab_model_desc),
                onClick = onNavigateToModel
            )
        }

        SettingsGroup(title = stringResource(R.string.assistant_page_group_capabilities)) {
            NavigationCard(
                icon = Icons.Rounded.Memory,
                title = stringResource(R.string.assistant_page_tab_memory),
                description = stringResource(R.string.assistant_page_tab_memory_desc),
                onClick = onNavigateToMemory
            )

            NavigationCard(
                icon = Icons.Rounded.Build,
                title = stringResource(R.string.assistant_page_tab_tools_search),
                description = stringResource(R.string.assistant_page_tab_tools_search_desc),
                onClick = onNavigateToTools
            )
        }

        SettingsGroup(title = stringResource(R.string.assistant_page_group_other)) {
            NavigationCard(
                icon = Icons.Rounded.Palette,
                title = stringResource(R.string.assistant_page_tab_ui_customization),
                description = stringResource(R.string.assistant_page_tab_ui_customization_desc),
                onClick = onNavigateToUI
            )

            NavigationCard(
                icon = Icons.Rounded.Tune,
                title = stringResource(R.string.assistant_page_tab_advanced),
                description = stringResource(R.string.assistant_page_tab_advanced_desc),
                onClick = onNavigateToAdvanced
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

/**
 * Navigation card component with proper styling
 */
@Composable
private fun NavigationCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit
) {
    SettingGroupItem(
        title = title,
        subtitle = description,
        icon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        onClick = onClick
    )
}
