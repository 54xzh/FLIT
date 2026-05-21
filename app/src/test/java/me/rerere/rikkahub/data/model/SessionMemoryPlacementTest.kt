package me.rerere.rikkahub.data.model

import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionMemoryPlacementTest {
    @Test
    fun fromToolValue_acceptsSupportedValues() {
        assertEquals(
            SessionMemoryPlacement.SYSTEM_PROMPT_AFTER,
            SessionMemoryPlacement.fromToolValue("SYSTEM_PROMPT_AFTER")
        )
        assertEquals(
            SessionMemoryPlacement.BEFORE_LATEST_MESSAGE,
            SessionMemoryPlacement.fromToolValue("before_latest_message")
        )
    }

    @Test
    fun fromToolValue_defaultsToBeforeLatestMessage() {
        assertEquals(
            SessionMemoryPlacement.BEFORE_LATEST_MESSAGE,
            SessionMemoryPlacement.fromToolValue(null)
        )
        assertEquals(
            SessionMemoryPlacement.BEFORE_LATEST_MESSAGE,
            SessionMemoryPlacement.fromToolValue("unknown")
        )
    }

    @Test
    fun sessionMemoryDecode_defaultsOldRecordsToBeforeLatestMessage() {
        val memories = JsonInstant.decodeFromString<List<SessionMemory>>(
            """[{"id":1,"content":"Keep this active.","createdAt":10,"updatedAt":20}]"""
        )

        assertEquals(SessionMemoryPlacement.BEFORE_LATEST_MESSAGE, memories.single().placement)
    }
}
