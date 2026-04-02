package me.rerere.rikkahub

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath
import me.rerere.rikkahub.ui.components.ui.AppToasterHost
import me.rerere.rikkahub.ui.components.ui.rememberAppToasterState
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import me.rerere.highlight.Highlighter
import me.rerere.highlight.LocalHighlighter
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.ui.TTSController
import me.rerere.rikkahub.ui.context.LocalAnimatedVisibilityScope
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.context.LocalSharedTransitionScope
import me.rerere.rikkahub.ui.context.LocalTTSState
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.readBooleanPreference
import me.rerere.rikkahub.ui.hooks.readStringPreference
import me.rerere.rikkahub.ui.hooks.rememberCustomTtsState
import me.rerere.rikkahub.ui.pages.assistant.AssistantPage
import me.rerere.rikkahub.ui.pages.assistant.detail.AssistantDetailPage
import me.rerere.rikkahub.ui.pages.assistant.groupchat.GroupChatTemplateDetailPage
import me.rerere.rikkahub.ui.pages.assistant.scheduled.AssistantScheduledTaskEditPage
import me.rerere.rikkahub.ui.pages.assistant.scheduled.AssistantScheduledTasksPage
import me.rerere.rikkahub.ui.pages.backup.BackupPage
import me.rerere.rikkahub.ui.pages.chat.ChatPage
import me.rerere.rikkahub.ui.pages.developer.DeveloperPage
import me.rerere.rikkahub.ui.pages.imggen.ImageGenPage
import me.rerere.rikkahub.ui.pages.logs.RequestLogDetailPage
import me.rerere.rikkahub.ui.pages.logs.RequestLogsOverviewPage
import me.rerere.rikkahub.ui.pages.menu.MenuPage
import me.rerere.rikkahub.ui.pages.storage.StorageCategoryPage
import me.rerere.rikkahub.ui.pages.storage.StorageManagerPage
import me.rerere.rikkahub.ui.pages.setting.SettingAboutPage
import me.rerere.rikkahub.ui.pages.setting.SettingAdvancedPage
import me.rerere.rikkahub.ui.pages.setting.SettingDisplayPage

import me.rerere.rikkahub.ui.pages.setting.SettingMcpPage
import me.rerere.rikkahub.ui.pages.setting.SettingModelPage
import me.rerere.rikkahub.ui.pages.setting.SettingPage
import me.rerere.rikkahub.ui.pages.setting.SettingProviderDetailPage
import me.rerere.rikkahub.ui.pages.setting.SettingProviderPage
import me.rerere.rikkahub.ui.pages.setting.SettingSearchPage
import me.rerere.rikkahub.ui.pages.setting.SettingTTSPage
import me.rerere.rikkahub.ui.pages.setting.SettingRpOptimizationsPage
import me.rerere.rikkahub.ui.pages.setting.SettingPromptInjectionsPage
import me.rerere.rikkahub.ui.pages.setting.SettingModesPage
import me.rerere.rikkahub.ui.pages.setting.SettingLorebooksPage
import me.rerere.rikkahub.ui.pages.setting.SettingLorebookDetailPage
import me.rerere.rikkahub.ui.pages.setting.SettingSkillsPage
import me.rerere.rikkahub.ui.pages.setting.SettingScriptsWorkspacePage
import me.rerere.rikkahub.ui.pages.setting.SettingChaquoPypiPage
import me.rerere.rikkahub.ui.pages.setting.SettingChaquoPypiPackagePage
import me.rerere.rikkahub.ui.pages.share.handler.ShareHandlerPage
import me.rerere.rikkahub.ui.pages.translator.TranslatorPage
import me.rerere.rikkahub.ui.pages.webview.WebViewPage
import me.rerere.rikkahub.ui.pages.setting.SettingAndroidIntegrationPage
import me.rerere.rikkahub.ui.pages.setting.SettingFontsPage
import me.rerere.rikkahub.ui.pages.setting.SettingWebPage
import me.rerere.rikkahub.ui.motion.LocalMotionPolicy
import me.rerere.rikkahub.ui.motion.rememberSystemMotionPolicy
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.ui.theme.RikkahubTheme
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koin.android.ext.android.inject
import me.rerere.rikkahub.utils.fileSizeToString
import me.rerere.rikkahub.utils.base64Encode
import me.rerere.rikkahub.data.model.ChatTarget
import me.rerere.rikkahub.utils.navigateToChatPage
import kotlin.uuid.Uuid

