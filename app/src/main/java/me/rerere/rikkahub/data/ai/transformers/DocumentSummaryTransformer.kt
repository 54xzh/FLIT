package me.rerere.rikkahub.data.ai.transformers

import android.content.Context
import android.util.Log
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.common.cache.LruCache
import me.rerere.common.cache.SingleFileCacheStore
import me.rerere.document.DocxParser
import me.rerere.document.PdfParser
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.AIRequestLogManager
import me.rerere.rikkahub.data.ai.AIRequestSource
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.model.Assistant
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.days

private const val TAG = "DocumentSummaryTransformer"
private const val SHORT_CONTENT_THRESHOLD = 500
private const val MAX_CONTENT_FOR_SUMMARY = 8000

object DocumentSummaryTransformer : KoinComponent {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    private val cache by lazy {
        val context = get<Context>()
        val json = Json { allowStructuredMapKeys = true }
        val store = SingleFileCacheStore(
            file = File(context.cacheDir, "doc_summary_cache.json"),
            keySerializer = String.serializer(),
            valueSerializer = String.serializer(),
            json = json
        )
        LruCache(
            capacity = 64,
            store = store,
            deleteOnEvict = true,
            preloadFromStore = true,
            expireAfterWriteMillis = 3.days.inWholeMilliseconds,
        )
    }

    fun getCached(url: String): String? = cache.get(url)

    fun prewarm(part: UIMessagePart.Document, settings: Settings, assistant: Assistant) {
        if (cache.get(part.url) != null) return
        if (!inFlight.add(part.url)) return
        val appScope = get<AppScope>()
        appScope.launch(Dispatchers.IO) {
            runCatching {
                summarize(part, settings, assistant)
            }.onFailure {
                Log.w(TAG, "prewarm: background summarization failed for ${part.fileName}", it)
            }.also {
                inFlight.remove(part.url)
            }
        }
    }

    suspend fun summarize(
        part: UIMessagePart.Document,
        settings: Settings,
        assistant: Assistant,
    ): String {
        cache.get(part.url)?.let { return it }

        val file = part.url.toUri().toFile()
        val rawContent = parseContent(file, part.mime)

        if (rawContent.length < SHORT_CONTENT_THRESHOLD) {
            return wrapResult(part.fileName, rawContent, isSummary = false)
                .also { cache.put(part.url, it) }
        }

        val modelId = assistant.contextSummarizerModelId
            ?: assistant.summarizerModelId
            ?: assistant.chatModelId
            ?: settings.chatModelId
        val model = settings.findModelById(modelId)
        if (model == null) {
            Log.w(TAG, "summarize: no model found, falling back to truncated content")
            return wrapResult(part.fileName, rawContent.take(1000), isSummary = false)
                .also { cache.put(part.url, it) }
        }

        val summary = callModel(model, settings, part.fileName, rawContent)
        return wrapResult(part.fileName, summary, isSummary = true)
            .also { cache.put(part.url, it) }
    }

    private fun parseContent(file: File, mime: String): String {
        return when (mime) {
            "application/pdf" -> PdfParser.parserPdf(file)
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DocxParser.parse(file)
            else -> file.readText()
        }
    }

    private suspend fun callModel(
        model: Model,
        settings: Settings,
        fileName: String,
        rawContent: String,
    ): String {
        val providerSetting = model.findProvider(settings.providers)
            ?: error("No provider found for model ${model.displayName}")
        val provider = get<ProviderManager>().getProviderByType(providerSetting)

        val systemPrompt = """You are a document summarization assistant. Summarize the following document concisely.
Preserve key information: data, names, dates, numbers, and conclusions.
Respond in the same language as the document.""".trimIndent()

        val userPrompt = """File: $fileName

${rawContent.take(MAX_CONTENT_FOR_SUMMARY)}"""

        val requestMessages = listOf(
            UIMessage.system(systemPrompt),
            UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text(userPrompt))
            )
        )
        var requestBodyJson: String? = null
        val params = TextGenerationParams(
            model = model,
            onRequestBody = { requestBodyJson = it },
        )

        val startAt = System.currentTimeMillis()
        var failure: Throwable? = null
        var content = ""
        var rawResponseText = ""
        try {
            val result = provider.generateText(
                providerSetting = providerSetting,
                messages = requestMessages,
                params = params,
            )
            rawResponseText = result.rawResponse.orEmpty()
            content = result.choices.firstOrNull()?.message?.toText().orEmpty()
            if (content.isBlank()) content = "[ERROR, document summary failed]"
        } catch (t: Throwable) {
            failure = t
            throw t
        } finally {
            get<AIRequestLogManager>().logTextGeneration(
                source = AIRequestSource.DOCUMENT_SUMMARY,
                providerSetting = providerSetting,
                params = params,
                requestMessages = requestMessages,
                requestBodyJson = requestBodyJson,
                responseText = content,
                responseRawText = rawResponseText,
                stream = false,
                latencyMs = System.currentTimeMillis() - startAt,
                durationMs = System.currentTimeMillis() - startAt,
                error = failure,
            )
        }
        Log.i(TAG, "summarize: generated summary for $fileName (${content.length} chars)")
        return content
    }

    private fun wrapResult(fileName: String, content: String, isSummary: Boolean): String {
        val tag = if (isSummary) "file_summary" else "file_content"
        return """
            <$tag>
            File: $fileName
            $content
            </$tag>
            * The $tag tag contains ${if (isSummary) "an AI-generated summary" else "the content"} of a file the user uploaded, not the user's prompt.
        """.trimIndent()
    }
}
