package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.rikkahub.data.ai.rag.EmbeddingService
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.LorebookActivationType
import me.rerere.rikkahub.data.model.LorebookEntry
import me.rerere.rikkahub.data.repository.LorebookEntryRevisionRepository
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import kotlin.uuid.Uuid

object LorebookTools {
    fun create(
        assistant: Assistant,
        conversationId: Uuid,
        settingsSnapshot: Settings,
        settingsStore: SettingsStore,
        embeddingService: EmbeddingService,
        revisionRepo: LorebookEntryRevisionRepository,
    ): List<Tool> {
        return listOf(
            createListEnabledTool(
                assistant = assistant,
                settingsSnapshot = settingsSnapshot,
                settingsStore = settingsStore,
            ),
            createEntryListTool(
                assistant = assistant,
                settingsSnapshot = settingsSnapshot,
                settingsStore = settingsStore,
            ),
            createEntryCreateTool(
                assistant = assistant,
                conversationId = conversationId,
                settingsSnapshot = settingsSnapshot,
                settingsStore = settingsStore,
                embeddingService = embeddingService,
                revisionRepo = revisionRepo,
            ),
            createEntryUpdateTool(
                assistant = assistant,
                conversationId = conversationId,
                settingsSnapshot = settingsSnapshot,
                settingsStore = settingsStore,
                embeddingService = embeddingService,
                revisionRepo = revisionRepo,
            ),
            createEntryDeleteTool(
                assistant = assistant,
                conversationId = conversationId,
                settingsSnapshot = settingsSnapshot,
                settingsStore = settingsStore,
                revisionRepo = revisionRepo,
            ),
            createHistoryListTool(
                assistant = assistant,
                settingsSnapshot = settingsSnapshot,
                settingsStore = settingsStore,
                revisionRepo = revisionRepo,
            ),
            createHistoryUndoTool(
                assistant = assistant,
                settingsSnapshot = settingsSnapshot,
                settingsStore = settingsStore,
                revisionRepo = revisionRepo,
            ),
        )
    }

    private fun createListEnabledTool(
        assistant: Assistant,
        settingsSnapshot: Settings,
        settingsStore: SettingsStore,
    ): Tool {
        return Tool(
            name = "lorebooks_list_enabled",
            description = "List enabled lorebooks.",
            parameters = {
                InputSchema.Obj(properties = buildJsonObject { })
            },
            systemPrompt = { _, _ -> LOREBOOK_SYSTEM_PROMPT_TEMPLATE },
            execute = {
                val settings = currentSettings(settingsSnapshot, settingsStore)
                val enabled = resolveEnabledLorebooks(settings, assistant)
                buildJsonObject {
                    put("ok", true)
                    put(
                        "items",
                        buildJsonArray {
                            enabled.forEach { lorebook ->
                                add(
                                    buildJsonObject {
                                        put("lorebook_id", lorebook.id.toString())
                                        put("name", lorebook.name)
                                    }
                                )
                            }
                        }
                    )
                }
            },
        )
    }