private const val TAG = "RouteActivity"
internal const val EXTRA_DIRECT_CHAT_TARGET_TYPE = "direct_chat_target_type"
internal const val EXTRA_DIRECT_CHAT_TARGET_ID = "direct_chat_target_id"
internal const val EXTRA_DIRECT_CHAT_TEXT = "direct_chat_text"
internal const val EXTRA_DIRECT_CHAT_AUTO_SEND = "direct_chat_auto_send"
internal const val DIRECT_CHAT_TARGET_TYPE_ASSISTANT = "assistant"
internal const val DIRECT_CHAT_TARGET_TYPE_GROUP_CHAT = "group_chat"

/**
 * Data class to hold text selection intent data for navigation
 */
data class TextSelectionData(
    val navigateTo: String?,
    val selectedText: String?,
    val aiResponse: String?,
    val userPrompt: String?,
    val translatorInput: String?,
    val translatorOutput: String?,
    val selectionAssistantId: String?
)

data class DirectChatData(
    val targetType: String,
    val targetId: String,
    val text: String,
    val autoSend: Boolean,
)

class RouteActivity : ComponentActivity() {
    private val highlighter by inject<Highlighter>()
    private val okHttpClient by inject<OkHttpClient>()
    private val settingsStore by inject<SettingsStore>()
    private val chatService by inject<me.rerere.rikkahub.service.ChatService>()
    private val conversationRepo by inject<me.rerere.rikkahub.data.repository.ConversationRepository>()
    private var navStack by mutableStateOf<NavHostController?>(null)
    private var pendingAssistantId by mutableStateOf<String?>(null)
    private var pendingTextSelection by mutableStateOf<TextSelectionData?>(null)
    private var pendingConversationId by mutableStateOf<String?>(null)
    private var pendingDirectChat by mutableStateOf<DirectChatData?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        disableNavigationBarContrast()
        super.onCreate(savedInstanceState)
        refreshPendingIntentData(intent)

