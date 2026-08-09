package me.rerere.rikkahub.data.sync

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import java.io.File
import kotlin.uuid.Uuid

internal object RikkaHubCompatDatabaseImporter {
    private const val TAG = "RikkaHubCompatImport"
    private const val CONVERSATION_TABLE = "ConversationEntity"
    private const val MESSAGE_NODE_TABLE = "message_node"

    private val upstreamFilesRoots = listOf(
        "/data/data/me.rerere.rikkahub/files",
        "/data/user/0/me.rerere.rikkahub/files",
    )
    private val fileUrlInTextRegex = Regex("""file:[^\s")\]]+""")
    private val localPartTypes = mapOf(
        "text" to "me.rerere.ai.ui.UIMessagePart.Text",
        "image" to "me.rerere.ai.ui.UIMessagePart.Image",
        "video" to "me.rerere.ai.ui.UIMessagePart.Video",
        "audio" to "me.rerere.ai.ui.UIMessagePart.Audio",
        "document" to "me.rerere.ai.ui.UIMessagePart.Document",
        "reasoning" to "me.rerere.ai.ui.UIMessagePart.Reasoning",
        "thinking" to "me.rerere.ai.ui.UIMessagePart.Reasoning",
        "tool_call" to "me.rerere.ai.ui.UIMessagePart.ToolCall",
        "tool_result" to "me.rerere.ai.ui.UIMessagePart.ToolResult",
        "search" to "me.rerere.ai.ui.UIMessagePart.Search",
        "quoted_follow_up" to "quoted_follow_up",
    )

    internal data class MessageNodeRow(
        val id: String,
        val messagesJson: String,
        val selectIndex: Int,
    )

    internal data class NodeConversionResult(
        val nodesJson: String,
        val totalRows: Int,
        val skippedRows: Int,
    )

