package me.rerere.rikkahub.ui.components.message

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.providers.GoogleProvider
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WebSearchStatus
import me.rerere.ai.ui.handleMessageChunk
import me.rerere.rikkahub.data.ai.StreamUiUpdateGate
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.Pipe
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 通过真实 SSE 读取器分批写入事件；未发送结束包时就检查消息和显示计划。 */
class GoogleSearchStreamingTest {
    @Test
    fun `search and resumed reasoning are visible before the response or connection completes`() = runBlocking {
        verifyStream("gemini-4-argon") { stream ->
            stream.send("""{"index":0,"step":{"type":"thought"},"event_type":"step.start"}""")
            stream.send("""{"index":0,"delta":{"type":"thought_summary","content":{"type":"text","text":"搜索前"}},"event_type":"step.delta"}""")
            stream.await { it.parts.filterIsInstance<UIMessagePart.Reasoning>().any { part -> part.reasoning == "搜索前" } }
            stream.send("""{"index":1,"step":{"type":"google_search_call","id":"s1"},"event_type":"step.start"}""")
            val pending = stream.await { it.parts.any { part -> part is UIMessagePart.WebSearch } }
            assertEquals(WebSearchStatus.Searching, pending.parts.filterIsInstance<UIMessagePart.WebSearch>().single().status)
            stream.send("""{"index":1,"delta":{"type":"google_search_call","arguments":{"queries":["一","二","三","四"]}},"event_type":"step.delta"}""")
            val searching = stream.await { it.parts.filterIsInstance<UIMessagePart.WebSearch>().singleOrNull()?.queries?.size == 4 }
            val rows = visibleProcessParts(searching).expandGoogleSearchQueriesForDisplay()
            assertEquals(listOf("一", "二", "三", "四"), rows.filterIsInstance<UIMessagePart.WebSearch>().map { it.queries.single() })
            assertTrue(rows.filterIsInstance<UIMessagePart.WebSearch>().all { it.status == WebSearchStatus.Searching })
            assertFalse(searching.hasRenderableNonProcessParts())
            stream.send("""{"index":2,"step":{"type":"google_search_result","call_id":"s1"},"event_type":"step.start"}""")
            stream.send("""{"index":2,"event_type":"step.stop"}""")
            stream.await { it.parts.filterIsInstance<UIMessagePart.WebSearch>().singleOrNull()?.status == WebSearchStatus.Completed }
            // 同一 thought 的后续摘要也必须在搜索之后另起一段，而非追加到最前面的摘要。
            stream.send("""{"index":0,"delta":{"type":"thought_summary","content":{"type":"text","text":"搜索后"}},"event_type":"step.delta"}""")
            val resumed = stream.await { it.parts.filterIsInstance<UIMessagePart.Reasoning>().any { part -> part.reasoning == "搜索后" } }
            val process = visibleProcessParts(resumed).expandGoogleSearchQueriesForDisplay()
            assertEquals("搜索前", (process.first() as UIMessagePart.Reasoning).reasoning)
            assertEquals("搜索后", (process.last() as UIMessagePart.Reasoning).reasoning)
            assertEquals(4, process.filterIsInstance<UIMessagePart.WebSearch>().size)
            stream.send("""{"index":3,"step":{"type":"google_search_call","id":"s2","arguments":{"queries":["五"]}},"event_type":"step.start"}""")
            val secondCall = stream.await { it.parts.filterIsInstance<UIMessagePart.WebSearch>().size == 2 }
            val separateRows = visibleProcessParts(secondCall).expandGoogleSearchQueriesForDisplay()
            assertEquals("搜索后", (separateRows[5] as UIMessagePart.Reasoning).reasoning)
            assertEquals(listOf("五"), (separateRows.last() as UIMessagePart.WebSearch).queries)
            stream.send("""{"index":4,"step":{"type":"model_output"},"event_type":"step.start"}""")
            stream.send("""{"index":4,"delta":{"type":"text","text":"最终回答"},"event_type":"step.delta"}""")
            stream.await { it.toContentText() == "最终回答" }
        }
    }

