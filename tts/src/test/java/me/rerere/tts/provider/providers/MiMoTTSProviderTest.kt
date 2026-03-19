package me.rerere.tts.provider.providers

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class MiMoTTSProviderTest {
    @Test
    fun parseMiMoAudioBytes_decodesNestedAudioData() {
        val responseJson = """
            {
              "choices": [
                {
                  "message": {
                    "audio": {
                      "id": "audio_123",
                      "data": "AQIDBA=="
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        val audioBytes = parseMiMoAudioBytes(responseJson)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), audioBytes)
    }
}
