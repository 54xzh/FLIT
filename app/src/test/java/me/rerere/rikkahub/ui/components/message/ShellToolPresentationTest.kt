package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellToolPresentationTest {
    @Test
    fun `command preview shows command without cwd`() {
        val arguments = buildJsonObject {
            put("command", "npm test")
            put("cwd", "/workspace/project")
        }

        assertEquals("npm test", shellCommandPreview(arguments))
    }

    @Test
    fun `blank or non-object arguments have no command preview`() {
        assertNull(shellCommandPreview(buildJsonObject { put("command", "  ") }))
        assertNull(shellCommandPreview(JsonPrimitive("npm test")))
    }

    @Test
    fun `successful command previews first two stdout lines`() {
        val content = buildJsonObject {
            put("exit_code", 0)
            put("stdout", "first\nsecond\nthird\n")
            put("stderr", "warning")
            put("timed_out", false)
        }

        assertEquals(
            ShellResultPreview("first\nsecond", ShellResultState.Success, 0),
            shellResultPreview(content),
        )
    }

    @Test
    fun `failed command prefers stderr`() {
        val content = buildJsonObject {
            put("exit_code", 1)
            put("stdout", "partial output")
            put("stderr", "failure detail")
            put("timed_out", false)
        }

        assertEquals(
            ShellResultPreview("failure detail", ShellResultState.Failed, 1),
            shellResultPreview(content),
        )
    }

    @Test
    fun `empty preferred stream falls back to other stream`() {
        val content = buildJsonObject {
            put("exit_code", 1)
            put("stdout", "useful fallback")
            put("stderr", "")
        }

        assertEquals(
            ShellResultPreview("useful fallback", ShellResultState.Failed, 1),
            shellResultPreview(content),
        )
    }

    @Test
    fun `preview removes terminal formatting and handles carriage returns`() {
        val content = buildJsonObject {
            put("exit_code", 0)
            put("stdout", "\u001B[31mold\u001B[0m\rnew\r\nfinished")
            put("stderr", "")
        }

        assertEquals(
            ShellResultPreview("old\nnew", ShellResultState.Success, 0),
            shellResultPreview(content),
        )
    }

    @Test
    fun `missing or blank output has no preview`() {
        assertEquals(
            ShellResultPreview(null, ShellResultState.Success, 0),
            shellResultPreview(buildJsonObject { put("exit_code", 0) }),
        )
        assertNull(shellResultPreview(JsonPrimitive("done")))
    }

    @Test
    fun `long output keeps the beginning`() {
        val content = buildJsonObject {
            put("exit_code", 0)
            put("stdout", "start-" + "x".repeat(400))
            put("stderr", "")
        }

        val preview = shellResultPreview(content)?.output.orEmpty()
        assertEquals(320, preview.length)
        assertEquals(true, preview.startsWith("start-"))
        assertEquals(true, preview.endsWith("..."))
    }

    @Test
    fun `tool execution error previews the readable beginning`() {
        val content = buildJsonObject {
            put("error", "[java.lang.IllegalStateException] Rootfs is not ready\nstack line 1\nstack line 2")
        }

        assertEquals(
            ShellResultPreview(
                "[java.lang.IllegalStateException] Rootfs is not ready\nstack line 1",
                ShellResultState.Failed,
                null,
            ),
            shellResultPreview(content),
        )
    }

    @Test
    fun `failed or timed out command without output keeps status`() {
        assertEquals(
            ShellResultPreview(null, ShellResultState.Failed, 127),
            shellResultPreview(buildJsonObject { put("exit_code", 127) }),
        )
        assertEquals(
            ShellResultPreview(null, ShellResultState.TimedOut, -1),
            shellResultPreview(
                buildJsonObject {
                    put("exit_code", -1)
                    put("timed_out", true)
                },
            ),
        )
    }
}
