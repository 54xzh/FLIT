package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.ImageGenerationMethod
import me.rerere.ai.provider.ImageGenerationParams
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.ImageGenerationItem
import me.rerere.ai.ui.ImageSizeOptions
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.utils.createImageFileFromBase64
import me.rerere.rikkahub.utils.getImagesDir
import me.rerere.rikkahub.utils.jsonPrimitiveOrNull
import java.io.File

internal data class ImageGenerationToolRequest(
    val prompt: String,
    val aspectRatio: String,
    val count: Int,
)

internal sealed interface ImageGenerationToolSelection {
    data class Ready(
        val model: Model,
        val provider: ProviderSetting,
    ) : ImageGenerationToolSelection

    data class Error(val message: String) : ImageGenerationToolSelection
}

internal data class SavedGeneratedToolImage(
    val uri: String,
    val path: String,
    val markdownImage: String,
)

internal fun parseImageGenerationToolRequest(args: JsonElement): ImageGenerationToolRequest? {
    val params = args as? JsonObject ?: return null
    val prompt = params["prompt"]?.jsonPrimitiveOrNull?.contentOrNull?.trim().orEmpty()
    if (prompt.isBlank()) return null

    val aspectRatio = when (params["aspect_ratio"]?.jsonPrimitiveOrNull?.contentOrNull?.lowercase()) {
        "landscape", "wide", "16:9" -> "16:9"
        "portrait", "tall", "9:16" -> "9:16"
        "square", "1:1" -> "1:1"
        else -> "1:1"
    }
    val count = params["count"]?.jsonPrimitiveOrNull?.intOrNull?.coerceIn(1, 4) ?: 1
    return ImageGenerationToolRequest(
        prompt = prompt,
        aspectRatio = aspectRatio,
        count = count,
    )
}

internal fun resolveImageGenerationToolSelection(settings: Settings): ImageGenerationToolSelection {
    val model = settings.findModelById(settings.imageGenerationModelId)
        ?: return ImageGenerationToolSelection.Error(
            "No image generation model is selected. Choose one in Image Generation settings."
        )
    val provider = model.findProvider(settings.providers)
        ?: return ImageGenerationToolSelection.Error("The selected image generation provider was not found.")
    return ImageGenerationToolSelection.Ready(model = model, provider = provider)
}

internal fun buildImageGenerationToolError(message: String): JsonObject = buildJsonObject {
    put("success", false)
    put("error", message)
}

internal fun buildImageGenerationToolSuccess(images: List<SavedGeneratedToolImage>): JsonObject = buildJsonObject {
    put("success", true)
    put("saved_to_gallery", true)
    put("images", buildJsonArray {
        images.forEach { image ->
            add(buildJsonObject {
                put("uri", image.uri)
                put("path", image.path)
                put("markdown_image", image.markdownImage)
            })
        }
    })
    put("note", "Include images[].markdown_image in your reply so the generated image appears in chat.")
}

