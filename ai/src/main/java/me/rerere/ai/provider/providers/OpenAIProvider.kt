package me.rerere.ai.provider.providers

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.ModelCapabilitySource
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.openai.ChatCompletionsAPI
import me.rerere.ai.provider.providers.openai.OpenRouterModelCapabilityProvider
import me.rerere.ai.provider.providers.openai.ResponseAPI
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.ImageGenerationResult
import me.rerere.ai.ui.ImageQuality
import me.rerere.ai.ui.MessageChunk
import me.rerere.ai.ui.ResolutionTier
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.util.KeyRoulette
import me.rerere.ai.util.configureClientWithProxy
import me.rerere.ai.util.json
import me.rerere.ai.util.mergeCustomBody
import me.rerere.ai.util.toHeaders
import me.rerere.common.http.await
import me.rerere.common.http.getByKey
import me.rerere.common.http.jsonPrimitiveOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigDecimal
import java.util.concurrent.TimeUnit

class OpenAIProvider(
    private val client: OkHttpClient,
    private val openRouterModelCapabilityProvider: OpenRouterModelCapabilityProvider? = null,
) : Provider<ProviderSetting.OpenAI> {
    private val keyRoulette = KeyRoulette.default()

    private val chatCompletionsAPI = ChatCompletionsAPI(client = client, keyRoulette = keyRoulette)
    private val responseAPI = ResponseAPI(client = client, keyRoulette = keyRoulette)


    override suspend fun listModels(providerSetting: ProviderSetting.OpenAI): List<Model> =
        withContext(Dispatchers.IO) {
            val key = keyRoulette.next(providerSetting)
            
            // Fetch regular models
            val regularModels = fetchModelsFromUrl(
                url = "${providerSetting.baseUrl}/models",
                key = key,
                providerSetting = providerSetting
            )
            
            // For OpenRouter, also fetch embedding models using output_modalities filter
            // OpenRouter's /models endpoint doesn't return embedding models by default
            val isOpenRouter = providerSetting.baseUrl.contains("openrouter.ai", ignoreCase = true)
            val embeddingModels = if (isOpenRouter) {
                fetchModelsFromUrl(
                    url = "${providerSetting.baseUrl}/models?output_modalities=embeddings",
                    key = key,
                    providerSetting = providerSetting,
                    forceEmbeddingType = true
                )
            } else {
                emptyList()
            }
            
            // Combine and deduplicate by model ID
            val allModels = (regularModels + embeddingModels)
                .distinctBy { it.modelId }
            
            allModels
        }
    
    private suspend fun fetchModelsFromUrl(
        url: String,
        key: String,
        providerSetting: ProviderSetting.OpenAI,
        forceEmbeddingType: Boolean = false
    ): List<Model> {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .get()
            .build()

        val response = client.configureClientWithProxy(providerSetting.proxy).newCall(request).await()
        if (!response.isSuccessful) {
            // Don't fail completely if embedding endpoint fails, just return empty
            if (forceEmbeddingType) {
                return emptyList()
            }
            error("Failed to get models: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val data = bodyJson["data"]?.jsonArray ?: return emptyList()

        return data.mapNotNull { modelJson ->
            val modelObj = modelJson.jsonObject
            val id = modelObj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null

            // Check if model is embedding type via:
            // 1. Model ID contains "embed"
            // 2. architecture.modality contains "embedding" (OpenRouter format)
            // 3. architecture.output_modalities contains "embedding" (OpenRouter array format)
            // 4. Forced by forceEmbeddingType parameter (for OpenRouter embedding endpoint)
            val architecture = modelObj["architecture"]?.jsonObject
            val modality = architecture?.get("modality")?.jsonPrimitive?.contentOrNull
            val outputModalities = architecture?.get("output_modalities")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList()
            
            val isEmbedding = forceEmbeddingType ||
                id.contains("embed", ignoreCase = true) ||
                modality?.contains("embedding", ignoreCase = true) == true ||
                outputModalities.any { it.contains("embedding", ignoreCase = true) }
            
            // Extract icon URL if available (some APIs provide this)
            val iconUrl = modelObj["icon"]?.jsonPrimitive?.contentOrNull
                ?: architecture?.get("icon")?.jsonPrimitive?.contentOrNull
            
            // Extract provider slug from model ID (e.g., "anthropic/claude-3.5" -> "anthropic")
            // Used for LobeHub CDN icon lookup
            val providerSlug = if (id.contains("/")) id.substringBefore("/") else null
            
            val baseModel = Model(
                modelId = id,
                displayName = modelObj["name"]?.jsonPrimitive?.contentOrNull ?: id,
                type = if (isEmbedding) me.rerere.ai.provider.ModelType.EMBEDDING else me.rerere.ai.provider.ModelType.CHAT,
                outputModalities = listOf(me.rerere.ai.provider.Modality.TEXT),
                iconUrl = iconUrl,
                providerSlug = providerSlug
            )

            if (forceEmbeddingType) {
                baseModel
            } else {
                val capability = openRouterModelCapabilityProvider?.resolve(id)
                if (capability == null) {
                    baseModel
                } else {
                    baseModel.copy(
                        inputModalities = capability.inputModalities,
                        outputModalities = capability.outputModalities,
                        abilities = capability.abilities,
                        capabilitySource = ModelCapabilitySource.AUTO,
                    )
                }
            }
        }
    }

    override suspend fun getBalance(providerSetting: ProviderSetting.OpenAI): String = withContext(Dispatchers.IO) {
        val key = keyRoulette.next(providerSetting)
        val url = if (providerSetting.balanceOption.apiPath.startsWith("http")) {
            providerSetting.balanceOption.apiPath
        } else {
            "${providerSetting.baseUrl}${providerSetting.balanceOption.apiPath}"
        }
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .get()
            .build()
        val response = client.configureClientWithProxy(providerSetting.proxy).newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to get balance: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body.string()
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val value = bodyJson.getByKey(providerSetting.balanceOption.resultPath)
        val digitalValue = value.toFloatOrNull()
        if(digitalValue != null) {
            "%.2f".format(digitalValue)
        } else {
            value
        }
    }

    override suspend fun streamText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): Flow<MessageChunk> = if (providerSetting.useResponseApi) {
        responseAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.streamText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateText(
        providerSetting: ProviderSetting.OpenAI,
        messages: List<UIMessage>,
        params: TextGenerationParams
    ): MessageChunk = if (providerSetting.useResponseApi) {
        responseAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    } else {
        chatCompletionsAPI.generateText(
            providerSetting = providerSetting,
            messages = messages,
            params = params
        )
    }

    override suspend fun generateImage(
        providerSetting: ProviderSetting,
        params: ImageGenerationParams
    ): ImageGenerationResult = withContext(Dispatchers.IO) {
        require(providerSetting is ProviderSetting.OpenAI) {
            "Expected OpenAI provider setting"
        }

        val key = keyRoulette.next(providerSetting)

        val requestBody = json.encodeToString(
            buildJsonObject {
                put("model", params.model.modelId)
                put("prompt", params.prompt)
                val modelId = params.model.modelId
                val isGptImage2 = modelId.contains("gpt-image-2", ignoreCase = true)
                val isDalle3 = modelId.contains("dall-e-3", ignoreCase = true)
                // DALL-E 3 only supports n=1, 其它模型上限 10
                put("n", if (isDalle3) 1 else params.numOfImages.coerceIn(1, 10))
                put("response_format", "b64_json")
                // 尺寸: gpt-image-2 走像素换算表 + quality; DALL-E 3 走固定三档; DALL-E 2 写死方图
                val size = when {
                    isGptImage2 -> resolveGptImage2Size(
                        params.sizeOptions.aspectRatio,
                        params.sizeOptions.resolutionTier
                    )
                    isDalle3 -> when (params.sizeOptions.aspectRatio) {
                        "1:1" -> "1024x1024"
                        "16:9" -> "1792x1024"
                        "9:16" -> "1024x1792"
                        else -> "1024x1024"
                    }
                    else -> "1024x1024" // DALL-E 2 只支持方图
                }
                put("size", size)
                // quality 仅 gpt-image-2 等支持的模型才传
                if (isGptImage2) {
                    put("quality", params.sizeOptions.quality.toOpenAIQuality())
                }
            }.mergeCustomBody(params.customBody)
        )

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/images/generations")
            .headers(params.customHeaders.toHeaders())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.configureClientWithProxy(providerSetting.proxy).newCall(request).await()
        if (!response.isSuccessful) {
            error("Failed to generate image: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val data = bodyJson["data"]?.jsonArray ?: error("No data in response")

        val items = data.map { imageJson ->
            val imageObj = imageJson.jsonObject
            val b64Json = imageObj["b64_json"]?.jsonPrimitive?.contentOrNull
                ?: error("No b64_json in response")

            ImageGenerationItem(
                data = b64Json,
                mimeType = "image/png"
            )
        }

        ImageGenerationResult(items = items)
    }
    override suspend fun createEmbedding(
        providerSetting: ProviderSetting.OpenAI,
        input: List<String>,
        model: Model,
        callTimeoutSeconds: Long?,
    ): List<List<Float>> = withContext(Dispatchers.IO) {
        val key = keyRoulette.next(providerSetting)
        val requestBody = json.encodeToString(buildEmbeddingRequestBody(input = input, model = model))

        val request = Request.Builder()
            .url("${providerSetting.baseUrl}/embeddings")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val call = client.configureClientWithProxy(providerSetting.proxy).newCall(request)
        if (callTimeoutSeconds != null && callTimeoutSeconds > 0) {
            call.timeout().timeout(callTimeoutSeconds, TimeUnit.SECONDS)
        }
        val response = call.await()
        if (!response.isSuccessful) {
            error("Failed to create embedding: ${response.code} ${response.body?.string()}")
        }

        val bodyStr = response.body?.string() ?: ""
        val bodyJson = json.parseToJsonElement(bodyStr).jsonObject
        val data = bodyJson["data"]?.jsonArray ?: error("No data in response")

        data.map { item ->
            item.jsonObject["embedding"]?.jsonArray?.map { it.jsonPrimitive.content.toFloat() }
                ?: error("No embedding in response")
        }
    }

    override fun buildEmbeddingRequestBodyForLog(
        providerSetting: ProviderSetting.OpenAI,
        input: List<String>,
        model: Model,
    ): String {
        return json.encodeToString(buildEmbeddingRequestBody(input = input, model = model))
    }

    // gpt-image-2 支持任意满足约束的分辨率: 两边都是 16 的倍数、长边 < 3840、长宽比 ≤ 3:1、
    // 总像素在 655,360 ~ 8,294,400 之间。这里用「宽高比 × 分辨率档」查表给出常用合法像素。
    // > 2560×1440 (2K) 官方标注为实验性, 4K 档可能结果不太稳定。
    private fun resolveGptImage2Size(
        aspectRatio: String,
        tier: ResolutionTier,
    ): String {
        // 表内值均满足 gpt-image-2 的硬性约束
        val table = mapOf(
            "1:1" to mapOf(
                ResolutionTier.T1K to "1024x1024",
                ResolutionTier.T2K to "2048x2048",
                ResolutionTier.T4K to "2880x2880",
            ),
            "16:9" to mapOf(
                ResolutionTier.T1K to "1536x864",
                ResolutionTier.T2K to "2048x1152",
                // 长边必须严格 < 3840, 3840 不合法, 向下取最近的 16 倍数
                ResolutionTier.T4K to "3824x2144",
            ),
            "9:16" to mapOf(
                ResolutionTier.T1K to "864x1536",
                ResolutionTier.T2K to "1152x2048",
                ResolutionTier.T4K to "2144x3824",
            ),
            "4:3" to mapOf(
                ResolutionTier.T1K to "1280x960",
                ResolutionTier.T2K to "2048x1536",
                // 4K 档受总像素 ≤ 8,294,400 约束, 取 16 倍数且不超限
                ResolutionTier.T4K to "3264x2448",
            ),
            "3:4" to mapOf(
                ResolutionTier.T1K to "960x1280",
                ResolutionTier.T2K to "1536x2048",
                ResolutionTier.T4K to "2448x3264",
            ),
            "3:2" to mapOf(
                ResolutionTier.T1K to "1536x1024",
                ResolutionTier.T2K to "2048x1360",
                ResolutionTier.T4K to "3520x2336",
            ),
            "2:3" to mapOf(
                ResolutionTier.T1K to "1024x1536",
                ResolutionTier.T2K to "1360x2048",
                ResolutionTier.T4K to "2336x3520",
            ),
            "21:9" to mapOf(
                ResolutionTier.T1K to "2016x864",
                ResolutionTier.T2K to "2688x1152",
                // 长边必须严格 < 3840, 3840 不合法, 向下取最近的 16 倍数
                ResolutionTier.T4K to "3824x1648",
            ),
        )
        return table[aspectRatio]?.get(tier) ?: "1024x1024"
    }

    private fun buildEmbeddingRequestBody(input: List<String>, model: Model) = buildJsonObject {
        put("model", model.modelId)
        put(
            "input",
            kotlinx.serialization.json.JsonArray(input.map { kotlinx.serialization.json.JsonPrimitive(it) })
        )
    }
}
