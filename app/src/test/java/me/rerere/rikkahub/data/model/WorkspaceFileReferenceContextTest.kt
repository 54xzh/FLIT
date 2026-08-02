package me.rerere.rikkahub.data.model

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class WorkspaceFileReferenceContextTest {
    @Test
    fun `writes workspace id while preserving provider metadata`() {
        val providerMetadata = buildJsonObject {
            put("provider_key", "provider-value")
        }
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Text(
                    text = "[报告](/workspace/output/report.pdf)",
                    metadata = providerMetadata,
                ),
                UIMessagePart.Text("普通正文"),
            ),
        )

        val updated = message.withWorkspaceFileReferenceContext(
            WorkspaceFileReferenceContext("workspace-original"),
        )
        val textPart = updated.parts.first() as UIMessagePart.Text

        assertEquals("provider-value", textPart.metadata?.get("provider_key")?.jsonPrimitive?.content)
        assertEquals(
            "workspace-original",
            textPart.metadata
                ?.get(WORKSPACE_FILE_REFERENCE_WORKSPACE_ID_METADATA_KEY)
                ?.jsonPrimitive
                ?.content,
        )
        assertNull((updated.parts[1] as UIMessagePart.Text).metadata)
        assertEquals(
            WorkspaceFileReferenceContext("workspace-original"),
            textPart.workspaceFileReferenceContextOrNull(),
        )
        val restored = JsonInstant.decodeFromString<UIMessage>(JsonInstant.encodeToString(updated))
        assertEquals(
            "workspace-original",
            (restored.parts.first() as UIMessagePart.Text)
                .workspaceFileReferenceContextOrNull()
                ?.workspaceId,
        )
    }

    @Test
    fun `does not rewrite a message without workspace links`() {
        val message = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(UIMessagePart.Text("普通正文")),
        )

        assertSame(
            message,
            message.withWorkspaceFileReferenceContext(WorkspaceFileReferenceContext("workspace-1")),
        )
    }
}
