package me.rerere.rikkahub.service

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.uuid.Uuid

class GroupChatDecisionRoutingTest {
    private val seat1 = Uuid.parse("00000000-0000-0000-0000-000000000001")
    private val seat2 = Uuid.parse("00000000-0000-0000-0000-000000000002")
    private val seat3 = Uuid.parse("00000000-0000-0000-0000-000000000003")
    private val seat4 = Uuid.parse("00000000-0000-0000-0000-000000000004")

    @Test
    fun selectsAtMostThreeSpeakersAboveThresholdByProbability() {
        val answers = answers(0.9, 0.8, 0.7, 0.6)

        val selected = selectDecisionSpeakerIds(
            answers = answers,
            allowedSeatIds = listOf(seat1, seat2, seat3, seat4),
        )

        assertEquals(listOf(seat1, seat2, seat3), selected)
    }

    @Test
    fun fallsBackToHighestProbabilityWhenAllAnswersAreBelowThreshold() {
        val answers = answers(0.1, 0.4, 0.2, 0.3)

        val selected = selectDecisionSpeakerIds(
            answers = answers,
            allowedSeatIds = listOf(seat1, seat2, seat3, seat4),
        )

        assertEquals(listOf(seat2), selected)
    }

    @Test
    fun ignoresMalformedAndUnknownAnswers() {
        val answers = buildJsonObject {
            put(seat1.toString(), buildJsonObject { put("noul", 0.8) })
            put(seat2.toString(), buildJsonObject { put("choice", "yes") })
            put("00000000-0000-0000-0000-000000000099", buildJsonObject { put("noul", 1.0) })
        }

        val selected = selectDecisionSpeakerIds(
            answers = answers,
            allowedSeatIds = listOf(seat1, seat2),
        )

        assertEquals(listOf(seat1), selected)
    }

    @Test
    fun acceptsVercelProbabilityAnswers() {
        val answers = buildJsonObject {
            put(seat1.toString(), buildJsonObject { put("probability", 0.3) })
            put(seat2.toString(), buildJsonObject { put("probability", 0.9) })
        }

        val selected = selectDecisionSpeakerIds(
            answers = answers,
            allowedSeatIds = listOf(seat1, seat2),
        )

        assertEquals(listOf(seat2), selected)
    }

    private fun answers(vararg probabilities: Double) = buildJsonObject {
        listOf(seat1, seat2, seat3, seat4).zip(probabilities.toList()).forEach { (seatId, probability) ->
            put(
                seatId.toString(),
                buildJsonObject {
                    put("type", "noul")
                    put("noul", probability)
                },
            )
        }
    }
}
