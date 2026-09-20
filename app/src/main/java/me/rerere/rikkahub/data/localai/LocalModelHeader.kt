package me.rerere.rikkahub.data.localai

import java.io.DataInputStream
import java.io.InputStream

/** Extension alone cannot distinguish a model from a renamed download/error page. */
internal fun validateLocalModelHeader(input: InputStream, format: LocalModelFormat) {
    val signature = when (format) {
        LocalModelFormat.GGUF -> "GGUF"
        LocalModelFormat.LITERT_LM -> "LITERTLM"
    }
    val header = ByteArray(signature.length)
    DataInputStream(input).readFully(header)
    require(header.contentEquals(signature.toByteArray(Charsets.US_ASCII))) {
        "Invalid ${format.extension} model file"
    }
}
