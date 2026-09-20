package me.rerere.rikkahub.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class GroupChatTextRoutingTest {
    private val seat1 = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val seat2 = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val seat3 = Uuid.parse("00000000-0000-0000-0000-000000000003")
    private val seat4 = Uuid.parse("00000000-0000-0000-0000-000000000004")
    private val candidates = listOf(seat1, seat2, seat3, seat4)
    private val fallback = listOf(seat1, seat2, seat3)

    @Test
    fun mapsSingleMemberNumberBackToSeatId() {
        val selected = resolveGroupChatTextSpeakerIds(
            text = """{"speakers":[2]}""",
            candidateSeatIds = candidates,
            fallbackSeatIds = fallback,
        )

        assertEquals(listOf(seat2), selected)
    }

    @Test
    fun mapsMemberNumbersBackToSeatIdsInOutputOrder() {
        val selected = resolveGroupChatTextSpeakerIds(
            text = """{"speakers":[3,1]}""",
            candidateSeatIds = candidates,
            fallbackSeatIds = fallback,
        )

        assertEquals(listOf(seat3, seat1), selected)
    }

    @Test
    fun removesDuplicatesAndLimitsSelectionToThreeMembers() {
        val selected = resolveGroupChatTextSpeakerIds(
            text = """{"speakers":[4,2,4,1,3]}""",
            candidateSeatIds = candidates,
            fallbackSeatIds = fallback,
        )

        assertEquals(listOf(seat4, seat2, seat1), selected)
    }

    @Test
    fun keepsValidNumbersWhenOutputAlsoContainsInvalidValues() {
        val selected = resolveGroupChatTextSpeakerIds(
            text = """{"speakers":[0,2,99,"3","Writer"]}""",
            candidateSeatIds = candidates,
            fallbackSeatIds = fallback,
        )

        assertEquals(listOf(seat2, seat3), selected)
    }

    @Test
    fun fallsBackWhenNoValidMemberNumberCanBeResolved() {
        val invalidOutputs = listOf(
            """{"speakers":[]}""",
            """{"speakers":[0,99]}""",
            """{"speakers":["Writer"]}""",
            """{"speakers":"1"}""",
            "not json",
        )

        invalidOutputs.forEach { output ->
            assertEquals(
                output,
                fallback,
                resolveGroupChatTextSpeakerIds(
                    text = output,
                    candidateSeatIds = candidates,
                    fallbackSeatIds = fallback,
                ),
            )
        }
    }

    @Test
    fun generatedRoutingInputUsesNumbersWithoutExposingSeatIds() {
        val candidateLines = buildGroupChatRoutingSeatLines(
            candidates = listOf(
                seat1 to "Writer",
                seat2 to "Reviewer",
            ),
        )
        val routingContext = buildString {
            appendLine("Members:")
            candidateLines.forEach { line -> appendLine(line) }
        }
        val prompt = buildGroupChatRouterPrompt("Prefer the most relevant specialist.")
        val questions = buildGroupChatDecisionQuestions(
            candidateSeatIds = listOf(seat1, seat2),
            seatDisplayNames = mapOf(seat1 to "Writer", seat2 to "Reviewer"),
        )
        val modelInput = prompt + routingContext + questions.toString()

        assertEquals(listOf("- 1: Writer", "- 2: Reviewer"), candidateLines)
        assertTrue(prompt.contains("{\"speakers\":[1, 2]}"))
        assertTrue(prompt.contains("Prefer the most relevant specialist."))
        assertFalse(prompt.contains("Writer"))
        assertTrue(routingContext.contains("Writer"))
        assertEquals(setOf("1", "2"), questions.keys)
        assertFalse(modelInput.contains(seat1.toString()))
        assertFalse(modelInput.contains(seat2.toString()))
    }
}
