package me.rerere.rikkahub.data.ai

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.EncodingType

/**
 * Offline cl100k_base estimate for the context composition bar.
 * This is not a provider tokenizer, and it is not used for context-window trimming.
 */
internal object ContextTokenEstimator {
    private val encoding by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE)
    }

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        return encoding.countTokens(text)
    }
}
