package me.rerere.rikkahub.data.ai.transformers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DocumentAsPromptTransformerTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `session upload path is exposed when current request can read uploads`() {
        val sessionDir = temp.newFolder("chat_uploads", "session-id")
        val file = File(sessionDir, "notes.txt").apply { writeText("hello") }

        assertEquals(
            "notes.txt",
            DocumentAsPromptTransformer.sessionUploadRelativePath(
                file = file,
                chatUploadsAccessible = true,
            ),
        )
    }

    @Test
    fun `session upload path falls back when current request cannot read uploads`() {
        val sessionDir = temp.newFolder("chat_uploads", "session-id")
        val file = File(sessionDir, "notes.txt").apply { writeText("hello") }

        assertNull(
            DocumentAsPromptTransformer.sessionUploadRelativePath(
                file = file,
                chatUploadsAccessible = false,
            ),
        )
    }

    @Test
    fun `legacy upload never exposes a sandbox path`() {
        val file = File(temp.newFolder("upload"), "notes.txt").apply { writeText("hello") }

        assertNull(
            DocumentAsPromptTransformer.sessionUploadRelativePath(
                file = file,
                chatUploadsAccessible = true,
            ),
        )
    }
}
