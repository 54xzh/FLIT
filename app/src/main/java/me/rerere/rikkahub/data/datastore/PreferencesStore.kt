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
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_LEARNING_MODE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import me.rerere.rikkahub.data.model.Assistant
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
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import me.rerere.rikkahub.utils.toMutableStateFlow
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

private const val TAG = "PreferencesStore"

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

private val Context.settingsStore by preferencesDataStore(
    name = "settings",
    produceMigrations = { context ->
        listOf(
            PreferenceStoreV1Migration()
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
        val ENABLE_RAG_LOGGING = booleanPreferencesKey("enable_rag_logging")

        // 模型选择
        val ENABLE_WEB_SEARCH = booleanPreferencesKey("enable_web_search")
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val TITLE_MODEL = stringPreferencesKey("title_model")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val SUGGESTION_MODEL = stringPreferencesKey("suggestion_model")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
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

        // WebDAV
        val WEBDAV_CONFIG = stringPreferencesKey("webdav_config")

        // TTS
        val TTS_PROVIDERS = stringPreferencesKey("tts_providers")
        val SELECTED_TTS_PROVIDER = stringPreferencesKey("selected_tts_provider")

        // Background Worker
        val CONSOLIDATION_WORKER_INTERVAL = intPreferencesKey("consolidation_worker_interval")
        val CONSOLIDATION_REQUIRES_DEVICE_IDLE = booleanPreferencesKey("consolidation_requires_device_idle")

        // Prompt Injections
        val MODES = stringPreferencesKey("modes")
        val LOREBOOKS = stringPreferencesKey("lorebooks")

        // Skills
        val SKILLS = stringPreferencesKey("skills")
        val SKILL_FOLDERS = stringPreferencesKey("skill_folders")
        val ENABLE_SKILL_SCRIPT_EXECUTION = booleanPreferencesKey("enable_skill_script_execution")
        val ENABLED_SKILL_SCRIPT_IDS = stringPreferencesKey("enabled_skill_script_ids")
        val WORKSPACE_ROOT_TREE_URI = stringPreferencesKey("workspace_root_tree_uri")
        val CONVERSATION_WORK_DIRS = stringPreferencesKey("conversation_work_dirs")

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
                translateModeId = preferences[TRANSLATE_MODEL]?.let { Uuid.parse(it) }
                    ?: GEMINI_2_5_FLASH_ID,
                suggestionModelId = preferences[SUGGESTION_MODEL]?.let { Uuid.parse(it) }
                    ?: GEMINI_2_5_FLASH_ID,
                imageGenerationModelId = preferences[IMAGE_GENERATION_MODEL]?.let { Uuid.parse(it) } ?: Uuid.random(),
                titlePrompt = preferences[TITLE_PROMPT] ?: DEFAULT_TITLE_PROMPT,
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
                mcpServers = preferences[MCP_SERVERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                webDavConfig = preferences[WEBDAV_CONFIG]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: WebDavConfig(),
                ttsProviders = preferences[TTS_PROVIDERS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                selectedTTSProviderId = preferences[SELECTED_TTS_PROVIDER]?.let { Uuid.parse(it) }
                    ?: DEFAULT_SYSTEM_TTS_ID,
                consolidationWorkerIntervalMinutes = preferences[CONSOLIDATION_WORKER_INTERVAL] ?: 15,
                consolidationRequiresDeviceIdle = preferences[CONSOLIDATION_REQUIRES_DEVICE_IDLE] ?: false,
                modes = preferences[MODES]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
                lorebooks = preferences[LOREBOOKS]?.let {
                    JsonInstant.decodeFromString(it)
                } ?: emptyList(),
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
                workspaceRootTreeUri = preferences[WORKSPACE_ROOT_TREE_URI],
                conversationWorkDirs = preferences[CONVERSATION_WORK_DIRS]?.let {
                    runCatching { JsonInstant.decodeFromString<Map<String, ConversationWorkDirBinding>>(it) }.getOrNull()
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
            val validSkillIds = sanitizedSkills.map { it.id }.toSet()
            val dedupedAssistants = settings.assistants.distinctBy { it.id }.map { assistant ->
                assistant.copy(
                    mcpServers = assistant.mcpServers.filter { serverId ->
                        serverId in validMcpServerIds
                    }.toSet(),
                    enabledSkillIds = assistant.enabledSkillIds.filter { skillId ->
                        skillId in validSkillIds
                    }.toSet()
                )
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
                    }
                },
                assistants = settings.assistants.distinctBy { it.id }.map { assistant ->
                    assistant.copy(
                        // 过滤掉不存在的 MCP 服务器 ID
                        mcpServers = assistant.mcpServers.filter { serverId ->
                            serverId in validMcpServerIds
                        }.toSet(),
                        enabledSkillIds = assistant.enabledSkillIds.filter { skillId ->
                            skillId in validSkillIds
                        }.toSet()
                    )
                },
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
        
        // Auto-update recently used assistants when assistant changes
        val settingsToSave = if (settings.assistantId != settingsFlow.value.assistantId && 
            !settingsFlow.value.init &&
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

        val finalSettingsToSave = settingsToSave.copy(
            displaySetting = settingsToSave.displaySetting.coerceForConflicts()
        )

        settingsFlow.value = finalSettingsToSave
        dataStore.edit { preferences ->
            preferences[DYNAMIC_COLOR] = finalSettingsToSave.dynamicColor
            preferences[THEME_ID] = finalSettingsToSave.themeId
            preferences[DEVELOPER_MODE] = finalSettingsToSave.developerMode
            preferences[ENABLE_RAG_LOGGING] = finalSettingsToSave.enableRagLogging
            preferences[DISPLAY_SETTING] = JsonInstant.encodeToString(finalSettingsToSave.displaySetting)
            preferences[TEXT_SELECTION_CONFIG] = JsonInstant.encodeToString(finalSettingsToSave.textSelectionConfig)

            preferences[ENABLE_WEB_SEARCH] = finalSettingsToSave.enableWebSearch
            preferences[FAVORITE_MODELS] = JsonInstant.encodeToString(finalSettingsToSave.favoriteModels)
            preferences[SELECT_MODEL] = finalSettingsToSave.chatModelId.toString()
            preferences[TITLE_MODEL] = finalSettingsToSave.titleModelId.toString()
            preferences[TRANSLATE_MODEL] = finalSettingsToSave.translateModeId.toString()
            preferences[SUGGESTION_MODEL] = finalSettingsToSave.suggestionModelId.toString()
            preferences[IMAGE_GENERATION_MODEL] = finalSettingsToSave.imageGenerationModelId.toString()
            preferences[TITLE_PROMPT] = finalSettingsToSave.titlePrompt
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
            preferences[WEBDAV_CONFIG] = JsonInstant.encodeToString(finalSettingsToSave.webDavConfig)
            preferences[TTS_PROVIDERS] = JsonInstant.encodeToString(finalSettingsToSave.ttsProviders)
            finalSettingsToSave.selectedTTSProviderId?.let {
                preferences[SELECTED_TTS_PROVIDER] = it.toString()
            } ?: preferences.remove(SELECTED_TTS_PROVIDER)

            preferences[CONSOLIDATION_WORKER_INTERVAL] = finalSettingsToSave.consolidationWorkerIntervalMinutes
            preferences[CONSOLIDATION_REQUIRES_DEVICE_IDLE] = finalSettingsToSave.consolidationRequiresDeviceIdle

            preferences[MODES] = JsonInstant.encodeToString(finalSettingsToSave.modes)
            preferences[LOREBOOKS] = JsonInstant.encodeToString(finalSettingsToSave.lorebooks)
            preferences[SKILL_FOLDERS] = JsonInstant.encodeToString(finalSettingsToSave.skillFolders)
            preferences[SKILLS] = JsonInstant.encodeToString(finalSettingsToSave.skills)
            preferences[ENABLE_SKILL_SCRIPT_EXECUTION] = finalSettingsToSave.enableSkillScriptExecution
            preferences[ENABLED_SKILL_SCRIPT_IDS] = JsonInstant.encodeToString(finalSettingsToSave.enabledSkillScriptIds)
            finalSettingsToSave.workspaceRootTreeUri?.let {
                preferences[WORKSPACE_ROOT_TREE_URI] = it
            } ?: preferences.remove(WORKSPACE_ROOT_TREE_URI)
            preferences[CONVERSATION_WORK_DIRS] = JsonInstant.encodeToString(finalSettingsToSave.conversationWorkDirs)
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
    val enableRagLogging: Boolean = false,
    val displaySetting: DisplaySetting = DisplaySetting(),
    val textSelectionConfig: TextSelectionConfig = TextSelectionConfig(),
    val enableWebSearch: Boolean = false,
    val favoriteModels: List<Uuid> = emptyList(),
    val chatModelId: Uuid = Uuid.random(),
    val titleModelId: Uuid = Uuid.random(),
    val imageGenerationModelId: Uuid = Uuid.random(),
    val titlePrompt: String = DEFAULT_TITLE_PROMPT,
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
    val mcpServers: List<McpServerConfig> = emptyList(),
    val webDavConfig: WebDavConfig = WebDavConfig(),
    val ttsProviders: List<TTSProviderSetting> = DEFAULT_TTS_PROVIDERS,
    val selectedTTSProviderId: Uuid = DEFAULT_SYSTEM_TTS_ID,
    val consolidationWorkerIntervalMinutes: Int = 15,
    val consolidationRequiresDeviceIdle: Boolean = false,

    // Prompt Injections
    val modes: List<Mode> = emptyList(),
    val lorebooks: List<Lorebook> = emptyList(),

    // Skills (imported from zip; loaded by local tool on demand)
    val skillFolders: List<SkillFolder> = emptyList(),
    val skills: List<Skill> = emptyList(),

    // Skill scripts & workspace (Android SAF workspace root + per-conversation work dirs)
    val enableSkillScriptExecution: Boolean = false,
    val enabledSkillScriptIds: Set<Uuid> = emptySet(),
    val workspaceRootTreeUri: String? = null,
    val conversationWorkDirs: Map<String, ConversationWorkDirBinding> = emptyMap(),
) {
    companion object {
        // 构造一个用于初始化的settings, 但它不能用于保存，防止使用初始值存储
        fun dummy() = Settings(init = true)
    }
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
    val messageInputStyle: MessageInputStyle = MessageInputStyle.STANDARD,
    val autoCloseThinking: Boolean = true,
    val showUpdates: Boolean = false,
    val checkForUpdates: Boolean = true, // Check GitHub for app updates
    val showMessageJumper: Boolean = false,
    val messageJumperOnLeft: Boolean = false,
    val fontSizeRatio: Float = 1.0f,
    val useExpressiveFont: Boolean = true, // M3 Expressive font (rounded, wider) vs Normal
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
    val showContextStacks: Boolean = false, // Show context sources (modes, memories, lorebooks) in message toolbar
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

fun Settings.getCurrentAssistant(): Assistant {
    return this.assistants.find { it.id == assistantId } ?: this.assistants.first()
}

fun Settings.getAssistantById(id: Uuid): Assistant? {
    return this.assistants.find { it.id == id }
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
            else -> { /* no change needed */ }
        }

        updatedAssistant
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
    val cleanedEnabledSkillScriptIds = enabledSkillScriptIds.filter { it in validSkillIds }.toSet()
    val cleanedConversationWorkDirs = conversationWorkDirs.filterValues { binding ->
        binding.relPath.isNotBlank()
    }

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

    // 2.5 Remove orphaned skill references from assistants
    val cleanedAssistantsWithSkills = cleanedAssistants.map { assistant ->
        val filtered = assistant.enabledSkillIds.filter { it in validSkillIds }.toSet()
        if (filtered.size != assistant.enabledSkillIds.size) {
            assistant.copy(enabledSkillIds = filtered)
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
        conversationWorkDirs = cleanedConversationWorkDirs,
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