    @Test
    fun `Agent Platform late search trace is delivered immediately without moving every phase to the top`() = runBlocking {
        verifyStream("gemini-3.8-flash") { stream ->
            // 保留用户提供的关键事件顺序：正文停止后，才发送搜索调用和查询。
            stream.send("""{"index":0,"step":{"type":"thought","summary":[{"type":"text","text":"准备查询"}]},"event_type":"step.start"}""")
            stream.send("""{"index":0,"event_type":"step.stop"}""")
            stream.send("""{"index":1,"step":{"type":"model_output"},"event_type":"step.start"}""")
            stream.send("""{"index":1,"delta":{"text":"今日新闻", "type":"text"},"event_type":"step.delta"}""")
            val answer = stream.await { it.toContentText() == "今日新闻" }
            assertTrue(answer.parts.none { it is UIMessagePart.WebSearch })
            stream.send("""{"index":1,"event_type":"step.stop"}""")
            stream.send("""{"index":2,"step":{"id":"xeobdf4s","signature":"","type":"google_search_call","search_type":"web_search"},"event_type":"step.start"}""")
            val started = stream.await { it.parts.filterIsInstance<UIMessagePart.WebSearch>().isNotEmpty() }
            assertEquals(WebSearchStatus.Searching, started.parts.filterIsInstance<UIMessagePart.WebSearch>().single().status)
            stream.send("""{"index":2,"delta":{"type":"google_search_call","arguments":{"queries":["新闻 2026年10月15日","news October 15 2026","2024年10月15日 新闻 焦点","头条新闻 2024年10月15日"]}},"event_type":"step.delta"}""")
            val queries = stream.await { it.parts.filterIsInstance<UIMessagePart.WebSearch>().singleOrNull()?.queries?.size == 4 }
            assertEquals(4, visibleProcessParts(queries).expandGoogleSearchQueriesForDisplay().filterIsInstance<UIMessagePart.WebSearch>().size)
            stream.send("""{"index":3,"step":{"type":"google_search_result","call_id":"xeobdf4s"},"event_type":"step.start"}""")
            stream.send("""{"index":3,"delta":{"type":"google_search_result","result":[{"search_suggestions":"<div>Google</div>"}],"is_error":false},"event_type":"step.delta"}""")
            stream.send("""{"index":3,"event_type":"step.stop"}""")
            val completedSearch = stream.await { it.parts.filterIsInstance<UIMessagePart.WebSearch>().singleOrNull()?.status == WebSearchStatus.Completed }
            assertEquals("今日新闻", completedSearch.toContentText())
            stream.send("""{"index":4,"step":{"type":"model_output"},"event_type":"step.start"}""")
            stream.send("""{"index":4,"delta":{"annotations":[{"url":"https://example.com","title":"Source","type":"url_citation"}],"type":"text_annotation_delta"},"event_type":"step.delta"}""")
            val cited = stream.await { it.annotations.isNotEmpty() }
            assertEquals("今日新闻", cited.toContentText())
            val blocks = buildMessageRenderBlocks(emptyList(), cited.parts)
            assertTrue(blocks[0] is MessageRenderBlock.ProcessGroup)
            assertTrue(blocks[1] is MessageRenderBlock.TextBlock)
            assertTrue(blocks[2] is MessageRenderBlock.ProcessGroup)
            assertEquals(1, blocks.filterIsInstance<MessageRenderBlock.TextBlock>().size)
        }
    }

    private fun visibleProcessParts(message: UIMessage): List<UIMessagePart> {
        val plan = planChatProcessDisplay(listOf(message.toMessageNode()), keepTrailingProcessOwnerVisible = false)
        val segments = plan.standaloneProcessSegmentsByIndex[0]
        val blocks = if (segments != null) buildMessageRenderBlocksFromSegments(segments)
            else buildMessageRenderBlocks(emptyList(), message.parts)
        return blocks.filterIsInstance<MessageRenderBlock.ProcessGroup>().flatMap { it.parts }
    }

    private class ScriptedStream(val pipe: Pipe, val updates: Channel<UIMessage>) {
        suspend fun send(event: String) = withContext(Dispatchers.IO) {
            val buffer = Buffer().writeUtf8("data: $event\n\n")
            pipe.sink.write(buffer, buffer.size)
            pipe.sink.flush()
        }

        suspend fun await(predicate: (UIMessage) -> Boolean): UIMessage = withTimeout(15_000) {
            var message = updates.receive()
            while (!predicate(message)) message = updates.receive()
            message
        }
    }

    private suspend fun verifyStream(modelId: String, assertions: suspend (ScriptedStream) -> Unit) = kotlinx.coroutines.coroutineScope {
        val pipe = Pipe(65_536)
        val requests = Channel<String>(Channel.UNLIMITED)
        val updates = Channel<UIMessage>(Channel.UNLIMITED)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = Buffer()
            chain.request().body?.writeTo(body)
            requests.trySend(body.readUtf8())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(pipe.source.buffer().asResponseBody("text/event-stream".toMediaType()))
                .build()
        }.build()
        val model = Model(modelId = modelId, abilities = listOf(ModelAbility.REASONING), tools = setOf(BuiltInTools.Search))
        val provider = GoogleProvider(client)
        val setting = ProviderSetting.Google(apiKey = "test", useInteractionsApi = true)
        val gate = StreamUiUpdateGate()
        var messages = listOf(UIMessage.user("查询新闻"))
        var finished = false
        val collector = launch {
            provider.streamText(setting, messages, TextGenerationParams(model = model)).collect { chunk ->
                messages = messages.handleMessageChunk(chunk, model)
                if (chunk.finishReasons.isNotEmpty()) finished = true
                if (gate.shouldEmit(messages, chunk.finishReasons)) updates.send(messages.last())
            }
        }
        val stream = ScriptedStream(pipe, updates)
        try {
            val request = withTimeout(15_000) { JsonInstant.parseToJsonElement(requests.receive()).jsonObject }
            assertEquals(true, request["stream"]?.jsonPrimitive?.booleanOrNull)
            assertions(stream)
            assertFalse("完成包还没发送，客户端不能提前结束", finished)
            assertTrue("搜索行必须在 SSE 连接仍打开时出现", collector.isActive)
            stream.send("""{"interaction":{"id":"test","status":"completed"},"event_type":"interaction.completed"}""")
            stream.send("[DONE]")
            withTimeout(15_000) { collector.join() }
            assertTrue(finished)
        } finally {
            withContext(Dispatchers.IO) { pipe.sink.close() }
            collector.cancelAndJoin()
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
        }
    }
}
