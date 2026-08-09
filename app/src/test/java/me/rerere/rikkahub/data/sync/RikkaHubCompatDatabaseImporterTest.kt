package me.rerere.rikkahub.data.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class RikkaHubCompatDatabaseImporterTest {
    @Test
    fun `compat message nodes restore selected message and local file paths`() {
        val first = UIMessage(
            id = Uuid.parse("10000000-0000-0000-0000-000000000001"),
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text("first")),
        )
        val second = UIMessage(
            id = Uuid.parse("10000000-0000-0000-0000-000000000002"),
            role = MessageRole.USER,
            parts = listOf(
                UIMessagePart.Text(
                    "![image](file:///data/data/me.rerere.rikkahub/files/upload/picture.png)"
                ),
                UIMessagePart.Image(
                    "file:///data/data/me.rerere.rikkahub/files/upload/picture.png"
                ),
            ),
        )
        val upstreamMessages = toUpstreamMessages(listOf(first, second))

        val result = RikkaHubCompatDatabaseImporter.convertMessageNodes(
            rows = listOf(
                RikkaHubCompatDatabaseImporter.MessageNodeRow(
                    id = "20000000-0000-0000-0000-000000000001",
                    messagesJson = upstreamMessages.toString(),
                    selectIndex = 1,
                )
            ),
            currentFilesDir = "/data/user/0/lastchat.rikkafork.cocolal.exp/files",
        )

        assertEquals(1, result.totalRows)
        assertEquals(0, result.skippedRows)
        val nodes = JsonInstant.decodeFromString<List<MessageNode>>(result.nodesJson)
        assertEquals(1, nodes.size)
        assertEquals(1, nodes.single().selectIndex)
        val selected = nodes.single().messages[1]
        val text = selected.parts.filterIsInstance<UIMessagePart.Text>().single().text
        val image = selected.parts.filterIsInstance<UIMessagePart.Image>().single().url
        assertTrue(text.contains("file:///data/user/0/lastchat.rikkafork.cocolal.exp/files/upload/picture.png"))
        assertEquals(
            "file:///data/user/0/lastchat.rikkafork.cocolal.exp/files/upload/picture.png",
            image,
        )
    }

    @Test
    fun `invalid compat node is skipped without dropping valid nodes`() {
        val message = UIMessage(
            id = Uuid.parse("10000000-0000-0000-0000-000000000003"),
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("ok")),
        )
        val result = RikkaHubCompatDatabaseImporter.convertMessageNodes(
            rows = listOf(
                RikkaHubCompatDatabaseImporter.MessageNodeRow(
                    id = "broken",
                    messagesJson = "not-json",
                    selectIndex = 0,
                ),
                RikkaHubCompatDatabaseImporter.MessageNodeRow(
                    id = "20000000-0000-0000-0000-000000000002",
                    messagesJson = toUpstreamMessages(listOf(message)).toString(),
                    selectIndex = 0,
                ),
            ),
            currentFilesDir = "/data/user/0/lastchat.rikkafork.cocolal/files",
        )

        assertEquals(2, result.totalRows)
        assertEquals(1, result.skippedRows)
        val nodes = JsonInstant.decodeFromString<List<MessageNode>>(result.nodesJson)
        assertEquals("ok", (nodes.single().messages.single().parts.single() as UIMessagePart.Text).text)
    }

    private fun toUpstreamMessages(messages: List<UIMessage>): JsonArray {
        val current = JsonInstant.parseToJsonElement(JsonInstant.encodeToString(messages)) as JsonArray
        return JsonArray(current.map { messageElement ->
            val message = messageElement as JsonObject
            val parts = message["parts"] as JsonArray
            JsonObject(message.toMutableMap().apply {
                this["parts"] = JsonArray(parts.map { partElement ->
                    val part = partElement as JsonObject
                    val localType = part["type"]?.jsonPrimitiveOrNull?.contentOrNull.orEmpty()
                    JsonObject(part.toMutableMap().apply {
                        this["type"] = JsonPrimitive(
                            when {
                                localType.endsWith(".Text") -> "text"
                                localType.endsWith(".Image") -> "image"
                                else -> error("Unexpected part type: $localType")
                            }
                        )
                    })
                })
            })
        })
    }
}
