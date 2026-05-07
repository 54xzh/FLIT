package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import androidx.datastore.core.IOException
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import me.rerere.rikkahub.R
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.normalizeProviderApiKeys
import me.rerere.ai.provider.syncEnabledApiKeysToLegacyField
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_LEARNING_MODE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_MODEL_NAME_GENERATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV2Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV3Migration
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.AssistantSearchMode
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.ChatTarget
import me.rerere.rikkahub.data.model.GroupChatTemplate
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.Mode
import me.rerere.rikkahub.data.model.Skill
import me.rerere.rikkahub.data.model.SkillFolder
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.model.TextSelectionAction
import me.rerere.rikkahub.data.model.TextSelectionConfig
import me.rerere.rikkahub.data.model.DEFAULT_TEXT_SELECTION_ACTIONS
import me.rerere.rikkahub.data.model.ToolResultHistoryMode
import me.rerere.rikkahub.data.model.ensureSeatInstanceNumbers
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.SkillScriptPathUtils
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.utils.toMutableStateFlow
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

private const val TAG = "PreferencesStore"
private const val DEFAULT_CONTEXT_HISTORY_LIMIT = 10

private fun decodeDisplaySettingCompat(raw: String?): DisplaySetting {
    if (raw.isNullOrBlank()) return DisplaySetting()

    val decoded = runCatching { JsonInstant.decodeFromString<DisplaySetting>(raw) }
        .getOrElse { return DisplaySetting() }

    val legacyKeepAll = runCatching {
        (JsonInstant.parseToJsonElement(raw) as? JsonObject)
            ?.get("toolResultKeepAll")
            ?.jsonPrimitiveOrNull
            ?.booleanOrNull
    }.getOrNull()

    if (legacyKeepAll == true) {
        return decoded.copy(toolResultHistoryMode = ToolResultHistoryMode.KEEP_ALL)
    }

    if (legacyKeepAll == false && decoded.toolResultHistoryMode == ToolResultHistoryMode.KEEP_ALL) {
        return decoded.copy(toolResultHistoryMode = ToolResultHistoryMode.RAG)
    }

    return decoded
}

private fun Assistant.normalizeContextManagementFlags(
    applyLegacyHistoryLimitMigration: Boolean = false,
): Assistant {
    var normalized = this

    // Dynamic pruning and auto-summarize are mutually exclusive.
    if (normalized.enableHistorySummarization && normalized.autoRegenerateSummary) {
        normalized = normalized.copy(autoRegenerateSummary = false)
    }

    val hasHistoryLimit = (normalized.maxHistoryMessages ?: 0) > 0
    if (
        applyLegacyHistoryLimitMigration &&
        hasHistoryLimit &&
        !normalized.enableHistorySummarization &&
        !normalized.autoRegenerateSummary
    ) {
        // Old versions used maxHistoryMessages as a hard cap.
        // Migrate these users to dynamic pruning to preserve behavior.
        normalized = normalized.copy(enableHistorySummarization = true)
    }

    if (
        normalized.maxHistoryMessages == null &&
        (normalized.enableHistorySummarization || normalized.autoRegenerateSummary)
    ) {
        normalized = normalized.copy(maxHistoryMessages = DEFAULT_CONTEXT_HISTORY_LIMIT)
    }

    return normalized
}

private val Context.settingsStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { context ->
        listOf(
            PreferenceStoreV1Migration(),
            PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration(),
        )
    }
)

