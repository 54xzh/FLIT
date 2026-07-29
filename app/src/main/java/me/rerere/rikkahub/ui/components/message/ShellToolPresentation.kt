package me.rerere.rikkahub.ui.components.message

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

private const val SHELL_OUTPUT_PREVIEW_MAX_LINES = 2
private const val SHELL_OUTPUT_PREVIEW_MAX_CHARS = 320

private val AnsiCsiPattern = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
private val AnsiOscPattern = Regex("\u001B\\][^\u0007]*(?:\u0007|\u001B\\\\)")

internal fun shellCommandPreview(arguments: JsonElement): String? {
    return (arguments as? JsonObject)
        ?.get("command")
        ?.let { it as? JsonPrimitive }
        ?.contentOrNull
        ?.trim()
        ?.takeIf(String::isNotBlank)
}

internal enum class ShellResultState {
    Success,
    Failed,
    TimedOut,
}

internal data class ShellResultPreview(
    val output: String?,
    val state: ShellResultState,
    val exitCode: Int?,
)

internal fun shellResultPreview(content: JsonElement?): ShellResultPreview? {
    val result = content as? JsonObject ?: return null
    val stdout = result.stringValue("stdout")
    val stderr = result.stringValue("stderr")
    val error = result.stringValue("error")
    val exitCode = (result["exit_code"] as? JsonPrimitive)?.intOrNull
    val timedOut = (result["timed_out"] as? JsonPrimitive)?.booleanOrNull == true
    val state = when {
        timedOut -> ShellResultState.TimedOut
        error.isNotBlank() || (exitCode != null && exitCode != 0) -> ShellResultState.Failed
        else -> ShellResultState.Success
    }
    val preferredOutput = if (timedOut || (exitCode != null && exitCode != 0)) {
        stderr.takeIf(String::isNotBlank) ?: stdout
    } else {
        stdout.takeIf(String::isNotBlank) ?: stderr
    }
    val output = if (preferredOutput.isNotBlank()) {
        preferredOutput.toShellOutputPreview()
    } else {
        error.toShellOutputPreview()
    }
    return ShellResultPreview(output = output, state = state, exitCode = exitCode)
}

private fun JsonObject.stringValue(name: String): String {
    return nullableStringValue(name).orEmpty()
}

private fun JsonObject.nullableStringValue(name: String): String? =
    (get(name) as? JsonPrimitive)?.contentOrNull

private fun String.toShellOutputPreview(): String? {
    if (isBlank()) return null
    val withoutTerminalFormatting = replace(AnsiOscPattern, "")
        .replace(AnsiCsiPattern, "")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .filter { character ->
            character == '\n' || character == '\t' || !character.isISOControl()
        }
    val lines = withoutTerminalFormatting
        .lineSequence()
        .map(String::trimEnd)
        .filter(String::isNotBlank)
        .toList()
    val preview = lines
        .take(SHELL_OUTPUT_PREVIEW_MAX_LINES)
        .joinToString("\n")
    if (preview.isBlank()) return null
    if (preview.length <= SHELL_OUTPUT_PREVIEW_MAX_CHARS) return preview
    return preview.take(SHELL_OUTPUT_PREVIEW_MAX_CHARS - 3) + "..."
}