    private fun createEntryListTool(
        assistant: Assistant,
        settingsSnapshot: Settings,
        settingsStore: SettingsStore,
    ): Tool {
        return Tool(
            name = "lorebooks_entry_list",
            description = "List lorebook entries.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("lorebook_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Target lorebook id.")
                        })
                    },
                    required = listOf("lorebook_id"),
                )
            },
            execute = {
                val obj = it.jsonObject
                val lorebookId = obj["lorebook_id"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?: return@Tool error("missing_lorebook_id", "lorebook_id is required")

                val settings = currentSettings(settingsSnapshot, settingsStore)
                val lorebook = resolveEnabledLorebookOrNull(settings, assistant, lorebookId)
                    ?: return@Tool error("lorebook_not_enabled_for_assistant", "Lorebook is not enabled for this assistant")

                buildJsonObject {
                    put("ok", true)
                    put(
                        "items",
                        buildJsonArray {
                            lorebook.entries.forEach { entry ->
                                add(
                                    buildJsonObject {
                                        put("entry_id", entry.id.toString())
                                        put("title", entry.name)
                                    }
                                )
                            }
                        }
                    )
                }
            },
        )
    }

    private fun createEntryCreateTool(
        assistant: Assistant,
        conversationId: Uuid,
        settingsSnapshot: Settings,
        settingsStore: SettingsStore,
        embeddingService: EmbeddingService,
        revisionRepo: LorebookEntryRevisionRepository,
    ): Tool {
        return Tool(
            name = "lorebooks_entry_create",
            description = "Create a lorebook entry.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("lorebook_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Target lorebook id.")
                        })
                        put("title", buildJsonObject {
                            put("type", "string")
                            put("description", "Entry title.")
                        })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "Entry content.")
                        })
                        put("trigger_type", buildJsonObject {
                            put("type", "string")
                            put("description", "Trigger type: always|keywords|rag.")
                        })
                    },
                    required = listOf("lorebook_id", "title", "content", "trigger_type"),
                )
            },
            execute = { args ->
                val obj = args.jsonObject
                val lorebookId = obj["lorebook_id"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?: return@Tool error("missing_lorebook_id", "lorebook_id is required")
                val title = obj["title"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?: return@Tool error("missing_title", "title is required")
                val content = obj["content"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?: return@Tool error("missing_content", "content is required")
                val triggerTypeRaw = obj["trigger_type"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?: return@Tool error("missing_trigger_type", "trigger_type is required")

                val activationType = parseActivationType(triggerTypeRaw)
                    ?: return@Tool error("invalid_trigger_type", "trigger_type must be one of: always|keywords|rag")

                val settings = currentSettings(settingsSnapshot, settingsStore)
                val lorebook = resolveEnabledLorebookOrNull(settings, assistant, lorebookId)
                    ?: return@Tool error("lorebook_not_enabled_for_assistant", "Lorebook is not enabled for this assistant")

                val entryForCreate = buildEntryForCreate(
                    title = title,
                    content = content,
                    activationType = activationType,
                    embeddingService = embeddingService,
                ) ?: return@Tool error("embedding_failed", "Failed to generate embedding for RAG entry")

                var newEntryIndex: Int? = null
                var savedEntry: LorebookEntry? = null
                withContext(Dispatchers.IO) {
                    settingsStore.update { current ->
                        val currentLorebook = resolveEnabledLorebookOrNull(current, assistant, lorebookId) ?: return@update current
                        newEntryIndex = currentLorebook.entries.size
                        savedEntry = entryForCreate
                        val updatedLorebook = currentLorebook.copy(entries = currentLorebook.entries + entryForCreate)
                        current.copy(
                            lorebooks = current.lorebooks.map { lb -> if (lb.id == currentLorebook.id) updatedLorebook else lb }
                        )
                    }
                }

                val entry = savedEntry
                    ?: return@Tool error("lorebook_update_failed", "Failed to update lorebook")

                val revisionId = revisionRepo.record(
                    lorebookId = lorebook.id.toString(),
                    assistantId = assistant.id.toString(),
                    conversationId = conversationId.toString(),
                    action = LorebookEntryRevisionRepository.Action.CREATE,
                    entryId = entry.id.toString(),
                    entryTitle = entry.name,
                    entryIndex = newEntryIndex,
                    before = null,
                    after = entry,
                )

                buildJsonObject {
                    put("ok", true)
                    put("entry_id", entry.id.toString())
                    put("revision_id", revisionId)
                }
            },
        )
    }

    private fun createEntryUpdateTool(
        assistant: Assistant,
        conversationId: Uuid,
        settingsSnapshot: Settings,
        settingsStore: SettingsStore,
        embeddingService: EmbeddingService,
        revisionRepo: LorebookEntryRevisionRepository,
    ): Tool {
        return Tool(
            name = "lorebooks_entry_update",
            description = "Update a lorebook entry.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("lorebook_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Target lorebook id.")
                        })
                        put("entry_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Target entry id.")
                        })
                        put("title", buildJsonObject {
                            put("type", "string")
                            put("description", "New title (optional).")
                        })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "New content (optional).")
                        })
                        put("trigger_type", buildJsonObject {
                            put("type", "string")
                            put("description", "Trigger type: always|keywords|rag (optional).")
                        })
                    },
                    required = listOf("lorebook_id", "entry_id"),
                )
            },
            execute = { args ->
                val obj = args.jsonObject
                val lorebookId = obj["lorebook_id"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?: return@Tool error("missing_lorebook_id", "lorebook_id is required")
                val entryId = obj["entry_id"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?: return@Tool error("missing_entry_id", "entry_id is required")

                val title = obj["title"]?.jsonPrimitiveOrNull?.contentOrNull
                val content = obj["content"]?.jsonPrimitiveOrNull?.contentOrNull
                val triggerTypeRaw = obj["trigger_type"]?.jsonPrimitiveOrNull?.contentOrNull

                if (title == null && content == null && triggerTypeRaw == null) {
                    return@Tool error("no_fields_to_update", "At least one of title/content/trigger_type must be provided")
                }

                val activationTypeOverride = triggerTypeRaw?.let(::parseActivationType)
                if (triggerTypeRaw != null && activationTypeOverride == null) {
                    return@Tool error("invalid_trigger_type", "trigger_type must be one of: always|keywords|rag")
                }

                val settings = currentSettings(settingsSnapshot, settingsStore)
                val lorebook = resolveEnabledLorebookOrNull(settings, assistant, lorebookId)
                    ?: return@Tool error("lorebook_not_enabled_for_assistant", "Lorebook is not enabled for this assistant")

                val entryIdStr = entryId.trim()

                var before: LorebookEntry? = null
                var after: LorebookEntry? = null
                var entryIndex: Int? = null

                val updated = withContext(Dispatchers.IO) {
                    val currentLorebook = resolveEnabledLorebookOrNull(currentSettings(settingsSnapshot, settingsStore), assistant, lorebookId)
                        ?: return@withContext false
                    val index = currentLorebook.entries.indexOfFirst { it.id.toString() == entryIdStr }
                    if (index == -1) return@withContext false
                    val existing = currentLorebook.entries[index]
                    entryIndex = index
                    before = existing

                    val originalTitle = existing.name
                    val patchedTitle = title ?: existing.name
                    val patchedPrompt = content ?: existing.prompt
                    val patchedActivation = activationTypeOverride ?: existing.activationType

                    var patched = existing.copy(
                        name = patchedTitle,
                        prompt = patchedPrompt,
                        activationType = patchedActivation,
                    )

                    patched = applyKeywordsDefaultsIfNeeded(
                        existing = existing,
                        patched = patched,
                        originalTitle = originalTitle,
                        titleProvided = title != null,
                        triggerTypeProvided = activationTypeOverride != null,
                    )

                    patched = applyEmbeddingIfNeeded(
                        existing = existing,
                        patched = patched,
                        activationTypeOverride = activationTypeOverride,
                        contentProvided = content != null,
                        embeddingService = embeddingService,
                    ) ?: return@withContext false

                    after = patched
                    var applied = false
                    settingsStore.update { current ->
                        val lb = resolveEnabledLorebookOrNull(current, assistant, lorebookId) ?: return@update current
                        val i = lb.entries.indexOfFirst { it.id.toString() == entryIdStr }
                        if (i == -1) return@update current
                        applied = true
                        val updatedEntries = lb.entries.toMutableList().apply { this[i] = patched }
                        val updatedLorebook = lb.copy(entries = updatedEntries)
                        current.copy(
                            lorebooks = current.lorebooks.map { item -> if (item.id == lb.id) updatedLorebook else item }
                        )
                    }
                    applied
                }

                val beforeEntry = before
                    ?: return@Tool error("entry_not_found", "Entry not found")
                val afterEntry = after
                    ?: return@Tool error("lorebook_update_failed", "Failed to update entry")

                if (!updated) {
                    return@Tool error("lorebook_update_failed", "Failed to update entry")
                }

                val revisionId = revisionRepo.record(
                    lorebookId = lorebook.id.toString(),
                    assistantId = assistant.id.toString(),
                    conversationId = conversationId.toString(),
                    action = LorebookEntryRevisionRepository.Action.UPDATE,
                    entryId = entryIdStr,
                    entryTitle = afterEntry.name,
                    entryIndex = entryIndex,
                    before = beforeEntry,
                    after = afterEntry,
                )

                buildJsonObject {
                    put("ok", true)
                    put("revision_id", revisionId)
                }
            },
        )
    }

    private fun createEntryDeleteTool(
        assistant: Assistant,
        conversationId: Uuid,
        settingsSnapshot: Settings,
        settingsStore: SettingsStore,
        revisionRepo: LorebookEntryRevisionRepository,
    ): Tool {
        return Tool(
            name = "lorebooks_entry_delete",
            description = "Delete a lorebook entry.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("lorebook_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Target lorebook id.")
                        })
                        put("entry_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Target entry id.")
                        })
                    },
                    required = listOf("lorebook_id", "entry_id"),
                )
            },
            execute = { args ->
                val obj = args.jsonObject
                val lorebookId = obj["lorebook_id"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?: return@Tool error("missing_lorebook_id", "lorebook_id is required")
                val entryId = obj["entry_id"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?: return@Tool error("missing_entry_id", "entry_id is required")

                val settings = currentSettings(settingsSnapshot, settingsStore)
                val lorebook = resolveEnabledLorebookOrNull(settings, assistant, lorebookId)
                    ?: return@Tool error("lorebook_not_enabled_for_assistant", "Lorebook is not enabled for this assistant")

                val entryIdStr = entryId.trim()

                var deletedEntry: LorebookEntry? = null
                var deletedIndex: Int? = null

                withContext(Dispatchers.IO) {
                    settingsStore.update { current ->
                        val lb = resolveEnabledLorebookOrNull(current, assistant, lorebookId) ?: return@update current
                        val index = lb.entries.indexOfFirst { it.id.toString() == entryIdStr }
                        if (index == -1) return@update current
                        deletedIndex = index
                        deletedEntry = lb.entries[index]
                        val updatedLorebook = lb.copy(entries = lb.entries.filterNot { it.id.toString() == entryIdStr })
                        current.copy(lorebooks = current.lorebooks.map { item -> if (item.id == lb.id) updatedLorebook else item })
                    }
                }

                val before = deletedEntry ?: return@Tool error("entry_not_found", "Entry not found")

                val revisionId = revisionRepo.record(
                    lorebookId = lorebook.id.toString(),
                    assistantId = assistant.id.toString(),
                    conversationId = conversationId.toString(),
                    action = LorebookEntryRevisionRepository.Action.DELETE,
                    entryId = entryIdStr,
                    entryTitle = before.name,
                    entryIndex = deletedIndex,
                    before = before,
                    after = null,
                )

                buildJsonObject {
                    put("ok", true)
                    put("revision_id", revisionId)
                }
            },
        )
    }

    private fun createHistoryListTool(
        assistant: Assistant,
        settingsSnapshot: Settings,
        settingsStore: SettingsStore,
        revisionRepo: LorebookEntryRevisionRepository,
    ): Tool {
        return Tool(
            name = "lorebooks_history_list",
            description = "List lorebook revision history.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("lorebook_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Target lorebook id.")
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("description", "Max items, 1-100. Default 50."
                            )
                        })
                    },
                    required = listOf("lorebook_id"),
                )
            },
            execute = { args ->
                val obj = args.jsonObject
                val lorebookId = obj["lorebook_id"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?: return@Tool error("missing_lorebook_id", "lorebook_id is required")
                val limit = obj["limit"]?.jsonPrimitiveOrNull?.intOrNull?.coerceIn(1, 100) ?: 50

                val settings = currentSettings(settingsSnapshot, settingsStore)
                val lorebook = resolveEnabledLorebookOrNull(settings, assistant, lorebookId)
                    ?: return@Tool error("lorebook_not_enabled_for_assistant", "Lorebook is not enabled for this assistant")

                val revisions = revisionRepo.getRecentByLorebook(lorebook.id.toString(), limit)
                buildJsonObject {
                    put("ok", true)
                    put(
                        "items",
                        buildJsonArray {
                            revisions.forEach { rev ->
                                add(
                                    buildJsonObject {
                                        put("revision_id", rev.id)
                                        put("action", rev.action)
                                        put("entry_id", rev.entryId)
                                        put("title_snapshot", rev.entryTitle)
                                        put("created_at", rev.createdAt)
                                        put("undone", JsonPrimitive(rev.undoneAt != null))
                                    }
                                )
                            }
                        }
                    )
                }
            },
        )
    }

    private fun createHistoryUndoTool(
        assistant: Assistant,
        settingsSnapshot: Settings,
        settingsStore: SettingsStore,
        revisionRepo: LorebookEntryRevisionRepository,
    ): Tool {
        return Tool(
            name = "lorebooks_history_undo",
            description = "Undo a lorebook revision.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        put("lorebook_id", buildJsonObject {
                            put("type", "string")
                            put("description", "Target lorebook id.")
                        })
                        put("revision_id", buildJsonObject {
                            put("type", "integer")
                            put("description", "Optional revision id to undo."
                            )
                        })
                    },
                    required = listOf("lorebook_id"),
                )
            },
            execute = { args ->
                val obj = args.jsonObject
                val lorebookId = obj["lorebook_id"]?.jsonPrimitiveOrNull?.contentOrNull
                    ?: return@Tool error("missing_lorebook_id", "lorebook_id is required")
                val revisionId = obj["revision_id"]?.jsonPrimitiveOrNull?.intOrNull

                val settings = currentSettings(settingsSnapshot, settingsStore)
                val lorebook = resolveEnabledLorebookOrNull(settings, assistant, lorebookId)
                    ?: return@Tool error("lorebook_not_enabled_for_assistant", "Lorebook is not enabled for this assistant")

                when (val result = revisionRepo.undo(lorebook.id.toString(), revisionId)) {
                    is LorebookEntryRevisionRepository.UndoResult.Success -> buildJsonObject {
                        put("ok", true)
                        put("undone_revision_id", result.revisionId)
                    }

                    is LorebookEntryRevisionRepository.UndoResult.Failure -> buildJsonObject {
                        put("ok", false)
                        put("error_code", result.code)
                        put("error", result.message)
                    }
                }
            },
        )
    }

    private fun currentSettings(settingsSnapshot: Settings, settingsStore: SettingsStore): Settings {
        val current = settingsStore.settingsFlow.value
        return if (current.init) settingsSnapshot else current
    }

    private fun resolveEnabledLorebooks(settings: Settings, assistant: Assistant): List<Lorebook> {
        if (assistant.enabledLorebookIds.isEmpty()) return emptyList()
        return settings.lorebooks.filter { lorebook ->
            lorebook.enabled && assistant.enabledLorebookIds.contains(lorebook.id)
        }
    }

    private fun resolveEnabledLorebookOrNull(
        settings: Settings,
        assistant: Assistant,
        lorebookId: String,
    ): Lorebook? {
        if (lorebookId.isBlank()) return null
        return resolveEnabledLorebooks(settings, assistant).firstOrNull { it.id.toString() == lorebookId }
    }

    private fun parseActivationType(raw: String): LorebookActivationType? {
        return when (raw.trim().lowercase()) {
            "always" -> LorebookActivationType.ALWAYS
            "keywords" -> LorebookActivationType.KEYWORDS
            "rag" -> LorebookActivationType.RAG
            else -> null
        }
    }

    private fun error(code: String, message: String): JsonObject {
        return buildJsonObject {
            put("ok", false)
            put("error_code", code)
            put("error", message)
        }
    }

    private suspend fun buildEntryForCreate(
        title: String,
        content: String,
        activationType: LorebookActivationType,
        embeddingService: EmbeddingService,
    ): LorebookEntry? {
        var entry = LorebookEntry(
            name = title,
            prompt = content,
            activationType = activationType,
        )

        if (activationType == LorebookActivationType.KEYWORDS) {
            entry = entry.copy(keywords = listOf(title))
        }

        if (activationType != LorebookActivationType.RAG) return entry
        if (content.isBlank()) return null

        return runCatching {
            val embeddingResult = withContext(Dispatchers.IO) {
                embeddingService.embedWithModelId(content)
            }
            entry.copy(
                embedding = embeddingResult.embeddings.firstOrNull(),
                hasEmbedding = true,
                embeddingModelId = embeddingResult.modelId,
            )
        }.getOrNull()
    }

    private fun applyKeywordsDefaultsIfNeeded(
        existing: LorebookEntry,
        patched: LorebookEntry,
        originalTitle: String,
        titleProvided: Boolean,
        triggerTypeProvided: Boolean,
    ): LorebookEntry {
        val patchedTitle = patched.name

        if (triggerTypeProvided && patched.activationType == LorebookActivationType.KEYWORDS) {
            return patched.copy(keywords = listOf(patchedTitle))
        }

        if (!titleProvided) return patched
        if (existing.activationType != LorebookActivationType.KEYWORDS || patched.activationType != LorebookActivationType.KEYWORDS) {
            return patched
        }

        val looksDefault = existing.keywords == listOf(originalTitle) || existing.keywords.isEmpty()
        if (!looksDefault) return patched

        return patched.copy(keywords = listOf(patchedTitle))
    }

    private suspend fun applyEmbeddingIfNeeded(
        existing: LorebookEntry,
        patched: LorebookEntry,
        activationTypeOverride: LorebookActivationType?,
        contentProvided: Boolean,
        embeddingService: EmbeddingService,
    ): LorebookEntry? {
        val activationType = patched.activationType

        if (activationType != LorebookActivationType.RAG) {
            return if (existing.embedding == null && !existing.hasEmbedding && existing.embeddingModelId == null) {
                patched
            } else {
                patched.copy(embedding = null, hasEmbedding = false, embeddingModelId = null)
            }
        }

        if (patched.prompt.isBlank()) return null

        val shouldRecompute = contentProvided || activationTypeOverride == LorebookActivationType.RAG || patched.embedding.isNullOrEmpty()
        if (!shouldRecompute) return patched

        return runCatching {
            val embeddingResult = embeddingService.embedWithModelId(patched.prompt)
            patched.copy(
                embedding = embeddingResult.embeddings.firstOrNull(),
                hasEmbedding = true,
                embeddingModelId = embeddingResult.modelId,
            )
        }.getOrNull()
    }
}