    fun isCompatDatabase(file: File): Boolean {
        if (!file.isFile) return false
        return runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use(::matches)
        }.onFailure {
            Log.w(TAG, "Failed to inspect restored database", it)
        }.getOrDefault(false)
    }

    fun matches(db: SQLiteDatabase): Boolean {
        return tableHasColumns(
            db = db,
            tableName = CONVERSATION_TABLE,
            required = setOf(
                "id",
                "assistant_id",
                "title",
                "create_at",
                "update_at",
                "suggestions",
                "is_pinned",
            ),
        ) && tableHasColumns(
            db = db,
            tableName = MESSAGE_NODE_TABLE,
            required = setOf(
                "id",
                "conversation_id",
                "node_index",
                "messages",
                "select_index",
            ),
        )
    }

    fun copyConversations(
        source: SQLiteDatabase,
        target: SupportSQLiteDatabase,
        currentFilesDir: String,
    ): DatabaseSanitizer.SanitizationResult {
        var conversations = 0
        var skippedConversations = 0
        var totalNodes = 0
        var skippedNodes = 0

        source.rawQuery(
            """
            SELECT id, assistant_id, title, create_at, update_at, suggestions, is_pinned
            FROM ConversationEntity
            """.trimIndent(),
            null,
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val assistantIdIndex = cursor.getColumnIndexOrThrow("assistant_id")
            val titleIndex = cursor.getColumnIndexOrThrow("title")
            val createAtIndex = cursor.getColumnIndexOrThrow("create_at")
            val updateAtIndex = cursor.getColumnIndexOrThrow("update_at")
            val suggestionsIndex = cursor.getColumnIndexOrThrow("suggestions")
            val pinnedIndex = cursor.getColumnIndexOrThrow("is_pinned")

            while (cursor.moveToNext()) {
                conversations++
                val values = try {
                    val id = cursor.getString(idIndex)
                    val conversion = convertMessageNodes(
                        rows = readMessageNodes(source, id),
                        currentFilesDir = currentFilesDir,
                    )
                    totalNodes += conversion.totalRows
                    skippedNodes += conversion.skippedRows

                    val values = ContentValues().apply {
                        put("id", id)
                        put("assistant_id", cursor.getString(assistantIdIndex))
                        put("title", cursor.getString(titleIndex))
                        put("nodes", conversion.nodesJson)
                        put("create_at", cursor.getLong(createAtIndex))
                        put("update_at", cursor.getLong(updateAtIndex))
                        put("suggestions", cursor.getString(suggestionsIndex) ?: "[]")
                        put("is_pinned", cursor.getInt(pinnedIndex))
                        put("root_id", id)
                    }
                    values
                } catch (e: Exception) {
                    skippedConversations++
                    Log.w(TAG, "Failed to convert RikkaHub conversation", e)
                    continue
                }

                check(
                    target.insert(
                        CONVERSATION_TABLE,
                        SQLiteDatabase.CONFLICT_REPLACE,
                        values,
                    ) != -1L
                ) { "Failed to insert converted RikkaHub conversation" }
            }
        }

        check(conversations == 0 || skippedConversations < conversations) {
            "All $conversations RikkaHub conversations were unreadable"
        }
        check(totalNodes == 0 || skippedNodes < totalNodes) {
            "All $totalNodes RikkaHub message nodes were invalid"
        }

        val skipped = skippedConversations + skippedNodes
        return DatabaseSanitizer.SanitizationResult(
            totalRows = conversations + totalNodes,
            skippedRows = skipped,
            issuesFixed = totalNodes - skippedNodes,
            details = buildString {
                append("Converted $conversations RikkaHub conversations and ")
                append("${totalNodes - skippedNodes} message nodes")
                if (skipped > 0) append("; skipped $skipped invalid rows")
            },
        )
    }

    internal fun convertMessageNodes(
        rows: List<MessageNodeRow>,
        currentFilesDir: String,
    ): NodeConversionResult {
        var skipped = 0
        val nodes = rows.mapNotNull { row ->
            runCatching {
                val messagesElement = JsonInstant.parseToJsonElement(row.messagesJson) as? JsonArray
                    ?: error("message_node.messages is not an array")
                val messages = mutableListOf<UIMessage>()
                var selectedIndex = 0

                messagesElement.forEachIndexed { oldIndex, element ->
                    val converted = convertMessage(element, currentFilesDir) ?: return@forEachIndexed
                    if (oldIndex == row.selectIndex) selectedIndex = messages.size
                    messages += converted
                }
                check(messages.isNotEmpty()) { "message node has no valid messages" }

                MessageNode(
                    id = runCatching { Uuid.parse(row.id) }.getOrElse { Uuid.random() },
                    messages = messages,
                    selectIndex = selectedIndex.coerceIn(messages.indices),
                )
            }.onFailure {
                skipped++
                Log.w(TAG, "Failed to convert RikkaHub message node ${row.id}", it)
            }.getOrNull()
        }
        return NodeConversionResult(
            nodesJson = JsonInstant.encodeToString(nodes),
            totalRows = rows.size,
            skippedRows = skipped,
        )
    }

    private fun readMessageNodes(
        source: SQLiteDatabase,
        conversationId: String,
    ): List<MessageNodeRow> {
        return source.rawQuery(
            """
            SELECT id, messages, select_index
            FROM message_node
            WHERE conversation_id = ?
            ORDER BY node_index
            """.trimIndent(),
            arrayOf(conversationId),
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val messagesIndex = cursor.getColumnIndexOrThrow("messages")
            val selectIndex = cursor.getColumnIndexOrThrow("select_index")
            val rows = ArrayList<MessageNodeRow>()
            while (cursor.moveToNext()) {
                rows += MessageNodeRow(
                    id = cursor.getString(idIndex),
                    messagesJson = cursor.getString(messagesIndex),
                    selectIndex = cursor.getInt(selectIndex),
                )
            }
            rows
        }
    }

    private fun convertMessage(element: JsonElement, currentFilesDir: String): UIMessage? {
        val message = element as? JsonObject ?: return null
        val rawParts = message["parts"] as? JsonArray ?: JsonArray(emptyList())
        val convertedParts = rawParts.mapNotNull { partElement ->
            convertPart(partElement, currentFilesDir)
        }
        val convertedMessage = JsonObject(
            message.toMutableMap().apply {
                this["parts"] = JsonArray(convertedParts)
            }
        )
        return runCatching {
            JsonInstant.decodeFromJsonElement<UIMessage>(convertedMessage)
        }.getOrNull()
    }

    private fun convertPart(element: JsonElement, currentFilesDir: String): JsonElement? {
        val part = element as? JsonObject ?: return null
        val rawType = part["type"]?.jsonPrimitiveOrNull?.contentOrNull ?: return null
        val localType = localPartTypes[rawType] ?: rawType.takeIf { '.' in it } ?: return null
        return JsonObject(buildMap {
            put("type", JsonPrimitive(localType))
            part.forEach { (key, value) ->
                when {
                    key == "type" -> Unit
                    rawType == "thinking" && key == "thinking" -> put("reasoning", value)
                    key == "url" -> {
                        val url = value.jsonPrimitiveOrNull?.contentOrNull
                        put("url", JsonPrimitive(remapFileUrl(url, currentFilesDir) ?: url))
                    }
                    key == "text" && localType.endsWith(".Text") -> {
                        val text = value.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
                        put("text", JsonPrimitive(rewriteFileUrlsInText(text, currentFilesDir)))
                    }
                    else -> put(key, value)
                }
            }
        })
    }

    private fun rewriteFileUrlsInText(text: String, currentFilesDir: String): String {
        if (!text.contains("file:")) return text
        return fileUrlInTextRegex.replace(text) { match ->
            remapFileUrl(match.value, currentFilesDir) ?: match.value
        }
    }

    private fun remapFileUrl(raw: String?, currentFilesDir: String): String? {
        if (raw.isNullOrBlank()) return null
        upstreamFilesRoots.forEach { root ->
            val prefixes = listOf("file://$root", "file:$root")
            prefixes.firstOrNull(raw::startsWith)?.let { prefix ->
                val suffix = raw.removePrefix(prefix)
                return "file://${currentFilesDir.trimEnd('/')}/${suffix.trimStart('/')}"
            }
        }
        return null
    }

    private fun tableHasColumns(
        db: SQLiteDatabase,
        tableName: String,
        required: Set<String>,
    ): Boolean {
        val exists = db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1",
            arrayOf(tableName),
        ).use { it.moveToFirst() }
        if (!exists) return false

        val columns = db.rawQuery("PRAGMA table_info(`$tableName`)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            buildSet {
                while (nameIndex >= 0 && cursor.moveToNext()) {
                    add(cursor.getString(nameIndex))
                }
            }
        }
        return columns.containsAll(required)
    }
}
