package me.rerere.rikkahub.data.interactive

import kotlinx.serialization.json.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.*
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.sanitize
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class InteractiveHistoryTest {
    private val seat = Uuid.random()
    private fun card(surface: String = "preferences", seatId: Uuid? = seat): UIMessage = UIMessage(
        role = MessageRole.ASSISTANT, speakerSeatId = seatId,
        parts = listOf(UIMessagePart.Text("```a2ui\n{\"version\":\"v0.9.1\",\"createSurface\":{\"surfaceId\":\"$surface\",\"catalogId\":\"catalog\"}}\n```")),
    )

    @Test fun `latest version follows selected branch and group seat`() {
        val first = card()
        val second = card()
        val otherSeat = card(seatId = Uuid.random())
        val otherCard = card("different")
        assertEquals(second.id, latestInteractiveMessage(listOf(first, second, otherSeat, otherCard), first, "preferences")?.id)
        assertEquals(first.id, latestInteractiveMessage(listOf(first, otherSeat, otherCard), first, "preferences")?.id)
    }

    @Test fun `submission metadata becomes model content without changing saved display text`() {
        val event = buildJsonObject {
            put("sourceMessageId", "source"); put("partIndex", 0); put("blockOffset", 18); put("fingerprint", "hash")
            put("action", buildJsonObject { put("name", "submit"); put("context", buildJsonObject { put("Budget", 1500) }) })
        }
        val part = UIMessagePart.Text("Submitted: Budget\nBudget: 1500", metadata = buildJsonObject { put(INTERACTIVE_ACTION_METADATA, event) })
        val user = UIMessage(role = MessageRole.USER, parts = listOf(part))
        val assistant = card()
        val output = listOf(user, assistant).withInteractiveActionsForModel()
        assertEquals(part.text, (user.parts.single() as UIMessagePart.Text).text)
        assertEquals(part.text + "\n\n<a2ui_action>\n$event\n</a2ui_action>", (output.first().parts.single() as UIMessagePart.Text).text)
        assertEquals(assistant, output.last())
        assertTrue(interactiveSubmissionExists(listOf(user), "source", 0, 18, "hash"))
        assertFalse(interactiveSubmissionExists(listOf(user), "source", 0, 18, "edited"))
        assertFalse(interactiveSubmissionExists(listOf(assistant), "source", 0, 18, "hash"))
    }

    @Test fun `built in and user packages with same display name retain separate identity after cleanup`() {
        val builtin = BuiltInSkills.INTERACTIVE_COMPONENTS_ID
        val user = Skill("interactive-components", "My imported package")
        val skills = listOf(user).includingBuiltInSkills()
        assertEquals(2, skills.size)
        assertTrue(skills.single { it.name == builtin }.isBuiltIn)
        assertFalse(user.isBuiltIn)
        assertEquals("interactive-components", skills.last().packageName)
        val assistant = Assistant(enabledSkills = setOf(builtin, user.name, "removed-skill"))
        val settings = Settings(skills = listOf(user), assistants = listOf(assistant))
        assertEquals(setOf(builtin, user.name), settings.sanitize().first.assistants.single().enabledSkills)
    }
    @Test fun `new cards keep generating assistant identity while ordinary and historical messages retain theirs`() {
        val owner = Uuid.random()
        val old = card(seatId = null)
        val fresh = card(seatId = null)
        val prose = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("Hello")))
        val owned = listOf(old, fresh, prose).withInteractiveOwners(setOf(old.id), owner)
        assertEquals(old, owned[0])
        assertEquals(owner, owned[1].speakerAssistantId)
        assertEquals(prose, owned[2])
    }

    @Test fun `submission route follows latest user action and normal messages reset it`() {
        val owner = Uuid.random()
        val event = buildJsonObject { put("sourceAssistantId", owner.toString()); put("sourceSeatId", seat.toString()) }
        val submitted = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Submitted", metadata = buildJsonObject { put(INTERACTIVE_ACTION_METADATA, event) })))
        assertEquals(InteractiveRequestRoute(owner, seat), interactiveRequestRoute(listOf(submitted, card())))
        val normal = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Next question")))
        assertNull(interactiveRequestRoute(listOf(submitted, card(), normal)))
    }

}