internal fun createImageGenerationTool(
    context: Context,
    settingsStore: SettingsStore,
    providerManager: ProviderManager,
    genMediaRepository: GenMediaRepository,
): Tool = Tool(
    name = "generate_image",
    description = "Generate an image with FLIT's selected image generation model and save it to the in-app image gallery. Use this only when the user asks for an image. Improve vague user requests into a concrete visual prompt before calling.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("prompt", buildJsonObject {
                    put("type", "string")
                    put("description", "Detailed visual prompt to generate")
                })
                put("aspect_ratio", buildJsonObject {
                    put("type", "string")
                    put("description", "square, landscape, or portrait")
                })
                put("count", buildJsonObject {
                    put("type", "integer")
                    put("description", "Number of images to generate, 1 to 4")
                })
            },
            required = listOf("prompt"),
        )
    },
    systemPrompt = { _, _ ->
        """
        ## Image generation tool
        When the user asks you to create, draw, render, or generate an image, call `generate_image`.
        - Rewrite short or vague requests into a richer visual prompt before calling the tool.
        - After the tool returns, include each returned `markdown_image` in your reply.
        - Do not call this tool for ordinary image analysis.
        """.trimIndent()
    },
    execute = { args ->
        val request = parseImageGenerationToolRequest(args)
            ?: return@Tool buildImageGenerationToolError("prompt is required")
        when (val selection = resolveImageGenerationToolSelection(settingsStore.settingsFlow.value)) {
            is ImageGenerationToolSelection.Error -> buildImageGenerationToolError(selection.message)
            is ImageGenerationToolSelection.Ready -> {
                try {
                    val generatedItems = generateImageItems(
                        request = request,
                        model = selection.model,
                        provider = selection.provider,
                        providerManager = providerManager,
                    )
                    if (generatedItems.isEmpty()) {
                        return@Tool buildImageGenerationToolError("The selected model did not return any images.")
                    }
                    val modelName = selection.model.displayName.ifBlank { selection.model.modelId }
                    val savedImages = generatedItems.take(request.count).mapIndexed { index, item ->
                        saveGeneratedToolImage(
                            context = context,
                            genMediaRepository = genMediaRepository,
                            item = item,
                            prompt = request.prompt,
                            modelName = modelName,
                            index = index,
                        )
                    }
                    buildImageGenerationToolSuccess(savedImages)
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    buildImageGenerationToolError(
                        exception.message?.takeIf { it.isNotBlank() } ?: "Image generation failed."
                    )
                }
            }
        }
    },
)

private suspend fun generateImageItems(
    request: ImageGenerationToolRequest,
    model: Model,
    provider: ProviderSetting,
    providerManager: ProviderManager,
): List<ImageGenerationItem> {
    return when (model.imageGenerationMethod ?: ImageGenerationMethod.DIFFUSION) {
        ImageGenerationMethod.DIFFUSION -> providerManager.getProviderByType(provider)
            .generateImage(
                providerSetting = provider,
                params = ImageGenerationParams(
                    model = model,
                    prompt = request.prompt,
                    numOfImages = request.count,
                    sizeOptions = ImageSizeOptions(aspectRatio = request.aspectRatio),
                    customHeaders = model.customHeaders,
                    customBody = model.customBodies,
                ),
            )
            .items

        ImageGenerationMethod.MULTIMODAL -> {
            val modelWithImageOutput = model.copy(outputModalities = model.outputModalities + Modality.IMAGE)
            providerManager.getProviderByType(provider)
                .generateText(
                    providerSetting = provider,
                    messages = listOf(UIMessage.user(request.prompt)),
                    params = TextGenerationParams(
                        model = modelWithImageOutput,
                        tools = emptyList(),
                        customHeaders = model.customHeaders,
                        customBody = model.customBodies,
                    ),
                )
                .choices
                .flatMap { choice -> choice.message?.parts.orEmpty() }
                .filterIsInstance<UIMessagePart.Image>()
                .map { part -> ImageGenerationItem(data = part.url, mimeType = "image/png") }
        }
    }
}

private suspend fun saveGeneratedToolImage(
    context: Context,
    genMediaRepository: GenMediaRepository,
    item: ImageGenerationItem,
    prompt: String,
    modelName: String,
    index: Int,
): SavedGeneratedToolImage = withContext(Dispatchers.IO) {
    val timestamp = System.currentTimeMillis()
    val safeModelName = modelName
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .take(48)
        .ifBlank { "image" }
    val imageFile = File(context.getImagesDir(), "${timestamp}_${safeModelName}_tool_$index.png")
    val createdFile = context.createImageFileFromBase64(item.data, imageFile.absolutePath)
    genMediaRepository.insertMedia(
        GenMediaEntity(
            path = "images/${createdFile.name}",
            modelId = modelName,
            prompt = prompt,
            createAt = timestamp,
        )
    )
    val uri = "file://${createdFile.absolutePath}"
    SavedGeneratedToolImage(
        uri = uri,
        path = createdFile.absolutePath,
        markdownImage = "![Generated image]($uri)",
    )
}
