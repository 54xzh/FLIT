package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolsJavascriptResultTest {
    @Test
    fun buildJavascriptToolResult_consoleOnly_keepsConsoleOutputWithoutResult() {
        val result = buildJavascriptToolResult(
            result = null,
            consoleOutput = "2\n",
        )

        assertFalse(result.containsKey("result"))
        assertEquals("2", result["console_output"]?.jsonPrimitive?.content)
    }

    @Test
    fun buildJavascriptToolResult_expressionOnly_keepsResult() {
        val result = buildJavascriptToolResult(
            result = 5,
            consoleOutput = "",
        )

        assertEquals("5", result["result"]?.jsonPrimitive?.content)
        assertFalse(result.containsKey("console_output"))
    }

    @Test
    fun buildJavascriptToolResult_consoleAndExpression_keepsBoth() {
        val result = buildJavascriptToolResult(
            result = true,
            consoleOutput = "{a: 1}\n",
        )

        assertEquals("true", result["result"]?.jsonPrimitive?.content)
        assertEquals("{a: 1}", result["console_output"]?.jsonPrimitive?.content)
    }
}
