package me.rerere.rikkahub.data.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultAssistantTemperatureMigrationTest {
    @Test
    fun `migrates unchanged legacy default assistant to provider temperature`() {
        val legacyDefault = DEFAULT_ASSISTANTS.single().copy(temperature = 0.6f)

        val migrated = migrateLegacyDefaultAssistantTemperature(listOf(legacyDefault))

        assertNull(migrated.single().temperature)
    }

    @Test
    fun `keeps customized assistant temperature`() {
        val customized = DEFAULT_ASSISTANTS.single().copy(
            name = "Custom",
            temperature = 0.6f,
        )

        val migrated = migrateLegacyDefaultAssistantTemperature(listOf(customized))

        assertEquals(customized, migrated.single())
    }
}
