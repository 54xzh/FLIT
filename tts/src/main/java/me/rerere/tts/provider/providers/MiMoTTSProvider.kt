package me.rerere.tts.provider.providers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.rerere.tts.model.AudioChunk
import me.rerere.tts.model.AudioFormat
import me.rerere.tts.model.TTSRequest
import me.rerere.tts.provider.TTSProvider
import me.rerere.tts.provider.TTSProviderSetting
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val TAG = "MiMoTTSProvider"

private val mimoJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

@Serializable
private data class MiMoTTSResponse(
    val choices: List<MiMoChoice> = emptyList()
)

@Serializable
private data class MiMoChoice(
    val message: MiMoMessage? = null
)

@Serializable
private data class MiMoMessage(
    val audio: MiMoAudio? = null
)

@Serializable
private data class MiMoAudio(
    val data: String? = null,
    val id: String? = null,
    @SerialName("expires_at")
    val expiresAt: Long? = null,
    val transcript: String? = null
)

internal fun parseMiMoAudioBytes(responseJson: String, json: Json = mimoJson): ByteArray {
    val response = json.decodeFromString<MiMoTTSResponse>(responseJson)
    val audioBase64 = response.choices.firstOrNull()?.message?.audio?.data
        ?: throw IllegalStateException("No audio data returned from MiMo TTS")
    return Base64.getDecoder().decode(audioBase64)
}

class MiMoTTSProvider : TTSProvider<TTSProviderSetting.MiMo> {
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun generateSpeech(
        context: Context,
        providerSetting: TTSProviderSetting.MiMo,
        request: TTSRequest
    ): Flow<AudioChunk> = flow {
        val requestBody = buildJsonObject {
            put("model", providerSetting.model)
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "assistant")
                        put("content", request.text)
                    }
                )
            }
            putJsonObject("audio") {
                put("format", "wav")
                put("voice", providerSetting.voice)
            }
        }

        Log.i(TAG, "generateSpeech: model=${providerSetting.model}, voice=${providerSetting.voice}")

        val httpRequest = Request.Builder()
            .url("${providerSetting.baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("api-key", providerSetting.apiKey)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                Log.e(TAG, "TTS request failed: ${response.code} $errorBody")
                throw Exception("MiMo TTS failed: $errorBody")
            }

            val responseJson = response.body?.string().orEmpty()
            val audioData = parseMiMoAudioBytes(responseJson)

            emit(
                AudioChunk(
                    data = audioData,
                    format = AudioFormat.WAV,
                    isLast = true,
                    metadata = mapOf(
                        "provider" to "mimo",
                        "model" to providerSetting.model,
                        "voice" to providerSetting.voice
                    )
                )
            )
        }
    }
}
