package me.rerere.rikkahub.data.datastore

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddingRetrievalTimeoutTest {
    @Test
    fun getEmbeddingRetrievalTimeoutMillis_defaultsTo2000() {
        assertEquals(2_000L, Settings().getEmbeddingRetrievalTimeoutMillis())
    }

    @Test
    fun getEmbeddingRetrievalTimeoutMillis_coercesValuesBelowOneSecond() {
        assertEquals(
            1_000L,
            Settings(displaySetting = DisplaySetting(embeddingRetrievalTimeoutMillis = 0L)).getEmbeddingRetrievalTimeoutMillis()
        )
        assertEquals(
            1_000L,
            Settings(displaySetting = DisplaySetting(embeddingRetrievalTimeoutMillis = -10L)).getEmbeddingRetrievalTimeoutMillis()
        )
    }

    @Test
    fun decimalSeconds_parseAndFormatAsMilliseconds() {
        assertEquals(1_500L, parseEmbeddingRetrievalTimeoutMillis("1.5"))
        assertEquals(1_500L, parseEmbeddingRetrievalTimeoutMillis("1,5"))
        assertEquals("1.5", formatEmbeddingRetrievalTimeoutSeconds(1_500L))
        assertEquals("2", formatEmbeddingRetrievalTimeoutSeconds(2_000L))
    }

    @Test
    fun decimalSeconds_rejectValuesBelowOneSecond() {
        assertEquals(null, parseEmbeddingRetrievalTimeoutMillis("0.9"))
        assertEquals(null, parseEmbeddingRetrievalTimeoutMillis(""))
    }

    @Test
    fun decodeDisplaySettingCompat_migratesLegacyIntegerSeconds() {
        val decoded = decodeDisplaySettingCompat("""{"embeddingRetrievalTimeoutSeconds":3}""")

        assertEquals(3_000L, decoded.embeddingRetrievalTimeoutMillis)
    }

    @Test
    fun decodeDisplaySettingCompat_prefersNewMilliseconds() {
        val decoded = decodeDisplaySettingCompat(
            """{"embeddingRetrievalTimeoutSeconds":3,"embeddingRetrievalTimeoutMillis":1500}"""
        )

        assertEquals(1_500L, decoded.embeddingRetrievalTimeoutMillis)
    }

    @Test
    fun settingsJsonMigration_preservesLegacyTimeoutInBackups() {
        val migrated = migrateLegacyEmbeddingRetrievalTimeoutSettingsJson(
            """{"displaySetting":{"embeddingRetrievalTimeoutSeconds":3}}"""
        )
        val decoded = me.rerere.rikkahub.utils.JsonInstant.parseToJsonElement(migrated).jsonObject
        val displaySetting = decoded.getValue("displaySetting").jsonObject

        assertEquals("3000", displaySetting.getValue("embeddingRetrievalTimeoutMillis").toString())
    }

    @Test
    fun displaySetting_useLastTurnMemoryOnSkip_defaultsToTrue() {
        assertEquals(true, DisplaySetting().useLastTurnMemoryOnSkip)
    }
}