class SettingsStore(
    private val context: Context,
    scope: AppScope,
) : KoinComponent {
    companion object {
        // 版本号
        val VERSION = intPreferencesKey("data_version")

        // UI设置
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_ID = stringPreferencesKey("theme_id")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        val LIVE_UPDATE_DEFAULT_APPLIED = booleanPreferencesKey("live_update_default_applied")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")
        val SHOW_MARKDOWN_FONT_DEBUG_INFO = booleanPreferencesKey("show_markdown_font_debug_info")
        val AUTO_CONTINUE_ON_TRUNCATION = booleanPreferencesKey("auto_continue_on_truncation")
        val ENABLE_RAG_LOGGING = booleanPreferencesKey("enable_rag_logging")

        // 模型选择
        val ENABLE_WEB_SEARCH = booleanPreferencesKey("enable_web_search")
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val TITLE_MODEL = stringPreferencesKey("title_model")
        val MODEL_NAME_GENERATION_MODEL = stringPreferencesKey("model_name_generation_model")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val SUGGESTION_MODEL = stringPreferencesKey("suggestion_model")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val MODEL_NAME_GENERATION_PROMPT = stringPreferencesKey("model_name_generation_prompt")
        val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        val LEARNING_MODE_PROMPT = stringPreferencesKey("learning_mode_prompt")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")
        val EMBEDDING_MODEL = stringPreferencesKey("embedding_model")

        // 提供商
        val PROVIDERS = stringPreferencesKey("providers")

        // 助手
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val CHAT_TARGET = stringPreferencesKey("chat_target")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")
        val PROVIDER_TAGS = stringPreferencesKey("provider_tags")
        val RECENTLY_USED_ASSISTANTS = stringPreferencesKey("recently_used_assistants")
        val GROUP_CHAT_TEMPLATES = stringPreferencesKey("group_chat_templates")

        // 搜索
        val SEARCH_SERVICES = stringPreferencesKey("search_services")
        val SEARCH_COMMON = stringPreferencesKey("search_common")
        val SEARCH_SELECTED = intPreferencesKey("search_selected")

        // MCP
        val MCP_SERVERS = stringPreferencesKey("mcp_servers")
        val MCP_TOOL_CALL_TIMEOUT_SECONDS = intPreferencesKey("mcp_tool_call_timeout_seconds")
        val HTTP_429_MAX_RETRIES = intPreferencesKey("http_429_max_retries")

        // WebDAV
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")

        // Object Storage (S3 Compatible)
        val OBJECT_STORAGE_CONFIG = stringPreferencesKey("object_storage_config")

        // TTS
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")

        // Web Server
        val WEB_SERVER_ENABLED = booleanPreferencesKey("web_server_enabled")
        val WEB_SERVER_PORT = intPreferencesKey("web_server_port")
        val WEB_SERVER_JWT_ENABLED = booleanPreferencesKey("web_server_jwt_enabled")
        val WEB_SERVER_ACCESS_PASSWORD = stringPreferencesKey("web_server_access_password")
        val WEB_SERVER_BACKGROUND_SETUP_SHOWN = booleanPreferencesKey("web_server_background_setup_shown")

        // Background Worker
        val CONSOLIDATION_WORKER_INTERVAL = intPreferencesKey("consolidation_worker_interval")
        val CONSOLIDATION_REQUIRES_DEVICE_IDLE = booleanPreferencesKey("consolidation_requires_device_idle")

        // Prompt Injections
        val MODES = stringPreferencesKey("modes")
        val LOREBOOKS = stringPreferencesKey("lorebooks")
        val CUSTOM_TOOL_SYSTEM_PROMPTS = stringPreferencesKey("custom_tool_system_prompts")

        // Skills
        val SKILLS = stringPreferencesKey("skills")
        val SKILL_FOLDERS = stringPreferencesKey("skill_folders")
        val ENABLE_SKILL_SCRIPT_EXECUTION = booleanPreferencesKey("enable_skill_script_execution")
        val ENABLED_SKILL_SCRIPT_IDS = stringPreferencesKey("enabled_skill_script_ids")
        val WORKSPACE_FILE_TOOLS_ALLOW_ALL = booleanPreferencesKey("workspace_file_tools_allow_all")
        val WORKSPACE_ROOT_TREE_URI = stringPreferencesKey("workspace_root_tree_uri")
        val CONVERSATION_WORKSPACE_ROOTS = stringPreferencesKey("conversation_workspace_roots")
        val CONVERSATION_WORK_DIRS = stringPreferencesKey("conversation_work_dirs")
        val REMEMBER_LAST_WORKSPACE_FOR_NEW_CHATS =
            booleanPreferencesKey("remember_last_workspace_for_new_chats")
        val REMEMBERED_WORKSPACE_FOR_NEW_CHATS =
            stringPreferencesKey("remembered_workspace_for_new_chats")
        val CONVERSATION_READ_POSITIONS = stringPreferencesKey("conversation_read_positions")
        val CONVERSATION_LARGE_CONTEXT_WARNING_SHOWN_AT =
            stringPreferencesKey("conversation_large_context_warning_shown_at")

        // Android Integration
        val TEXT_SELECTION_CONFIG = stringPreferencesKey("text_selection_config")
        val TEXT_SELECTION_LOCALIZED_DEFAULT_APPLIED =
            booleanPreferencesKey("text_selection_localized_default_applied")
    }

    private val dataStore = context.settingsStore

    private fun normalizeTextSelectionTemplate(text: String): String {
        return text.replace("\r\n", "\n").trim()
    }

    private fun isLegacyDefaultTextSelectionActions(actions: List<TextSelectionAction>): Boolean {
        val currentById = actions.associateBy { it.id }
        val defaultById = DEFAULT_TEXT_SELECTION_ACTIONS.associateBy { it.id }
        val ids = listOf("translate", "explain", "summarize", "custom")

        if (ids.any { currentById[it] == null || defaultById[it] == null }) return false

        return ids.all { id ->
            val current = currentById.getValue(id)
            val default = defaultById.getValue(id)
            current.enabled == default.enabled &&
                current.icon == default.icon &&
                current.isCustomPrompt == default.isCustomPrompt &&
                normalizeTextSelectionTemplate(current.name) == normalizeTextSelectionTemplate(default.name) &&
                normalizeTextSelectionTemplate(current.prompt) == normalizeTextSelectionTemplate(default.prompt)
        }
    }

    private fun buildLocalizedDefaultTextSelectionActions(): List<TextSelectionAction> {
        return listOf(
            TextSelectionAction(
                id = "translate",
                name = context.getString(R.string.text_selection_translate),
                icon = "Translate",
                prompt = context.getString(R.string.text_selection_prompt_translate).trim(),
            ),
            TextSelectionAction(
                id = "explain",
                name = context.getString(R.string.text_selection_explain),
                icon = "Lightbulb",
                prompt = context.getString(R.string.text_selection_prompt_explain).trim(),
            ),
            TextSelectionAction(
                id = "summarize",
                name = context.getString(R.string.text_selection_summarize),
                icon = "Summarize",
                prompt = context.getString(R.string.text_selection_prompt_summarize).trim(),
            ),
            TextSelectionAction(
                id = "custom",
                name = context.getString(R.string.text_selection_ask),
                icon = "AutoAwesome",
                prompt = context.getString(R.string.text_selection_prompt_custom).trim(),
                isCustomPrompt = true,
            ),
        )
    }

    init {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val prefs = dataStore.data.first()
                if (prefs[LIVE_UPDATE_DEFAULT_APPLIED] == true) return@launch

                val rawDisplaySetting = prefs[DISPLAY_SETTING]
                val alreadyPersisted = rawDisplaySetting?.contains("\"enableLiveUpdate\"") == true
                if (alreadyPersisted) {
                    dataStore.edit { it[LIVE_UPDATE_DEFAULT_APPLIED] = true }
                    return@launch
                }

                val currentDisplaySetting = decodeDisplaySettingCompat(rawDisplaySetting)

                dataStore.edit { preferences ->
                    preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(
                        currentDisplaySetting.copy(enableLiveUpdate = true)
                    )
                    preferences[LIVE_UPDATE_DEFAULT_APPLIED] = true
                }
            }.onFailure {
                Log.w(TAG, "applyLiveUpdateDefaultIfNeeded failed: ${it.message}", it)
            }
        }

        // Localize default Text Selection actions (only if user hasn't customized them).
        scope.launch(Dispatchers.IO) {
            runCatching {
                val prefs = dataStore.data.first()
                if (prefs[TEXT_SELECTION_LOCALIZED_DEFAULT_APPLIED] == true) return@launch

                val localizedDefaults = buildLocalizedDefaultTextSelectionActions()
                val rawConfig = prefs[TEXT_SELECTION_CONFIG]
                val currentConfig = rawConfig?.let {
                    runCatching { JsonInstant.decodeFromString<TextSelectionConfig>(it) }.getOrNull()
                }

                val updatedConfig = when {
                    currentConfig == null -> TextSelectionConfig(actions = localizedDefaults)
                    isLegacyDefaultTextSelectionActions(currentConfig.actions) ->
                        currentConfig.copy(actions = localizedDefaults)

                    else -> null
                }

                dataStore.edit { preferences ->
                    if (updatedConfig != null) {
                        preferences[TEXT_SELECTION_CONFIG] = JsonInstant.encodeToString(updatedConfig)
                    }
                    preferences[TEXT_SELECTION_LOCALIZED_DEFAULT_APPLIED] = true
                }
            }.onFailure {
                Log.w(TAG, "localizeTextSelectionDefaultsIfNeeded failed: ${it.message}", it)
            }
        }

        // Migrate legacy "keep alive: always" to the closest remaining behavior (during generation).
        scope.launch(Dispatchers.IO) {
            runCatching {
                val prefs = dataStore.data.first()
                val rawDisplaySetting = prefs[DISPLAY_SETTING] ?: return@launch
                val currentDisplaySetting = decodeDisplaySettingCompat(rawDisplaySetting)

                val shouldMigrate = currentDisplaySetting.enableKeepAliveNotification &&
                    currentDisplaySetting.keepAliveMode == KeepAliveMode.ALWAYS &&
                    !currentDisplaySetting.enableLiveUpdate
                if (!shouldMigrate) return@launch

                dataStore.edit { preferences ->
                    preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(
                        currentDisplaySetting.copy(keepAliveMode = KeepAliveMode.GENERATION)
                    )
                }
            }.onFailure {
                Log.w(TAG, "migrateKeepAliveAlwaysToGeneration failed: ${it.message}", it)
            }
        }
    }

    val settingsFlowRaw = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }.map { preferences ->
            val assistantId = preferences[SELECT_ASSISTANT]?.let { Uuid.parse(it) }
                ?: DEFAULT_ASSISTANT_ID
            Settings(
                enableWebSearch = preferences[ENABLE_WEB_SEARCH] == true,
                favoriteModels = preferences[FAVORITE_MODELS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                chatModelId = preferences[SELECT_MODEL]?.let { Uuid.parse(it) }
                    ?: GEMINI_2_5_FLASH_ID,
                titleModelId = preferences[TITLE_MODEL]?.let { Uuid.parse(it) }
                    ?: GEMINI_2_5_FLASH_ID,
                modelNameGenerationModelId = preferences[MODEL_NAME_GENERATION_MODEL]?.let { Uuid.parse(it) }
                    ?: GEMINI_2_5_FLASH_ID,
                translateModeId = preferences[TRANSLATE_MODEL]?.let { Uuid.parse(it) }
                    ?: GEMINI_2_5_FLASH_ID,
                suggestionModelId = preferences[SUGGESTION_MODEL]?.let { Uuid.parse(it) }
                    ?: GEMINI_2_5_FLASH_ID,
                imageGenerationModelId = preferences[IMAGE_GENERATION_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
                modelNameGenerationPrompt = preferences[MODEL_NAME_GENERATION_PROMPT] ?: DEFAULT_MODEL_NAME_GENERATION_PROMPT,
                translatePrompt = preferences[TRANSLATION_PROMPT] ?: DEFAULT_TRANSLATION_PROMPT,
                suggestionPrompt = preferences[SUGGESTION_PROMPT] ?: DEFAULT_SUGGESTION_PROMPT,
                learningModePrompt = preferences[LEARNING_MODE_PROMPT] ?: DEFAULT_LEARNING_MODE_PROMPT,
                ocrModelId = preferences[OCR_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                ocrPrompt = preferences[OCR_PROMPT] ?: DEFAULT_OCR_PROMPT,
                embeddingModelId = preferences[EMBEDDING_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                assistantId = assistantId,
                chatTarget = preferences[CHAT_TARGET]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<ChatTarget>(raw) }.getOrNull()
                } ?: ChatTarget.Assistant(assistantId),
                assistantTags = preferences[ASSISTANT_TAGS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                providerTags = preferences[PROVIDER_TAGS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                providers = JsonInstant.decodeFromString(preferences[PROVIDERS] ?: "[]"),
                assistants = JsonInstant.decodeFromString(preferences[ASSISTANTS] ?: "[]"),
                recentlyUsedAssistants = preferences[RECENTLY_USED_ASSISTANTS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                groupChatTemplates = preferences[GROUP_CHAT_TEMPLATES]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<List<GroupChatTemplate>>(raw) }.getOrNull()
                } ?: emptyList(),
                dynamicColor = preferences[DYNAMIC_COLOR] != false,
                themeId = preferences[THEME_ID] ?: PresetThemes[0].id,
                developerMode = preferences[DEVELOPER_MODE] == true,
                showMarkdownFontDebugInfo = preferences[SHOW_MARKDOWN_FONT_DEBUG_INFO] != false,
                autoContinueOnTruncation = preferences[AUTO_CONTINUE_ON_TRUNCATION] == true,
                enableRagLogging = preferences[ENABLE_RAG_LOGGING] == true,
                displaySetting = decodeDisplaySettingCompat(preferences[DISPLAY_SETTING]),
                textSelectionConfig = preferences[TEXT_SELECTION_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: TextSelectionConfig(),
                searchServices = preferences[SEARCH_SERVICES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: listOf(SearchServiceOptions.DEFAULT),
                searchCommonOptions = preferences[SEARCH_COMMON]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: SearchCommonOptions(),
                searchServiceSelected = preferences[SEARCH_SELECTED] ?: 0,
                mcpToolCallTimeoutSeconds = (preferences[MCP_TOOL_CALL_TIMEOUT_SECONDS] ?: 60).coerceAtLeast(1),
                http429MaxRetries = (preferences[HTTP_429_MAX_RETRIES] ?: 0).coerceIn(0, 10),
                mcpServers = preferences[MCP_SERVERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                webDavConfig = preferences[WEBDAV_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: WebDavConfig(),
                objectStorageConfig = preferences[OBJECT_STORAGE_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: ObjectStorageConfig(),
                ttsProviders = preferences[TTS_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedTTSProviderId = preferences[SELECTED_TTS_PROVIDER]?.let { Uuid.parse(it) }
                    ?: DEFAULT_SYSTEM_TTS_ID,
                consolidationWorkerIntervalMinutes = preferences[CONSOLIDATION_WORKER_INTERVAL] ?: 15,
                consolidationRequiresDeviceIdle = preferences[CONSOLIDATION_REQUIRES_DEVICE_IDLE] ?: false,
                webServerEnabled = preferences[WEB_SERVER_ENABLED] == true,
                webServerPort = preferences[WEB_SERVER_PORT] ?: 8080,
                webServerJwtEnabled = preferences[WEB_SERVER_JWT_ENABLED] == true,
                webServerAccessPassword = preferences[WEB_SERVER_ACCESS_PASSWORD] ?: "",
                webServerBackgroundSetupShown = preferences[WEB_SERVER_BACKGROUND_SETUP_SHOWN] == true,
                modes = preferences[MODES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                lorebooks = preferences[LOREBOOKS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                customToolSystemPrompts = preferences[CUSTOM_TOOL_SYSTEM_PROMPTS]?.let {
                    runCatching { JsonInstant.decodeFromString<Map<String, String>>(it) }.getOrNull()
                } ?: emptyMap(),
                skillFolders = preferences[SKILL_FOLDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                skills = preferences[SKILLS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                enableSkillScriptExecution = preferences[ENABLE_SKILL_SCRIPT_EXECUTION] == true,
                enabledSkillScriptIds = preferences[ENABLED_SKILL_SCRIPT_IDS]?.let {
                    runCatching { JsonInstant.decodeFromString<Set<Uuid>>(it) }.getOrNull()
                } ?: emptySet(),
                workspaceFileToolsAllowAll = preferences[WORKSPACE_FILE_TOOLS_ALLOW_ALL] == true,
                workspaceRootTreeUri = preferences[WORKSPACE_ROOT_TREE_URI],
                conversationWorkspaceRoots = preferences[CONVERSATION_WORKSPACE_ROOTS]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<Map<String, String>>(raw) }.getOrNull()
                } ?: emptyMap(),
                conversationWorkDirs = preferences[CONVERSATION_WORK_DIRS]?.let {
                    runCatching { JsonInstant.decodeFromString<Map<String, ConversationWorkDirBinding>>(it) }.getOrNull()
                } ?: emptyMap(),
                rememberLastWorkspaceForNewChats = preferences[REMEMBER_LAST_WORKSPACE_FOR_NEW_CHATS] == true,
                rememberedWorkspaceForNewChats = preferences[REMEMBERED_WORKSPACE_FOR_NEW_CHATS]?.let {
                    runCatching { JsonInstant.decodeFromString<RememberedWorkspaceForNewChats>(it) }.getOrNull()
                },
                conversationReadPositions = preferences[CONVERSATION_READ_POSITIONS]?.let {
                    runCatching { JsonInstant.decodeFromString<Map<String, ConversationReadPosition>>(it) }.getOrNull()
                } ?: emptyMap(),
                conversationLargeContextWarningShownAt = preferences[CONVERSATION_LARGE_CONTEXT_WARNING_SHOWN_AT]?.let {
                    runCatching { JsonInstant.decodeFromString<Map<String, Long>>(it) }.getOrNull()
                } ?: emptyMap(),
            )
        }
        .map {
            var providers = it.providers.ifEmpty { DEFAULT_PROVIDERS }.toMutableList()
            // DEFAULT_PROVIDERS.forEach { defaultProvider ->
            //     if (providers.none { it.id == defaultProvider.id }) {
            //         providers.add(defaultProvider.copyProvider())
            //     }
            // }
            providers = providers.map { provider ->
                val defaultProvider = DEFAULT_PROVIDERS.find { it.id == provider.id }
                if (defaultProvider != null) {
                    provider.copyProvider(
                        builtIn = defaultProvider.builtIn,
                        description = defaultProvider.description,
                        shortDescription = defaultProvider.shortDescription,
                    )
                } else provider
            }.toMutableList()
            val assistants = it.assistants.ifEmpty { DEFAULT_ASSISTANTS }.toMutableList()
            val ttsProviders = it.ttsProviders.ifEmpty { DEFAULT_TTS_PROVIDERS }.toMutableList()
            DEFAULT_TTS_PROVIDERS.forEach { defaultTTSProvider ->
                if (ttsProviders.none { provider -> provider.id == defaultTTSProvider.id }) {
                    ttsProviders.add(defaultTTSProvider.copyProvider())
                }
            }
            it.copy(
                providers = providers,
                assistants = assistants,
                ttsProviders = ttsProviders
            )
        }
        .map { settings ->
            // 去重并清理无效引用
            val validMcpServerIds = settings.mcpServers.map { it.id }.toSet()
            val dedupedSkillFolders = settings.skillFolders.distinctBy { it.id }
            val validSkillFolderIds = dedupedSkillFolders.map { it.id }.toSet()
            val sanitizedSkills = settings.skills.map { skill ->
                if (skill.folderId != null && skill.folderId !in validSkillFolderIds) {
                    skill.copy(folderId = null)
                } else {
                    skill
                }
            }
            val validModeIds = settings.modes.map { it.id }.toSet()
            val validSkillIds = sanitizedSkills.map { it.id }.toSet()
            val dedupedAssistants = settings.assistants.distinctBy { it.id }.map { assistant ->
                assistant.copy(
                    mcpServers = assistant.mcpServers.filter { serverId ->
                        serverId in validMcpServerIds
                    }.toSet(),
                    enabledModeIds = assistant.enabledModeIds.filter { modeId ->
                        modeId in validModeIds
                    }.toSet(),
                    enabledSkillIds = assistant.enabledSkillIds.filter { skillId ->
                        skillId in validSkillIds
                    }.toSet()
                ).normalizeContextManagementFlags()
            }
            val validAssistantIds = dedupedAssistants.map { it.id }.toSet()
            val dedupedGroupChats = settings.groupChatTemplates
                .distinctBy { it.id }
                .map { template ->
                    template.copy(
                        seats = template.seats
                            .distinctBy { it.id }
                            .filter { seat -> seat.assistantId in validAssistantIds }
                    ).ensureSeatInstanceNumbers()
                }
            val sanitizedChatTarget = when (val target = settings.chatTarget) {
                is ChatTarget.Assistant -> {
                    val id = target.assistantId
                    if (id in validAssistantIds) target else ChatTarget.Assistant(dedupedAssistants.first().id)
                }

                is ChatTarget.GroupChat -> {
                    val id = target.templateId
                    if (dedupedGroupChats.any { it.id == id }) target else ChatTarget.Assistant(dedupedAssistants.first().id)
                }
            }
            settings.copy(
                providers = settings.providers.distinctBy { it.id }.map { provider ->
                    when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Google -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )

                        is ProviderSetting.Claude -> provider.copy(
                            models = provider.models.distinctBy { model -> model.id }
                        )
                    }.normalizeProviderApiKeys()
                },
                assistants = dedupedAssistants,
                skillFolders = dedupedSkillFolders,
                skills = sanitizedSkills,
                groupChatTemplates = dedupedGroupChats,
                chatTarget = sanitizedChatTarget,
                ttsProviders = settings.ttsProviders.distinctBy { it.id },
                favoriteModels = settings.favoriteModels.filter { uuid ->
                    settings.providers.flatMap { it.models }.any { it.id == uuid }
                }
            )
        }
        .onEach {
            get<PebbleEngine>().templateCache.invalidateAll()
        }
        .flowOn(Dispatchers.Default)

    val settingsFlow = settingsFlowRaw
        .distinctUntilChanged()
        .toMutableStateFlow(scope, Settings.dummy())

	    suspend fun update(settings: Settings) {
	        if(settings.init) {
	            Log.w(TAG, "Cannot update dummy settings")
	            return
	        }

	        val oldSettingsSnapshot = settingsFlow.value
	        
	        // Auto-update recently used assistants when assistant changes
	        val settingsToSave = if (settings.assistantId != oldSettingsSnapshot.assistantId && 
	            !oldSettingsSnapshot.init &&
	            settings.assistants.any { it.id == settings.assistantId }) {
	            val updatedList = buildList {
	                add(settings.assistantId)
	                settings.recentlyUsedAssistants
                    .filter { it != settings.assistantId }
                    .take(2)
                    .forEach { add(it) }
            }
            settings.copy(recentlyUsedAssistants = updatedList)
	        } else {
	            settings
	        }

	        val settingsToSaveWithReboundSearchIndices = if (!oldSettingsSnapshot.init) {
	            val oldSearchServiceIds = oldSettingsSnapshot.searchServices.map { it.id }
	            val newSearchServiceIds = settingsToSave.searchServices.map { it.id }
	            if (oldSearchServiceIds != newSearchServiceIds) {
	                settingsToSave.rebindSearchServiceIndices(oldSearchServices = oldSettingsSnapshot.searchServices)
	            } else {
	                settingsToSave
	            }
	        } else {
	            settingsToSave
	        }

            val normalizedAssistants = settingsToSaveWithReboundSearchIndices.assistants.map { assistant ->
                assistant.normalizeContextManagementFlags()
            }
	        val finalSettingsToSave = settingsToSaveWithReboundSearchIndices.copy(
                assistants = normalizedAssistants,
                providers = settingsToSaveWithReboundSearchIndices.providers.map { provider ->
                    provider.normalizeProviderApiKeys().syncEnabledApiKeysToLegacyField()
                },
	            displaySetting = settingsToSaveWithReboundSearchIndices.displaySetting.coerceForConflicts(),
                mcpToolCallTimeoutSeconds = settingsToSaveWithReboundSearchIndices.mcpToolCallTimeoutSeconds.coerceAtLeast(1),
                http429MaxRetries = settingsToSaveWithReboundSearchIndices.http429MaxRetries.coerceIn(0, 10),
                webServerPort = settingsToSaveWithReboundSearchIndices.webServerPort.coerceIn(1024, 65535),
                webServerJwtEnabled = settingsToSaveWithReboundSearchIndices.webServerJwtEnabled &&
                    settingsToSaveWithReboundSearchIndices.webServerAccessPassword.isNotBlank(),
	        )

        settingsFlow.value = finalSettingsToSave
        dataStore.edit { preferences ->
            preferences[VERSION] = 3
            preferences[DYNAMIC_COLOR] = finalSettingsToSave.dynamicColor
            preferences[THEME_ID] = finalSettingsToSave.themeId
            preferences[DEVELOPER_MODE] = finalSettingsToSave.developerMode
            preferences[SHOW_MARKDOWN_FONT_DEBUG_INFO] = finalSettingsToSave.showMarkdownFontDebugInfo
            preferences[AUTO_CONTINUE_ON_TRUNCATION] = finalSettingsToSave.autoContinueOnTruncation
            preferences[ENABLE_RAG_LOGGING] = finalSettingsToSave.enableRagLogging
            preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(finalSettingsToSave.displaySetting)
            preferences[TEXT_SELECTION_CONFIG] = JsonInstant.encodeToString(finalSettingsToSave.textSelectionConfig)

            preferences[ENABLE_WEB_SEARCH] = finalSettingsToSave.enableWebSearch
            preferences[FAVORITE_MODELS] = JsonInstant.encodeToString(finalSettingsToSave.favoriteModels)
            preferences[SELECT_MODEL] = finalSettingsToSave.chatModelId.toString()
            preferences[TITLE_MODEL] = finalSettingsToSave.titleModelId.toString()
            preferences[MODEL_NAME_GENERATION_MODEL] = finalSettingsToSave.modelNameGenerationModelId.toString()
            preferences[TRANSLATE_MODEL] = finalSettingsToSave.translateModeId.toString()
            preferences[SUGGESTION_MODEL] = finalSettingsToSave.suggestionModelId.toString()
            preferences[IMAGE_GENERATION_MODEL] = finalSettingsToSave.imageGenerationModelId.toString()
            preferences[TITLE_PROMPT] = finalSettingsToSave.titlePrompt
            preferences[MODEL_NAME_GENERATION_PROMPT] = finalSettingsToSave.modelNameGenerationPrompt
            preferences[TRANSLATION_PROMPT] = finalSettingsToSave.translatePrompt
            preferences[SUGGESTION_PROMPT] = finalSettingsToSave.suggestionPrompt
            preferences[LEARNING_MODE_PROMPT] = finalSettingsToSave.learningModePrompt
            preferences[OCR_MODEL] = finalSettingsToSave.ocrModelId.toString()
            preferences[OCR_PROMPT] = finalSettingsToSave.ocrPrompt
            preferences[EMBEDDING_MODEL] = finalSettingsToSave.embeddingModelId.toString()

            preferences[PROVIDERS] = JsonInstant.encodeToString(finalSettingsToSave.providers)

            preferences[ASSISTANTS] = JsonInstant.encodeToString(finalSettingsToSave.assistants)
            preferences[SELECT_ASSISTANT] = finalSettingsToSave.assistantId.toString()
            preferences[CHAT_TARGET] = JsonInstant.encodeToString(finalSettingsToSave.chatTarget)
            preferences[ASSISTANT_TAGS] = JsonInstant.encodeToString(finalSettingsToSave.assistantTags)
            preferences[PROVIDER_TAGS] = JsonInstant.encodeToString(finalSettingsToSave.providerTags)
            preferences[RECENTLY_USED_ASSISTANTS] = JsonInstant.encodeToString(finalSettingsToSave.recentlyUsedAssistants)
            preferences[GROUP_CHAT_TEMPLATES] = JsonInstant.encodeToString(finalSettingsToSave.groupChatTemplates)

            preferences[SEARCH_SERVICES] = JsonInstant.encodeToString(finalSettingsToSave.searchServices)
            preferences[SEARCH_COMMON] = JsonInstant.encodeToString(finalSettingsToSave.searchCommonOptions)
            preferences[SEARCH_SELECTED] = finalSettingsToSave.searchServiceSelected.coerceIn(0, finalSettingsToSave.searchServices.size - 1)

            preferences[MCP_SERVERS] = JsonInstant.encodeToString(finalSettingsToSave.mcpServers)
            preferences[MCP_TOOL_CALL_TIMEOUT_SECONDS] = finalSettingsToSave.mcpToolCallTimeoutSeconds.coerceAtLeast(1)
            preferences[HTTP_429_MAX_RETRIES] = finalSettingsToSave.http429MaxRetries.coerceIn(0, 10)
            preferences[WEBDAV_CONFIG] = JsonInstant.encodeToString(finalSettingsToSave.webDavConfig)
            preferences[OBJECT_STORAGE_CONFIG] = JsonInstant.encodeToString(finalSettingsToSave.objectStorageConfig)
            preferences[TTS_PROVIDERS] = JsonInstant.encodeToString(finalSettingsToSave.ttsProviders)
            finalSettingsToSave.selectedTTSProviderId?.let {
                preferences[SELECTED_TTS_PROVIDER] = it.toString()
            } ?: preferences.remove(SELECTED_TTS_PROVIDER)

            preferences[CONSOLIDATION_WORKER_INTERVAL] = finalSettingsToSave.consolidationWorkerIntervalMinutes
            preferences[CONSOLIDATION_REQUIRES_DEVICE_IDLE] = finalSettingsToSave.consolidationRequiresDeviceIdle

            preferences[WEB_SERVER_ENABLED] = finalSettingsToSave.webServerEnabled
            preferences[WEB_SERVER_PORT] = finalSettingsToSave.webServerPort
            preferences[WEB_SERVER_JWT_ENABLED] = finalSettingsToSave.webServerJwtEnabled
            preferences[WEB_SERVER_ACCESS_PASSWORD] = finalSettingsToSave.webServerAccessPassword
            preferences[WEB_SERVER_BACKGROUND_SETUP_SHOWN] = finalSettingsToSave.webServerBackgroundSetupShown

            preferences[MODES] = JsonInstant.encodeToString(finalSettingsToSave.modes)
            preferences[LOREBOOKS] = JsonInstant.encodeToString(finalSettingsToSave.lorebooks)
            preferences[CUSTOM_TOOL_SYSTEM_PROMPTS] =
                JsonInstant.encodeToString(finalSettingsToSave.customToolSystemPrompts)
            preferences[SKILL_FOLDERS] = JsonInstant.encodeToString(finalSettingsToSave.skillFolders)
            preferences[SKILLS] = JsonInstant.encodeToString(finalSettingsToSave.skills)
            preferences[ENABLE_SKILL_SCRIPT_EXECUTION] = finalSettingsToSave.enableSkillScriptExecution
            preferences[ENABLED_SKILL_SCRIPT_IDS] = JsonInstant.encodeToString(finalSettingsToSave.enabledSkillScriptIds)
            preferences[WORKSPACE_FILE_TOOLS_ALLOW_ALL] = finalSettingsToSave.workspaceFileToolsAllowAll
            finalSettingsToSave.workspaceRootTreeUri?.let {
                preferences[WORKSPACE_ROOT_TREE_URI] = it
            } ?: preferences.remove(WORKSPACE_ROOT_TREE_URI)
            preferences[CONVERSATION_WORKSPACE_ROOTS] =
                JsonInstant.encodeToString(finalSettingsToSave.conversationWorkspaceRoots)
            preferences[CONVERSATION_WORK_DIRS] = JsonInstant.encodeToString(finalSettingsToSave.conversationWorkDirs)
            preferences[REMEMBER_LAST_WORKSPACE_FOR_NEW_CHATS] =
                finalSettingsToSave.rememberLastWorkspaceForNewChats
            finalSettingsToSave.rememberedWorkspaceForNewChats
                ?.takeIf { finalSettingsToSave.rememberLastWorkspaceForNewChats }
                ?.let {
                preferences[REMEMBERED_WORKSPACE_FOR_NEW_CHATS] = JsonInstant.encodeToString(it)
            } ?: preferences.remove(REMEMBERED_WORKSPACE_FOR_NEW_CHATS)
            preferences[CONVERSATION_READ_POSITIONS] =
                JsonInstant.encodeToString(finalSettingsToSave.conversationReadPositions)
            preferences[CONVERSATION_LARGE_CONTEXT_WARNING_SHOWN_AT] =
                JsonInstant.encodeToString(finalSettingsToSave.conversationLargeContextWarningShownAt)
        }
    }

    suspend fun update(fn: (Settings) -> Settings) {
        update(fn(settingsFlow.value))
    }

    suspend fun updateAssistant(assistantId: Uuid) {
        updateChatTarget(ChatTarget.Assistant(assistantId))
    }

    suspend fun updateChatTarget(target: ChatTarget) {
        dataStore.edit { preferences ->
            preferences[CHAT_TARGET] = JsonInstant.encodeToString(target)
            if (target is ChatTarget.Assistant) {
                preferences[SELECT_ASSISTANT] = target.assistantId.toString()
            }
        }
    }

    /**
     * Mark an assistant as recently used for app shortcuts.
     * Moves it to the front of the list and keeps only the 3 most recent.
     */
    suspend fun markAssistantUsed(assistantId: Uuid) {
        val current = settingsFlow.value
        // Only add if the assistant exists
        if (current.assistants.none { it.id == assistantId }) return
        
        val updatedList = buildList {
            add(assistantId)
            current.recentlyUsedAssistants
                .filter { it != assistantId }
                .take(2)
                .forEach { add(it) }
        }
        
        if (updatedList != current.recentlyUsedAssistants) {
            update(current.copy(recentlyUsedAssistants = updatedList))
        }
    }
}

@Serializable
data class Settings(
    @Transient
    val init: Boolean = false,
    val dynamicColor: Boolean = true,
    val themeId: String = PresetThemes[0].id,
    val developerMode: Boolean = false,
    val showMarkdownFontDebugInfo: Boolean = false,
    val autoContinueOnTruncation: Boolean = false,
    val enableRagLogging: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val textSelectionConfig: TextSelectionConfig = TextSelectionConfig(),
    val enableWebSearch: Boolean = false,
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val titleModelId: Uuid = Uuid.random(),
    val modelNameGenerationModelId: Uuid = Uuid.random(),
    val imageGenerationModelId: Uuid = Uuid.random(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
    val modelNameGenerationPrompt: String = DEFAULT_MODEL_NAME_GENERATION_PROMPT,
    val translateModeId: Uuid = Uuid.random(),
    val translatePrompt: String = DEFAULT_TRANSLATION_PROMPT,
    val suggestionModelId: Uuid = Uuid.random(),
    val suggestionPrompt: String = DEFAULT_SUGGESTION_PROMPT,
    val learningModePrompt: String = DEFAULT_LEARNING_MODE_PROMPT,
    val ocrModelId: Uuid = Uuid.random(),
    val ocrPrompt: String = DEFAULT_OCR_PROMPT,
    val embeddingModelId: Uuid = Uuid.random(),
    val assistantId: Uuid = DEFAULT_ASSISTANT_ID,
    val chatTarget: ChatTarget = ChatTarget.Assistant(DEFAULT_ASSISTANT_ID),
    val providers: List<ProviderSetting> = DEFAULT_PROVIDERS,
    val assistants: List<Assistant> = DEFAULT_ASSISTANTS,
    val assistantTags: List<Tag> = emptyList(),
    val providerTags: List<Tag> = emptyList(),
    val recentlyUsedAssistants: List<Uuid> = emptyList(), // For app shortcuts, max 3 items
    val groupChatTemplates: List<GroupChatTemplate> = emptyList(),
    val searchServices: List<SearchServiceOptions> = listOf(SearchServiceOptions.DEFAULT),
    val searchCommonOptions: SearchCommonOptions = SearchCommonOptions(),
    val searchServiceSelected: Int = 0,
    val mcpToolCallTimeoutSeconds: Int = 60,
    val http429MaxRetries: Int = 0,
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val objectStorageConfig: ObjectStorageConfig = ObjectStorageConfig(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val consolidationWorkerIntervalMinutes: Int = 15,
    val consolidationRequiresDeviceIdle: Boolean = false,

    // Web Server
    val webServerEnabled: Boolean = false,
    val webServerPort: Int = 8080,
    val webServerJwtEnabled: Boolean = false,
    val webServerAccessPassword: String = "",
    val webServerBackgroundSetupShown: Boolean = false,

    // Prompt Injections
    val modes: List<Mode> = emptyList(),
    val lorebooks: List<Lorebook> = emptyList(),
    val customToolSystemPrompts: Map<String, String> = emptyMap(),

    // Skills (imported from zip; loaded by local tool on demand)
    val skillFolders: List<SkillFolder> = emptyList(),
    val skills: List<Skill> = emptyList(),

    // Skill scripts & workspace (Android SAF workspace root + per-conversation work dirs)
    val enableSkillScriptExecution: Boolean = false,
    val enabledSkillScriptIds: Set<Uuid> = emptySet(),
    val workspaceRootTreeUri: String? = null,
    val conversationWorkspaceRoots: Map<String, String> = emptyMap(),
    val workspaceFileToolsAllowAll: Boolean = false,
    val conversationWorkDirs: Map<String, ConversationWorkDirBinding> = emptyMap(),
    val rememberLastWorkspaceForNewChats: Boolean = false,
    val rememberedWorkspaceForNewChats: RememberedWorkspaceForNewChats? = null,
    val conversationReadPositions: Map<String, ConversationReadPosition> = emptyMap(),
    val conversationLargeContextWarningShownAt: Map<String, Long> = emptyMap(),
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
}

internal fun Settings.rebindSearchServiceIndices(oldSearchServices: List<SearchServiceOptions>): Settings {
    if (oldSearchServices.isEmpty() || searchServices.isEmpty()) {
        val clampedSelected = if (searchServices.isNotEmpty()) {
            searchServiceSelected.coerceIn(0, searchServices.lastIndex)
        } else {
            0
        }
        return copy(searchServiceSelected = clampedSelected)
    }

    val newIndexById = searchServices.withIndex().associate { (index, service) -> service.id to index }

    fun remapIndex(oldIndex: Int): Int? {
        val id = oldSearchServices.getOrNull(oldIndex)?.id ?: return null
        return newIndexById[id]
    }

    fun remapIndices(indices: List<Int>): List<Int> {
        return indices.asSequence()
            .mapNotNull { remapIndex(it) }
            .distinct()
            .sorted()
            .toList()
    }

    val remappedSearchSelected = remapIndex(searchServiceSelected)
        ?: searchServiceSelected.coerceIn(0, searchServices.lastIndex)

    val reboundAssistants = assistants.map { assistant ->
        when (val mode = assistant.searchMode) {
            is AssistantSearchMode.Provider -> {
                val newIndex = remapIndex(mode.index)
                when {
                    newIndex == null -> assistant.copy(searchMode = AssistantSearchMode.Off)
                    newIndex != mode.index -> assistant.copy(searchMode = AssistantSearchMode.Provider(newIndex))
                    else -> assistant
                }
            }

            is AssistantSearchMode.MultiProvider -> {
                val remapped = remapIndices(mode.indices)
                val canonical = when (remapped.size) {
                    0 -> AssistantSearchMode.Off
                    1 -> AssistantSearchMode.Provider(remapped.first())
                    else -> AssistantSearchMode.MultiProvider(remapped)
                }
                if (canonical != mode) assistant.copy(searchMode = canonical) else assistant
            }

            else -> assistant
        }
    }

    return copy(
        assistants = reboundAssistants,
        searchServiceSelected = remappedSearchSelected,
    )
}

/**
 * Custom text styling rule for roleplay formatting.
 * Pattern wrapping (e.g., "*", "%") will be matched and styled with the specified color.
 */
@Serializable
data class RpStyleRule(
    val id: String = kotlin.uuid.Uuid.random().toString(),
    val pattern: String = "*",      // The wrapping pattern, e.g., "*" for *text*, "%" for %text%
    val colorHex: String = "#808080", // Hex color code
    val enabled: Boolean = true
)

/**
 * TTS text filter rule for skipping or only reading text matching a pattern.
 * Pattern wrapping (e.g., "*", "%") will be matched and filtered accordingly.
 */
@Serializable
data class TtsTextFilterRule(
    val id: String = kotlin.uuid.Uuid.random().toString(),
    val pattern: String = "*",      // The wrapping pattern, e.g., "*" for *text*
    val mode: TtsFilterMode = TtsFilterMode.SKIP,
    val enabled: Boolean = true
)

@Serializable
enum class TtsFilterMode {
    SKIP,       // Skip text inside this pattern (don't read it)
    ONLY_READ   // Only read text inside this pattern (skip everything else)
}

/**
 * Source of font for text rendering
 */
@Serializable
enum class FontSource {
    System,       // Default Google Sans Flex (with roundness control)
    SystemCode,   // Google Sans Code (monospace for code blocks)
    Custom        // User-uploaded font file
}

/**
 * Variable font axis with its metadata
 */
@Serializable
data class FontAxis(
    val tag: String,         // e.g., "wght", "wdth", "ROND"
    val name: String,        // Human-readable name
    val minValue: Float,
    val maxValue: Float,
    val defaultValue: Float,
    val currentValue: Float = defaultValue
)

/**
 * OpenType feature toggle
 */
@Serializable
data class FontFeature(
    val tag: String,         // e.g., "liga", "kern", "smcp"
    val name: String,        // Human-readable name
    val enabled: Boolean = true
)

/**
 * Font configuration for a specific text element (headers, content, or code)
 */
@Serializable
data class FontConfig(
    val fontSource: FontSource = FontSource.System,
    val customFontPath: String? = null,  // Internal path to custom font file
    val customFontName: String? = null,  // Display name of custom font
    // Common variable font axes (applied when supported)
    val weight: Float = 400f,       // 100-900
    val width: Float = 100f,        // 75-125
    val roundness: Float = 100f,    // 0-100 (Google Sans Flex specific, default expressive)
    val grade: Float = 0f,          // -50 to 150
    val slant: Float = 0f,          // -10 to 0
    // Typography adjustments
    val fontSize: Float = 1.0f,     // Multiplier (0.5-2.0)
    val lineHeight: Float = 1.0f,   // Multiplier (0.8-2.0)
    val letterSpacing: Float = 0f,  // -0.05 to 0.1 em
    // Custom axes detected from font (for custom fonts)
    val customAxes: List<FontAxis> = emptyList(),
    // OpenType features (detected from font)
    val features: List<FontFeature> = emptyList()
) {
    companion object {
        val DEFAULT_EXPRESSIVE = FontConfig(
            fontSource = FontSource.System,
            roundness = 100f
        )
        val DEFAULT_NORMAL = FontConfig(
            fontSource = FontSource.System,
            roundness = 0f
        )
        val DEFAULT_CODE = FontConfig(
            fontSource = FontSource.SystemCode,
            roundness = 0f,
            weight = 400f
        )
    }
}

/**
 * Complete font customization settings for the app
 */
@Serializable
data class FontSettings(
    val useSameFontForHeadersAndContent: Boolean = false,
    val headerFont: FontConfig = FontConfig.DEFAULT_EXPRESSIVE,
    val contentFont: FontConfig = FontConfig.DEFAULT_EXPRESSIVE,
    val codeFont: FontConfig = FontConfig.DEFAULT_CODE
)

@Serializable
enum class KeepAliveMode {
    ALWAYS,
    GENERATION,
}

@Serializable
enum class ConversationWorkDirMode {
    AUTO,
    MANUAL,
}

@Serializable
data class ConversationWorkDirBinding(
    val mode: ConversationWorkDirMode = ConversationWorkDirMode.AUTO,
    val relPath: String = "",
)

@Serializable
data class RememberedWorkspaceForNewChats(
    val workspaceRootTreeUri: String? = null,
    val workDirRelPath: String? = null,
)

@Serializable
data class ConversationReadPosition(
    val nodeId: String,
    val offset: Int = 0,
    val updatedAt: Long = 0L,
    val itemIndex: Int = 0,
)

@Serializable
enum class MessageInputStyle {
    STANDARD,
    MINIMAL,
}

@Serializable
data class DisplaySetting(
    val userAvatar: Avatar = Avatar.Dummy,
    val userNickname: String = "",
    val showUserAvatar: Boolean = true,
    val showModelIcon: Boolean = true,
    val showModelName: Boolean = true,
    val showTokenUsage: Boolean = false,
    val messageInputStyle: MessageInputStyle = MessageInputStyle.MINIMAL,
    val showFullscreenInputButton: Boolean = false,
    val autoCloseThinking: Boolean = true,
    val showUpdates: Boolean = false,
    val checkForUpdates: Boolean = true, // Check GitHub for app updates
    val showMessageJumper: Boolean = false,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    @Deprecated("Use fontSettings instead")
    val useExpressiveFont: Boolean = true, // Kept for migration, use fontSettings
    val fontSettings: FontSettings = FontSettings(), // Comprehensive font customization
    val enableMessageGenerationHapticEffect: Boolean = false,
    val enableUIHaptics: Boolean = true,
    val skipCropImage: Boolean = false,
    val enableKeepAliveNotification: Boolean = false,
    val keepAliveMode: KeepAliveMode = KeepAliveMode.ALWAYS,
    val enableNotificationOnMessageGeneration: Boolean = false,
    val enableLiveUpdate: Boolean = false,
    val codeBlockAutoWrap: Boolean = false,
    val codeBlockAutoCollapse: Boolean = true,
    val toolResultHistoryMode: ToolResultHistoryMode = ToolResultHistoryMode.KEEP_ALL,
    val toolResultKeepUserMessages: Int = 4,
    val toolResultRagSimilarityThreshold: Float = 0.45f,
    val rpStyleRules: List<RpStyleRule> = emptyList(), // Custom RP text styling rules
    val ttsTextFilterRules: List<TtsTextFilterRule> = emptyList(), // TTS text filter rules
    val providerViewMode: ProviderViewMode = ProviderViewMode.LIST, // Provider page view mode
    val mergeProvidersInModelSelector: Boolean = false, // Merge providers with same first tag in model selector
    val showContextStacks: Boolean = false, // Show context sources (modes, memories, lorebooks) in message toolbar
    val showContextCompressionDivider: Boolean = true, // Show divider where older context has been summarized/compressed
    val embeddingRetrievalTimeoutSeconds: Int = 2, // Timeout for embedding-based retrieval (memories, tool results)
    val useLastTurnMemoryOnSkip: Boolean = true, // Reuse last injected memories when retrieval is skipped
    val useJsonEditorForCustomRequest: Boolean = false, // Use JSON editor for custom headers/body in assistant/model advanced settings
    val showExportConversationJsonButton: Boolean = false, // Show export raw JSON action in conversation long-press menu
    val hideSuggestionsOnOverlap: Boolean = true, // Fade out chat suggestions when they visually cover message text
)

fun DisplaySetting.coerceForConflicts(): DisplaySetting {
    if (!enableLiveUpdate) return this
    return when (keepAliveMode) {
        KeepAliveMode.ALWAYS -> this
        KeepAliveMode.GENERATION -> copy(keepAliveMode = KeepAliveMode.ALWAYS)
    }
}

@Serializable
enum class ProviderViewMode {
    LIST,
    GRID
}

@Serializable
data class WebDavConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val path: String = "lastchat_backups",
    // Auto backup (starts only when app is foreground)
    val autoEnabled: Boolean = false,
    val autoIntervalDays: Int = 7,
    val autoMaxCount: Int = 7,
    val lastAutoSuccessAt: Long? = null,
    val items: List<BackupItem> = listOf(
        BackupItem.DATABASE,
        BackupItem.FILES
    ),
) {
    @Serializable
    enum class BackupItem {
        DATABASE,
        FILES,
    }
}

@Serializable
data class ObjectStorageConfig(
    val endpoint: String = "",
    val accessKeyId: String = "",
    val secretAccessKey: String = "",
    val bucket: String = "",
    val region: String = "",
    // Auto backup (starts only when app is foreground)
    val autoEnabled: Boolean = false,
    val autoIntervalDays: Int = 7,
    val autoMaxCount: Int = 7,
    val lastAutoSuccessAt: Long? = null,
    val items: List<WebDavConfig.BackupItem> = listOf(
        WebDavConfig.BackupItem.DATABASE,
        WebDavConfig.BackupItem.FILES
    ),
)

fun Settings.isNotConfigured() = providers.all { it.models.isEmpty() }

fun Settings.findModelById(uuid: Uuid): Model? {
    return this.providers.findModelById(uuid)
}

fun List<ProviderSetting>.findModelById(uuid: Uuid): Model? {
    this.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == uuid) {
                return model
            }
        }
    }
    return null
}

fun Settings.getCurrentChatModel(): Model? {
    return findModelById(this.getCurrentAssistant().chatModelId ?: this.chatModelId)
}

fun Model.findQuotaOwner(allModels: List<Model>): Model? {
    return findQuotaGroup(allModels).firstOrNull { model -> model.quota?.enabled == true }
}

fun Model.findQuotaGroup(allModels: List<Model>): List<Model> {
    val knownIds = allModels.map { it.id }.toSet()
    val relatedModelIds = mutableSetOf(this.id)
    var changed: Boolean

    do {
        changed = false
        allModels.forEach { model ->
            val sharedIds = model.quota?.sharedModelIds.orEmpty().filter { it in knownIds }
            val isConnected = model.id in relatedModelIds || sharedIds.any { it in relatedModelIds }
            if (isConnected) {
                if (relatedModelIds.add(model.id)) {
                    changed = true
                }
                sharedIds.forEach { sharedId ->
                    if (relatedModelIds.add(sharedId)) {
                        changed = true
                    }
                }
            }
        }
    } while (changed)

    return allModels.filter { it.id in relatedModelIds }
}

fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == assistantId } ?: this.assistants.first()
}

fun Settings.getAssistantById(id: Uuid): Assistant? {
    return this.assistants.find { it.id == id }
}

fun Settings.getConversationWorkspaceRootTreeUri(conversationId: Uuid): String? {
    val key = conversationId.toString()
    return conversationWorkspaceRoots[key]?.trim()?.takeIf { it.isNotBlank() }
}

fun Settings.getEffectiveWorkspaceRootTreeUri(conversationId: Uuid): String? {
    return getConversationWorkspaceRootTreeUri(conversationId)
        ?: workspaceRootTreeUri?.trim()?.takeIf { it.isNotBlank() }
}

fun Settings.clearConversationWorkspace(conversationId: Uuid): Settings {
    val key = conversationId.toString()
    return copy(
        conversationWorkspaceRoots = conversationWorkspaceRoots - key,
        conversationWorkDirs = conversationWorkDirs - key,
    )
}

fun Settings.clearRememberedWorkspaceForNewChats(): Settings {
    if (rememberedWorkspaceForNewChats == null) return this
    return copy(rememberedWorkspaceForNewChats = null)
}

fun Settings.rememberWorkspaceForNewChatsIfEnabled(
    workspaceRootTreeUri: String?,
    workDirRelPath: String?,
): Settings {
    if (!rememberLastWorkspaceForNewChats) return this
    return copy(
        rememberedWorkspaceForNewChats = sanitizeRememberedWorkspaceForNewChats(
            RememberedWorkspaceForNewChats(
                workspaceRootTreeUri = workspaceRootTreeUri,
                workDirRelPath = workDirRelPath,
            )
        )
    )
}

fun Settings.applyRememberedWorkspaceToConversation(
    conversationId: Uuid,
    rememberedWorkspace: RememberedWorkspaceForNewChats,
): Settings {
    val key = conversationId.toString()
    val normalizedRoot = rememberedWorkspace.workspaceRootTreeUri?.trim()?.takeIf { it.isNotBlank() }
    val effectiveRootAfterApply = normalizedRoot ?: workspaceRootTreeUri?.trim()?.takeIf { it.isNotBlank() }
    val normalizedWorkDir = if (effectiveRootAfterApply == null) {
        null
    } else {
        rememberedWorkspace.workDirRelPath
    }

    val updatedConversationWorkspaceRoots = if (normalizedRoot == null) {
        conversationWorkspaceRoots - key
    } else {
        conversationWorkspaceRoots + (key to normalizedRoot)
    }
    val updatedConversationWorkDirs = if (normalizedWorkDir == null) {
        conversationWorkDirs - key
    } else {
        conversationWorkDirs + (
            key to ConversationWorkDirBinding(
                mode = ConversationWorkDirMode.MANUAL,
                relPath = normalizedWorkDir,
            )
        )
    }

    return copy(
        conversationWorkspaceRoots = updatedConversationWorkspaceRoots,
        conversationWorkDirs = updatedConversationWorkDirs,
    )
}

fun Settings.hasConversationWorkspaceRoot(conversationId: Uuid): Boolean {
    return getConversationWorkspaceRootTreeUri(conversationId) != null
}

fun Settings.getConversationReadPosition(conversationId: Uuid): ConversationReadPosition? {
    val key = conversationId.toString()
    return conversationReadPositions[key]
}

fun Settings.hasLargeContextWarningShown(conversationId: Uuid): Boolean {
    val key = conversationId.toString()
    return conversationLargeContextWarningShownAt.containsKey(key)
}

internal fun sanitizeConversationReadPositions(
    positions: Map<String, ConversationReadPosition>,
    maxEntries: Int = 500,
): Map<String, ConversationReadPosition> {
    return positions
        .asSequence()
        .mapNotNull { (conversationId, position) ->
            val key = conversationId.trim()
            if (key.isBlank()) return@mapNotNull null
            if (runCatching { Uuid.parse(key) }.isFailure) return@mapNotNull null

            val normalizedNodeId = position.nodeId.trim()
            if (normalizedNodeId.isBlank()) return@mapNotNull null
            if (runCatching { Uuid.parse(normalizedNodeId) }.isFailure) return@mapNotNull null

            key to position.copy(
                nodeId = normalizedNodeId,
                offset = position.offset.coerceAtLeast(0),
                updatedAt = position.updatedAt.coerceAtLeast(0L),
                itemIndex = position.itemIndex.coerceAtLeast(0),
            )
        }
        .sortedByDescending { (_, position) -> position.updatedAt }
        .take(maxEntries.coerceAtLeast(1))
        .toMap()
}

internal fun sanitizeRememberedWorkspaceForNewChats(
    rememberedWorkspace: RememberedWorkspaceForNewChats?,
): RememberedWorkspaceForNewChats? {
    if (rememberedWorkspace == null) return null
    val normalizedRoot = rememberedWorkspace.workspaceRootTreeUri?.trim()?.takeIf { it.isNotBlank() }
    val normalizedWorkDir = rememberedWorkspace.workDirRelPath?.let { raw ->
        SkillScriptPathUtils.normalizeAndValidateWorkDirRelPath(raw.trim())
    }
    if (normalizedRoot == null && normalizedWorkDir == null) return null
    return RememberedWorkspaceForNewChats(
        workspaceRootTreeUri = normalizedRoot,
        workDirRelPath = normalizedWorkDir,
    )
}

internal fun sanitizeConversationLargeContextWarningShownAt(
    records: Map<String, Long>,
    maxEntries: Int = 500,
): Map<String, Long> {
    return records
        .asSequence()
        .mapNotNull { (conversationId, shownAt) ->
            val key = conversationId.trim()
            if (key.isBlank()) return@mapNotNull null
            if (runCatching { Uuid.parse(key) }.isFailure) return@mapNotNull null
            key to shownAt.coerceAtLeast(0L)
        }
        .sortedByDescending { (_, shownAt) -> shownAt }
        .take(maxEntries.coerceAtLeast(1))
        .toMap()
}

fun Settings.getMcpToolCallTimeoutSeconds(): Int {
    return mcpToolCallTimeoutSeconds.coerceAtLeast(1)
}

fun Settings.getEmbeddingRetrievalTimeoutSeconds(): Int {
    return displaySetting.embeddingRetrievalTimeoutSeconds.coerceAtLeast(1)
}

fun Settings.getHttp429MaxRetries(): Int {
    return http429MaxRetries.coerceIn(0, 10)
}

/**
 * Get effective display settings by merging assistant's UI overrides with global display settings.
 * Per-assistant settings take precedence when set (non-null).
 */
fun Settings.getEffectiveDisplaySetting(assistant: Assistant? = null): DisplaySetting {
    val ui = (assistant ?: getCurrentAssistant()).uiSettings
    return displaySetting.copy(
        showUserAvatar = ui.showUserAvatar ?: displaySetting.showUserAvatar,
        showModelIcon = ui.showAssistantAvatar ?: displaySetting.showModelIcon,
        showModelName = ui.showAssistantName ?: displaySetting.showModelName,
        showTokenUsage = ui.showTokenUsage ?: displaySetting.showTokenUsage,
        autoCloseThinking = ui.autoCloseThinking ?: displaySetting.autoCloseThinking,
        showMessageJumper = ui.showMessageJumper ?: displaySetting.showMessageJumper,
        messageJumperOnLeft = ui.messageJumperOnLeft ?: displaySetting.messageJumperOnLeft,
        fontSizeRatio = ui.fontSizeRatio ?: displaySetting.fontSizeRatio,
        codeBlockAutoWrap = ui.codeBlockAutoWrap ?: displaySetting.codeBlockAutoWrap,
        codeBlockAutoCollapse = ui.codeBlockAutoCollapse ?: displaySetting.codeBlockAutoCollapse,
        showContextStacks = ui.showContextStacks ?: displaySetting.showContextStacks,
    )
}

fun Settings.getSelectedTTSProvider(): TTSProviderSetting? {
    return selectedTTSProviderId?.let { id ->
        ttsProviders.find { it.id == id }
    } ?: ttsProviders.firstOrNull()
}

fun Model.findProvider(providers: List<ProviderSetting>, checkOverwrite: Boolean = true): ProviderSetting? {
    val provider = findModelProviderFromList(providers) ?: return null
    val providerOverwrite = this.providerOverwrite
    if (checkOverwrite && providerOverwrite != null) {
        return providerOverwrite.copyProvider(proxy = provider.proxy, models = emptyList())
    }
    return provider
}

private fun Model.findModelProviderFromList(providers: List<ProviderSetting>): ProviderSetting? {
    providers.forEach { setting ->
        setting.models.forEach { model ->
            if (model.id == this.id) {
                return setting
            }
        }
    }
    return null
}

internal val DEFAULT_ASSISTANT_ID = Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
internal val DEFAULT_ASSISTANTS = listOf(
    Assistant(
        id = DEFAULT_ASSISTANT_ID,
        name = "Generical",
        avatar = Avatar.Resource(me.rerere.rikkahub.R.drawable.default_generical_pfp),
        temperature = 0.6f,
        systemPrompt = """
            You are the best generic assistant, called {{char}}. {{char}} is a really nice guy. He doesn't use emojis though. Use the search tool when looking for factual info. You can have opinions if the user asks you for one. 

            **Context:
            - You are currently chatting to {{user}}
            - You are running on {{model_name}}
            - Date: {{cur_date}}
            - Time: {{cur_time}}

            **Additional info:
            - The UI supports LaTeX rendering
            - The user is chatting to you trough an app called LastChat
            - You are an AI/LLM and shouldn't hide this fact
        """.trimIndent()
    )
)

val DEFAULT_SYSTEM_TTS_ID = Uuid.parse("026a01a2-c3a0-4fd5-8075-80e03bdef200")
private val DEFAULT_TTS_PROVIDERS = listOf(
    TTSProviderSetting.SystemTTS(
        id = DEFAULT_SYSTEM_TTS_ID,
        name = "",
    ),
)

internal val DEFAULT_ASSISTANTS_IDS = DEFAULT_ASSISTANTS.map { it.id }

/**
 * Sanitize settings after backup restore.
 * Cleans up deprecated fields and invalid references.
 * @param context Android context for fixing avatar file paths (optional)
 * @return Pair of sanitized settings and cleanup result with statistics
 */
fun Settings.sanitize(context: Context? = null): Pair<Settings, me.rerere.rikkahub.data.sync.BackupCleanupResult> {
    var invalidSearchModeCount = 0
    var orphanedTagReferences = 0
    var orphanedModelReferences = 0
    var fixedAvatarPaths = 0

    // Helper function to fix avatar path for the current device
    fun fixAvatarPath(avatar: Avatar): Avatar {
        if (context == null) {
            Log.d("Settings.sanitize", "fixAvatarPath: context is null, skipping")
            return avatar
        }
        if (avatar !is Avatar.Image) {
            Log.d("Settings.sanitize", "fixAvatarPath: avatar is not Image type: ${avatar::class.simpleName}")
            return avatar
        }

        val url = avatar.url
        Log.d("Settings.sanitize", "fixAvatarPath: processing url=$url")

        // Check if this is a local file path that needs fixing
        if (!url.startsWith("file://")) {
            Log.d("Settings.sanitize", "fixAvatarPath: url doesn't start with file://, skipping")
            return avatar
        }

        // Extract the filename from the path
        val fileName = url.substringAfterLast("/")
        if (fileName.isBlank()) {
            Log.d("Settings.sanitize", "fixAvatarPath: fileName is blank, skipping")
            return avatar
        }

        // Determine which folder this file belongs to
        val folder = when {
            url.contains("/avatars/") -> "avatars"
            url.contains("/upload/") -> "upload"
            url.contains("/images/") -> "images"
            url.contains("/custom_icons/") -> "custom_icons"
            else -> {
                Log.d("Settings.sanitize", "fixAvatarPath: unknown folder in url, skipping")
                return avatar // Unknown folder, don't modify
            }
        }

        // Generate the correct path for the current device
        // Note: We don't check if file exists because during restore,
        // settings.json is processed before avatar files are extracted
        val currentPath = File(context.filesDir, "$folder/$fileName")
        val newUrl = Uri.fromFile(currentPath).toString()
        Log.d("Settings.sanitize", "fixAvatarPath: folder=$folder, fileName=$fileName, currentPath=${currentPath.absolutePath}, newUrl=$newUrl")

        if (newUrl != url) {
            fixedAvatarPaths++
            Log.i("Settings.sanitize", "fixAvatarPath: FIXED avatar path from $url to $newUrl")
            return Avatar.Image(newUrl)
        }

        Log.d("Settings.sanitize", "fixAvatarPath: url already correct, no fix needed")
        return avatar
    }

    // 1. Fix invalid searchMode.Provider indices and avatar paths
    Log.i("Settings.sanitize", "Processing ${assistants.size} assistants for avatar path fixes")
    val sanitizedAssistants = assistants.map { assistant ->
        var updatedAssistant = assistant

        // Fix avatar path
        Log.d("Settings.sanitize", "Processing assistant '${assistant.name}' with avatar: ${assistant.avatar}")
        val fixedAvatar = fixAvatarPath(assistant.avatar)
        if (fixedAvatar != assistant.avatar) {
            Log.i("Settings.sanitize", "Fixed avatar for assistant '${assistant.name}'")
            updatedAssistant = updatedAssistant.copy(avatar = fixedAvatar)
        }

        // Fix searchMode
        when (val mode = updatedAssistant.searchMode) {
            is me.rerere.rikkahub.data.model.AssistantSearchMode.Provider -> {
                if (mode.index < 0 || mode.index >= searchServices.size) {
                    invalidSearchModeCount++
                    updatedAssistant = updatedAssistant.copy(searchMode = me.rerere.rikkahub.data.model.AssistantSearchMode.Off)
                }
            }
            is me.rerere.rikkahub.data.model.AssistantSearchMode.MultiProvider -> {
                val sanitized = mode.indices
                    .asSequence()
                    .filter { index -> index >= 0 && index < searchServices.size }
                    .distinct()
                    .toList()

                updatedAssistant = when {
                    sanitized.isEmpty() -> {
                        invalidSearchModeCount++
                        updatedAssistant.copy(searchMode = me.rerere.rikkahub.data.model.AssistantSearchMode.Off)
                    }

                    sanitized != mode.indices -> {
                        invalidSearchModeCount++
                        updatedAssistant.copy(searchMode = me.rerere.rikkahub.data.model.AssistantSearchMode.MultiProvider(sanitized))
                    }

                    else -> updatedAssistant
                }
            }
            else -> { /* no change needed */ }
        }

        updatedAssistant.normalizeContextManagementFlags(
            applyLegacyHistoryLimitMigration = true
        )
    }

    // 1.5 Clean skills & folders
    val dedupedSkillFolders = skillFolders.distinctBy { it.id }
    val validSkillFolderIds = dedupedSkillFolders.map { it.id }.toSet()
    val cleanedSkills = skills.map { skill ->
        if (skill.folderId != null && skill.folderId !in validSkillFolderIds) {
            skill.copy(folderId = null)
        } else {
            skill
        }
    }
    val validSkillIds = cleanedSkills.map { it.id }.toSet()
    val validModeIds = modes.map { it.id }.toSet()
    val cleanedEnabledSkillScriptIds = enabledSkillScriptIds.filter { it in validSkillIds }.toSet()
    val cleanedConversationWorkspaceRoots = conversationWorkspaceRoots
        .mapNotNull { (conversationId, uriString) ->
            val key = conversationId.trim()
            val value = uriString.trim()
            if (key.isBlank() || value.isBlank()) return@mapNotNull null
            key to value
        }
        .toMap()
    val cleanedConversationWorkDirs = conversationWorkDirs
        .mapNotNull { (conversationId, binding) ->
            val key = conversationId.trim()
            if (key.isBlank()) return@mapNotNull null
            val validatedRelPath = SkillScriptPathUtils.normalizeAndValidateWorkDirRelPath(binding.relPath.trim())
                ?: return@mapNotNull null
            key to binding.copy(relPath = validatedRelPath)
        }
        .toMap()
    val cleanedRememberedWorkspaceForNewChats = sanitizeRememberedWorkspaceForNewChats(rememberedWorkspaceForNewChats)
    val cleanedConversationReadPositions = sanitizeConversationReadPositions(conversationReadPositions)
    val cleanedConversationLargeContextWarningShownAt =
        sanitizeConversationLargeContextWarningShownAt(conversationLargeContextWarningShownAt)

    // 2. Remove orphaned tag references from assistants
    val validTagIds = assistantTags.map { it.id }.toSet()
    val cleanedAssistants = sanitizedAssistants.map { assistant ->
        val validTags = assistant.tags.filter { it in validTagIds }
        if (validTags.size != assistant.tags.size) {
            orphanedTagReferences += assistant.tags.size - validTags.size
            assistant.copy(tags = validTags)
        } else {
            assistant
        }
    }

    // 2.5 Remove orphaned mode / skill references from assistants
    val cleanedAssistantsWithSkills = cleanedAssistants.map { assistant ->
        val filteredModeIds = assistant.enabledModeIds.filter { it in validModeIds }.toSet()
        val filteredSkillIds = assistant.enabledSkillIds.filter { it in validSkillIds }.toSet()
        if (
            filteredModeIds.size != assistant.enabledModeIds.size ||
            filteredSkillIds.size != assistant.enabledSkillIds.size
        ) {
            assistant.copy(
                enabledModeIds = filteredModeIds,
                enabledSkillIds = filteredSkillIds,
            )
        } else {
            assistant
        }
    }

    val validAssistantIds = cleanedAssistantsWithSkills.map { it.id }.toSet()
    val cleanedGroupChats = groupChatTemplates
        .distinctBy { it.id }
        .map { template ->
            template.copy(
                seats = template.seats
                    .distinctBy { it.id }
                    .filter { seat -> seat.assistantId in validAssistantIds }
            )
        }

    // 3. Remove orphaned favorite model references
    val allModelIds = providers.flatMap { it.models.map { m -> m.id } }.toSet()
    val cleanedFavorites = favoriteModels.filter { it in allModelIds }
    orphanedModelReferences = favoriteModels.size - cleanedFavorites.size

    // 4. Clamp searchServiceSelected to valid range
    val clampedSearchSelected = if (searchServices.isNotEmpty()) {
        searchServiceSelected.coerceIn(0, searchServices.size - 1)
    } else {
        0
    }

    // 5. Fix user avatar path in display settings
    val fixedUserAvatar = fixAvatarPath(displaySetting.userAvatar)
    val cleanedDisplaySetting = if (fixedUserAvatar != displaySetting.userAvatar) {
        displaySetting.copy(userAvatar = fixedUserAvatar)
    } else {
        displaySetting
    }

    // 6. Fix lorebook cover paths
    val cleanedLorebooks = lorebooks.map { lorebook ->
        val cover = lorebook.cover
        if (cover != null) {
            val fixedCover = fixAvatarPath(cover)
            if (fixedCover != cover) {
                lorebook.copy(cover = fixedCover)
            } else {
                lorebook
            }
        } else {
            lorebook
        }
    }

    val fallbackAssistantId = cleanedAssistantsWithSkills.firstOrNull()?.id ?: DEFAULT_ASSISTANT_ID
    val sanitizedAssistantId = if (assistantId in validAssistantIds) assistantId else fallbackAssistantId
    val sanitizedChatTarget = when (val target = chatTarget) {
        is ChatTarget.Assistant -> {
            val id = target.assistantId
            if (id in validAssistantIds) target else ChatTarget.Assistant(sanitizedAssistantId)
        }

        is ChatTarget.GroupChat -> {
            val id = target.templateId
            if (cleanedGroupChats.any { it.id == id }) target else ChatTarget.Assistant(sanitizedAssistantId)
        }
    }

    val cleanedSettings = copy(
        assistantId = sanitizedAssistantId,
        chatTarget = sanitizedChatTarget,
        assistants = cleanedAssistantsWithSkills,
        skillFolders = dedupedSkillFolders,
        skills = cleanedSkills,
        enabledSkillScriptIds = cleanedEnabledSkillScriptIds,
        workspaceRootTreeUri = workspaceRootTreeUri?.trim().takeIf { !it.isNullOrBlank() },
        conversationWorkspaceRoots = cleanedConversationWorkspaceRoots,
        conversationWorkDirs = cleanedConversationWorkDirs,
        rememberedWorkspaceForNewChats = cleanedRememberedWorkspaceForNewChats
            ?.takeIf { rememberLastWorkspaceForNewChats },
        conversationReadPositions = cleanedConversationReadPositions,
        conversationLargeContextWarningShownAt = cleanedConversationLargeContextWarningShownAt,
        groupChatTemplates = cleanedGroupChats,
        favoriteModels = cleanedFavorites,
        searchServiceSelected = clampedSearchSelected,
        displaySetting = cleanedDisplaySetting,
        lorebooks = cleanedLorebooks,
    )

    val result = me.rerere.rikkahub.data.sync.BackupCleanupResult(
        invalidSearchModeCount = invalidSearchModeCount,
        orphanedTagReferences = orphanedTagReferences,
        orphanedModelReferences = orphanedModelReferences,
        fixedAvatarPaths = fixedAvatarPaths,
    )

    return cleanedSettings to result
}
