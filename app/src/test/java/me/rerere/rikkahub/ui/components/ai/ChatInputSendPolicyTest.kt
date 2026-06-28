package me.rerere.rikkahub.ui.components.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatInputSendPolicyTest {
    @Test
    fun `interrupts and sends when generating with raised input and draft text`() {
        assertTrue(
            shouldInterruptGenerationAndSend(
                isGenerating = true,
                isInputRaised = true,
                hasDraftText = true,
            )
        )
    }

    @Test
    fun `does not interrupt and send when input is not raised`() {
        assertFalse(
            shouldInterruptGenerationAndSend(
                isGenerating = true,
                isInputRaised = false,
                hasDraftText = true,
            )
        )
    }

    @Test
    fun `does not interrupt and send when draft text is empty`() {
        assertFalse(
            shouldInterruptGenerationAndSend(
                isGenerating = true,
                isInputRaised = true,
                hasDraftText = false,
            )
        )
    }

    @Test
    fun `does not interrupt and send when generation is already idle`() {
        assertFalse(
            shouldInterruptGenerationAndSend(
                isGenerating = false,
                isInputRaised = true,
                hasDraftText = true,
            )
        )
    }
}