        // Track app launch and initialize usage stats
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { conversationRepo.initUsageStats() }
                .onFailure { android.util.Log.e(TAG, "initUsageStats failed", it) }
            runCatching { conversationRepo.backfillDailyActivityFromConversationHistoryIfNeeded() }
                .onFailure { android.util.Log.e(TAG, "daily activity backfill failed", it) }
            runCatching { conversationRepo.backfillUsageStatsFromHistoryIfNeeded() }
                .onFailure { android.util.Log.e(TAG, "usage stats backfill failed", it) }
            runCatching { conversationRepo.incrementAppLaunches() }
                .onFailure { android.util.Log.e(TAG, "increment app launches failed", it) }
        }

        setContent {
            val navStack = rememberNavController()
            this.navStack = navStack
            ShareHandler(navStack)
            AssistantShortcutHandler(navStack)
            DirectChatHandler(navStack)
            TextSelectionHandler(navStack)
            NotificationHandler(navStack)
            RikkahubTheme {
                setSingletonImageLoaderFactory { context ->
                    ImageLoader.Builder(context)
                        .crossfade(true)
                        .memoryCache {
                            MemoryCache.Builder()
                                .maxSizePercent(context, 0.25) // Use 25% of app's memory for image cache
                                .build()
                        }
                        .diskCache {
                            DiskCache.Builder()
                                .directory(context.filesDir.resolve("icon_cache").toOkioPath())
                                .maxSizeBytes(50 * 1024 * 1024) // 50 MB persistent disk cache for icons
                                .build()
                        }
                        .components {
                            add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
                            add(SvgDecoder.Factory(scaleToDensity = true))
                        }
                        .build()
                }
                AppRoutes(navStack)
            }
        }
    }

    private fun disableNavigationBarContrast() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }
    
    @Composable
    private fun AssistantShortcutHandler(navBackStack: NavHostController) {
        val assistantIdStr = pendingAssistantId
        LaunchedEffect(assistantIdStr) {
            if (assistantIdStr != null) {
                pendingAssistantId = null
                try {
                    val assistantId = Uuid.parse(assistantIdStr)
                    // Update the selected assistant
                    settingsStore.updateAssistant(assistantId)
                    // Mark as recently used
                    settingsStore.markAssistantUsed(assistantId)
                    // Navigate to a new chat
                    navBackStack.navigate(Screen.Chat(Uuid.random().toString())) {
                        popUpTo(0) { inclusive = true }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    @Composable
    private fun ShareHandler(navBackStack: NavHostController) {
        val shareIntent = remember {
            Intent().apply {
                action = intent?.action
                putExtra(Intent.EXTRA_TEXT, intent?.getStringExtra(Intent.EXTRA_TEXT))
                putExtra(Intent.EXTRA_STREAM, intent?.getStringExtra(Intent.EXTRA_STREAM))
            }
        }

        LaunchedEffect(navBackStack) {
            if (shareIntent.action == Intent.ACTION_SEND) {
                val text = shareIntent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                val imageUri = shareIntent.getStringExtra(Intent.EXTRA_STREAM)
                navBackStack.navigate(Screen.ShareHandler(text, imageUri))
            }
        }
    }

    @Composable
    private fun NotificationHandler(navBackStack: NavHostController) {
        val conversationIdStr = pendingConversationId
        LaunchedEffect(conversationIdStr) {
            if (conversationIdStr != null) {
                pendingConversationId = null
                navBackStack.navigate(Screen.Chat(conversationIdStr))
            }
        }
    }

    @Composable
    private fun DirectChatHandler(navBackStack: NavHostController) {
        val directChatData = pendingDirectChat
        LaunchedEffect(directChatData) {
            val data = directChatData ?: return@LaunchedEffect

            try {
                val settings = settingsStore.settingsFlow.first { !it.init }
                val target = when (data.targetType) {
                    DIRECT_CHAT_TARGET_TYPE_ASSISTANT -> {
                        val assistantId = Uuid.parse(data.targetId)
                        if (settings.assistants.none { it.id == assistantId }) {
                            pendingDirectChat = null
                            return@LaunchedEffect
                        }
                        ChatTarget.Assistant(assistantId)
                    }

                    DIRECT_CHAT_TARGET_TYPE_GROUP_CHAT -> {
                        val templateId = Uuid.parse(data.targetId)
                        if (settings.groupChatTemplates.none { it.id == templateId }) {
                            pendingDirectChat = null
                            return@LaunchedEffect
                        }
                        ChatTarget.GroupChat(templateId)
                    }

                    else -> {
                        pendingDirectChat = null
                        return@LaunchedEffect
                    }
                }

                settingsStore.updateChatTarget(target)
                if (target is ChatTarget.Assistant) {
                    settingsStore.markAssistantUsed(target.assistantId)
                }

                if (data.autoSend) {
                    val conversationId = Uuid.random()
                    val initialized = chatService.initializeConversation(conversationId)
                    if (!initialized) return@LaunchedEffect

                    chatService.sendMessage(
                        conversationId = conversationId,
                        content = listOf(me.rerere.ai.ui.UIMessagePart.Text(data.text)),
                    )

                    navigateToChatPage(
                        navController = navBackStack,
                        chatId = conversationId,
                    )
                } else {
                    navigateToChatPage(
                        navController = navBackStack,
                        initText = data.text.base64Encode(),
                        autoSend = false,
                    )
                }
                pendingDirectChat = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @Composable
    private fun TextSelectionHandler(navBackStack: NavHostController) {
        val data = pendingTextSelection
        val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
        
        
        LaunchedEffect(data) {
            if (data != null) {
                pendingTextSelection = null
                try {
                    when (data.navigateTo) {
                        "translator" -> {
                            // Navigate to Translator page
                            navBackStack.navigate(Screen.Translator)
                        }
                        else -> {
                            // Create a new conversation with pre-existing messages
                            val conversationId = Uuid.random()
                            
                            // Create user message with selected text
                            val userContent = buildString {
                                if (!data.selectedText.isNullOrBlank()) {
                                    append(data.selectedText)
                                }
                                if (!data.userPrompt.isNullOrBlank()) {
                                    append("\n\n")
                                    append(data.userPrompt)
                                }
                            }
                            
                            val messages = mutableListOf<me.rerere.rikkahub.data.model.MessageNode>()
                            
                            // Add user message if there's content
                            if (userContent.isNotBlank()) {
                                val userMessage = me.rerere.ai.ui.UIMessage.user(userContent.trim())
                                messages.add(me.rerere.rikkahub.data.model.MessageNode.of(userMessage))
                            }
                            
                            // Add AI response message if available
                            if (!data.aiResponse.isNullOrBlank()) {
                                val assistantMessage = me.rerere.ai.ui.UIMessage.assistant(data.aiResponse)
                                messages.add(me.rerere.rikkahub.data.model.MessageNode.of(assistantMessage))
                            }
                            
                            if (messages.isNotEmpty()) {
                                // Use the assistant from text selection config if available
                                val assistantId = data.selectionAssistantId?.let { 
                                    Uuid.parse(it) 
                                } ?: settings.assistantId
                                
                                // Create the conversation with messages
                                val conversation = me.rerere.rikkahub.data.model.Conversation.ofId(
                                    id = conversationId,
                                    assistantId = assistantId,
                                    messages = messages
                                )
                                
                                // Save to database
                                chatService.saveConversation(conversationId, conversation)
                                
                                // Navigate to the conversation
                                navBackStack.navigate(Screen.Chat(id = conversationId.toString()))
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refreshPendingIntentData(intent)
    }

    private fun refreshPendingIntentData(intent: Intent?) {
        pendingAssistantId = intent?.getStringExtra("assistantId")
        pendingConversationId = intent?.getStringExtra("conversationId")

        val navigateTo = intent?.getStringExtra("navigate_to")
        val continueConversation = intent?.getBooleanExtra("continue_conversation", false) ?: false
        if (navigateTo == "translator" || continueConversation) {
            pendingTextSelection = TextSelectionData(
                navigateTo = navigateTo,
                selectedText = intent?.getStringExtra("selected_text"),
                aiResponse = intent?.getStringExtra("ai_response"),
                userPrompt = intent?.getStringExtra("user_prompt"),
                translatorInput = intent?.getStringExtra("translator_input"),
                translatorOutput = intent?.getStringExtra("translator_output"),
                selectionAssistantId = intent?.getStringExtra("selection_assistant_id")
            )
        }

        val targetType = intent?.getStringExtra(EXTRA_DIRECT_CHAT_TARGET_TYPE)
        val targetId = intent?.getStringExtra(EXTRA_DIRECT_CHAT_TARGET_ID)
        val text = intent?.getStringExtra(EXTRA_DIRECT_CHAT_TEXT)
        if (!targetType.isNullOrBlank() && !targetId.isNullOrBlank() && !text.isNullOrBlank()) {
            pendingDirectChat = DirectChatData(
                targetType = targetType,
                targetId = targetId,
                text = text,
                autoSend = intent.getBooleanExtra(EXTRA_DIRECT_CHAT_AUTO_SEND, false),
            )
        }
    }

    @Composable
    fun AppRoutes(navBackStack: NavHostController) {
        val toastState = rememberAppToasterState()
        val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
        val tts = rememberCustomTtsState()
        val motionPolicy = rememberSystemMotionPolicy()
        SharedTransitionLayout {
            CompositionLocalProvider(
                LocalNavController provides navBackStack,
                LocalSharedTransitionScope provides this,
                LocalSettings provides settings,
                LocalHighlighter provides highlighter,
                LocalToaster provides toastState,
                LocalTTSState provides tts,
                LocalMotionPolicy provides motionPolicy,
            ) {
                // Check for backup cleanup results and show toast
                LaunchedEffect(Unit) {
                    val prefs = this@RouteActivity.getSharedPreferences("backup_cleanup", MODE_PRIVATE)
                    val unsupportedBytes = prefs.getLong("unsupported_bytes", 0)
                    val issuesFixed = prefs.getInt("issues_fixed", 0)
                    val skippedRows = prefs.getInt("db_skipped_rows", 0)
                    
                    if (unsupportedBytes > 0 || issuesFixed > 0 || skippedRows > 0) {
                        // Clear the stored values
                        prefs.edit().clear().apply()
                        
                        // Build cleanup message
                        val parts = mutableListOf<String>()
                        if (unsupportedBytes > 0) {
                            parts.add("${unsupportedBytes.fileSizeToString()} of unsupported data")
                        }
                        if (issuesFixed > 0) {
                            parts.add("$issuesFixed invalid references")
                        }
                        if (skippedRows > 0) {
                            parts.add("$skippedRows corrupt items removed")
                        }
                        
                        val message = "Import completed: ${parts.joinToString(", ")}"
                        toastState.show(message, type = me.rerere.rikkahub.ui.components.ui.ToastType.Info)
                    }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                TTSController()
                NavHost(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    startDestination = Screen.Chat(
                        id = if (readBooleanPreference("create_new_conversation_on_start", true)) {
                            Uuid.random().toString()
                        } else {
                            readStringPreference(
                                "lastConversationId",
                                Uuid.random().toString()
                            ) ?: Uuid.random().toString()
                        }
                    ),
                    navController = navBackStack,
                    enterTransition = { 
                        slideInHorizontally(
                            animationSpec = tween(200, easing = FastOutSlowInEasing)
                        ) { it / 2 } + fadeIn(animationSpec = tween(150))
                    },
                    exitTransition = { 
                        slideOutHorizontally(
                            animationSpec = tween(200, easing = FastOutSlowInEasing)
                        ) { -it / 4 } + fadeOut(animationSpec = tween(100))
                    },
                    popEnterTransition = {
                        slideInHorizontally(
                            animationSpec = tween(200, easing = FastOutSlowInEasing)
                        ) { -it / 4 } + fadeIn(animationSpec = tween(150))
                    },
                    popExitTransition = {
                        slideOutHorizontally(
                            animationSpec = tween(200, easing = FastOutSlowInEasing)
                        ) { it / 2 } + fadeOut(animationSpec = tween(100))
                    }
                ) {
                    composable<Screen.Chat>(
                        enterTransition = { fadeIn() },
                        exitTransition = { fadeOut() },
                    ) { backStackEntry ->
                        val route = backStackEntry.toRoute<Screen.Chat>()
                        ChatPage(
                            id = Uuid.parse(route.id),
                            text = route.text,
                            files = route.files.map { it.toUri() },
                            searchQuery = route.searchQuery,
                            autoSend = route.autoSend,
                        )
                    }

                    composable<Screen.ShareHandler> { backStackEntry ->
                        val route = backStackEntry.toRoute<Screen.ShareHandler>()
                        ShareHandlerPage(
                            text = route.text,
                            image = route.streamUri
                        )
                    }



                    // All assistant-related routes share the same AnimatedVisibilityScope
                    // for seamless hero animations across all screens
                    composable<Screen.Assistant> {
                        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                            AssistantPage()
                        }
                    }

                    composable<Screen.AssistantDetail> { backStackEntry ->
                        val route = backStackEntry.toRoute<Screen.AssistantDetail>()
                        CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
                            AssistantDetailPage(
                                id = route.id,
                                startRoute = route.startRoute,
                                initialMemoryTab = route.initialMemoryTab,
                                scrollToMemoryId = route.scrollToMemoryId
                            )
                        }
                    }

                    composable<Screen.GroupChatTemplateDetail> { backStackEntry ->
                        val route = backStackEntry.toRoute<Screen.GroupChatTemplateDetail>()
                        GroupChatTemplateDetailPage(id = route.id)
                    }

                    composable<Screen.AssistantScheduledTasks> { backStackEntry ->
                        val route = backStackEntry.toRoute<Screen.AssistantScheduledTasks>()
                        AssistantScheduledTasksPage(assistantId = route.assistantId)
                    }

                    composable<Screen.AssistantScheduledTaskEdit> { backStackEntry ->
                        val route = backStackEntry.toRoute<Screen.AssistantScheduledTaskEdit>()
                        AssistantScheduledTaskEditPage(
                            assistantId = route.assistantId,
                            taskId = route.taskId,
                        )
                    }

                    composable<Screen.Menu> {
                        MenuPage()
                    }

                    composable<Screen.Translator> {
                        TranslatorPage()
                    }

                    composable<Screen.Setting> {
                        SettingPage()
                    }

                    composable<Screen.StorageManager> {
                        StorageManagerPage()
                    }

                    composable<Screen.StorageCategory> { backStackEntry ->
                        val route = backStackEntry.toRoute<Screen.StorageCategory>()
                        StorageCategoryPage(category = route.category)
                    }

                    composable<Screen.Backup> {
                        BackupPage()
                    }

                    composable<Screen.ImageGen> {
                        ImageGenPage()
                    }

                    composable<Screen.WebView> { backStackEntry ->
                        val route = backStackEntry.toRoute<Screen.WebView>()
                        WebViewPage(route.url, route.content)
                    }

                    composable<Screen.SettingDisplay> {
                        SettingDisplayPage()
                    }

                    composable<Screen.SettingAdvanced> {
                        SettingAdvancedPage()
                    }

                    composable<Screen.SettingProvider> {
                        SettingProviderPage()
                    }

                    composable<Screen.SettingProviderDetail> {
                        val route = it.toRoute<Screen.SettingProviderDetail>()
                        val id = Uuid.parse(route.providerId)
                        SettingProviderDetailPage(id = id)
                    }

                    composable<Screen.SettingModels> {
                        SettingModelPage()
                    }

                    composable<Screen.SettingAbout> {
                        SettingAboutPage()
                    }

                    composable<Screen.SettingSearch> {
                        SettingSearchPage()
                    }

                    composable<Screen.SettingTTS> {
                        SettingTTSPage()
                    }

                    composable<Screen.SettingMcp> {
                        SettingMcpPage()
                    }

                    composable<Screen.SettingSkills> {
                        SettingSkillsPage()
                    }

                    composable<Screen.SettingScriptsWorkspace> {
                        SettingScriptsWorkspacePage()
                    }

                    composable<Screen.SettingChaquoPypi> {
                        SettingChaquoPypiPage()
                    }

                    composable<Screen.SettingChaquoPypiPackage> { backStackEntry ->
                        val route = backStackEntry.toRoute<Screen.SettingChaquoPypiPackage>()
                        SettingChaquoPypiPackagePage(packageName = route.packageName)
                    }

                    composable<Screen.SettingRpOptimizations> {
                        SettingRpOptimizationsPage()
                    }

                    composable<Screen.SettingPromptInjections> {
                        SettingPromptInjectionsPage()
                    }

                    composable<Screen.SettingModes> { backStackEntry ->
                        val route = backStackEntry.toRoute<Screen.SettingModes>()
                        SettingModesPage(scrollToModeId = route.scrollToModeId)
                    }

                    composable<Screen.SettingLorebooks> {
                        SettingLorebooksPage()
                    }

                    composable<Screen.SettingLorebookDetail> { backStackEntry ->
                        val route = backStackEntry.toRoute<Screen.SettingLorebookDetail>()
                        SettingLorebookDetailPage(id = route.id, scrollToEntryId = route.scrollToEntryId)
                    }

                    composable<Screen.RequestLogs> {
                        RequestLogsOverviewPage()
                    }

                    composable<Screen.RequestLogDetail> { backStackEntry ->
                        val route = backStackEntry.toRoute<Screen.RequestLogDetail>()
                        RequestLogDetailPage(id = route.id)
                    }

                    composable<Screen.Developer> {
                        DeveloperPage()
                    }

                    composable<Screen.SettingAndroidIntegration> {
                        SettingAndroidIntegrationPage()
                    }

                    composable<Screen.SettingFonts> {
                        SettingFontsPage()
                    }

                    composable<Screen.SettingWeb> {
                        SettingWebPage()
                    }

                }
                // Toast host must be last so it renders on top of all content
                AppToasterHost(state = toastState)
                }
            }
        }
    }
}

sealed interface Screen {
    @Serializable
    data class Chat(
        val id: String,
        val text: String? = null,
        val files: List<String> = emptyList(),
        val searchQuery: String? = null,
        val autoSend: Boolean = false,
    ) : Screen

    @Serializable
    data class ShareHandler(val text: String, val streamUri: String? = null) : Screen


    @Serializable
    data object Assistant : Screen

    @Serializable
    data class AssistantDetail(
        val id: String,
        val startRoute: String? = null,  // Navigate directly to a sub-route (e.g., "memory")
        val initialMemoryTab: Int? = null,  // 0 = Core, 1 = Episodic
        val scrollToMemoryId: Int? = null  // Memory ID to scroll to
    ) : Screen

    @Serializable
    data class GroupChatTemplateDetail(val id: String) : Screen

    @Serializable
    data class AssistantScheduledTasks(val assistantId: String) : Screen

    @Serializable
    data class AssistantScheduledTaskEdit(val assistantId: String, val taskId: String? = null) : Screen

    @Serializable
    data object Menu : Screen

    @Serializable
    data object Translator : Screen

    @Serializable
    data object Setting : Screen

    @Serializable
    data object StorageManager : Screen

    @Serializable
    data class StorageCategory(val category: String) : Screen

    @Serializable
    data object Backup : Screen

    @Serializable
    data object ImageGen : Screen

    @Serializable
    data class WebView(val url: String = "", val content: String = "") : Screen

    @Serializable
    data object SettingDisplay : Screen

    @Serializable
    data object SettingAdvanced : Screen

    @Serializable
    data object SettingProvider : Screen

    @Serializable
    data class SettingProviderDetail(val providerId: String) : Screen

    @Serializable
    data object SettingModels : Screen

    @Serializable
    data object SettingAbout : Screen

    @Serializable
    data object SettingSearch : Screen

    @Serializable
    data object SettingTTS : Screen

    @Serializable
    data object SettingMcp : Screen

    @Serializable
    data object SettingSkills : Screen

    @Serializable
    data object SettingScriptsWorkspace : Screen

    @Serializable
    data object SettingChaquoPypi : Screen

    @Serializable
    data class SettingChaquoPypiPackage(val packageName: String) : Screen

    @Serializable
    data object SettingRpOptimizations : Screen

    @Serializable
    data object SettingPromptInjections : Screen

    @Serializable
    data class SettingModes(val scrollToModeId: String? = null) : Screen

    @Serializable
    data object SettingLorebooks : Screen

    @Serializable
    data class SettingLorebookDetail(val id: String, val scrollToEntryId: String? = null) : Screen

    @Serializable
    data object RequestLogs : Screen

    @Serializable
    data class RequestLogDetail(val id: Long) : Screen

    @Serializable
    data object Developer : Screen

    @Serializable
    data object SettingAndroidIntegration : Screen

    @Serializable
    data object SettingFonts : Screen

    @Serializable
    data object SettingWeb : Screen

}
