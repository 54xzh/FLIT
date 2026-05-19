package me.rerere.rikkahub.data.datastore

import me.rerere.rikkahub.data.model.ToolResultHistoryMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolResultSettingsMigrationTest {
    @Test
    fun getToolResultKeepUserMessages_clampsToRange() {
        assertEquals(
            TOOL_RESULT_KEEP_USER_MESSAGES_MIN,
            Settings(displaySetting = DisplaySetting(toolResultKeepUserMessages = 0)).getToolResultKeepUserMessages()
        )
        assertEquals(
            20,
            Settings(displaySetting = DisplaySetting(toolResultKeepUserMessages = 20)).getToolResultKeepUserMessages()
        )
        assertEquals(
            TOOL_RESULT_KEEP_USER_MESSAGES_MAX,
            Settings(displaySetting = DisplaySetting(toolResultKeepUserMessages = 99)).getToolResultKeepUserMessages()
        )
    }

    @Test
    fun decodeDisplaySettingCompat_clampsLegacyKeepRange() {
        val decoded = decodeDisplaySettingCompat(
            """{"toolResultHistoryMode":"discard","toolResultKeepUserMessages":99}"""
        )

        assertEquals(ToolResultHistoryMode.DISCARD, decoded.toolResultHistoryMode)
        assertEquals(TOOL_RESULT_KEEP_USER_MESSAGES_MAX, decoded.toolResultKeepUserMessages)
    }

    @Test
    fun decodeDisplaySettingCompat_migratesRagModeToDiscard() {
        val decoded = decodeDisplaySettingCompat(
            """{"toolResultHistoryMode":"rag","toolResultKeepUserMessages":0}"""
        )

        assertEquals(ToolResultHistoryMode.DISCARD, decoded.toolResultHistoryMode)
        assertEquals(TOOL_RESULT_KEEP_USER_MESSAGES_MIN, decoded.toolResultKeepUserMessages)
    }
}
