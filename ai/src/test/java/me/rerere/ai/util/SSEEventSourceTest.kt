package me.rerere.ai.util

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SSEEventSourceTest {
    @Test
    fun `processResponse accepts text plain sse bodies`() {
        val events = mutableListOf<String>()
        var opened = false
        var closed = false
        var failure: Throwable? = null
        val source = SSEEventSource(
            request = request,
            listener = object : EventSourceListener() {
                override fun onOpen(eventSource: EventSource, response: Response) {
                    opened = true
                }

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    events += data
                }

                override fun onClosed(eventSource: EventSource) {
                    closed = true
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    failure = t
                }
            },
        )

        source.processResponse(
            response(
                body = "data: {\"choices\":[]}\n\n",
                contentType = "text/plain; charset=utf-8",
            )
        )

        assertTrue(opened)
        assertEquals(listOf("{\"choices\":[]}"), events)
        assertTrue(closed)
        assertNull(failure)
    }

    @Test
    fun `processResponse rejects non stream content types`() {
        var failure: Throwable? = null
        val source = SSEEventSource(
            request = request,
            listener = object : EventSourceListener() {
                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    failure = t
                }
            },
        )

        source.processResponse(
            response(
                body = "{\"ok\":true}",
                contentType = "application/json",
            )
        )

        assertTrue(failure is IllegalStateException)
    }

    private fun response(
        body: String,
        contentType: String,
    ): Response {
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody(contentType.toMediaType()))
            .build()
    }

    private companion object {
        val request: Request = Request.Builder()
            .url("https://example.com/chat/completions")
            .build()
    }
}
