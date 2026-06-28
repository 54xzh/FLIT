package me.rerere.rikkahub.ui.components.ai

internal fun shouldInterruptGenerationAndSend(
    isGenerating: Boolean,
    isInputRaised: Boolean,
    hasDraftText: Boolean,
): Boolean {
    return isGenerating && isInputRaised && hasDraftText
}
