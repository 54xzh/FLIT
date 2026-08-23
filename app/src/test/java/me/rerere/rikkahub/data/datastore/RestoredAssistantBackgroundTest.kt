package me.rerere.rikkahub.data.datastore

import java.io.File
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.ChatTarget
import me.rerere.rikkahub.data.sync.BackupCleanupResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestoredAssistantBackgroundTest {
    private val filesDir = File("/data/user/0/me.rerere.rikkahub.plus/files")

    @Test
    fun normalizeRestoredAssistantBackground_keepsNoBackgroundAsNull() {
        assertNull(normalizeRestoredAssistantBackground(null, filesDir))
        assertNull(normalizeRestoredAssistantBackground("   ", filesDir))
    }

    @Test
    fun normalizeRestoredAssistantBackground_remapsOldPackageUploadPath() {
        val restored = normalizeRestoredAssistantBackground(
            "file:///data/user/0/me.rerere.rikkahub.exp/files/upload/background.png",
            filesDir,
        )

        assertEquals(
            "file:///data/user/0/me.rerere.rikkahub.plus/files/upload/background.png",
            restored,
        )
    }

    @Test
    fun normalizeRestoredAssistantBackground_keepsRemoteAndUnknownPaths() {
        assertEquals(
            "https://example.com/background.png",
            normalizeRestoredAssistantBackground("https://example.com/background.png", filesDir),
        )
        assertEquals(
            "file:///storage/emulated/0/Pictures/background.png",
            normalizeRestoredAssistantBackground(
                "file:///storage/emulated/0/Pictures/background.png",
                filesDir,
            ),
        )
    }

    @Test
    fun sanitize_normalizesAssistantBackgroundAndReportsIt() {
        val assistant = Assistant(background = "   ")
        val settings = Settings(
            assistantId = assistant.id,
            chatTarget = ChatTarget.Assistant(assistant.id),
            assistants = listOf(assistant),
        )

        val (sanitized, cleanup) = settings.sanitize()

        assertNull(sanitized.assistants.single().background)
        assertEquals(1, cleanup.fixedAssistantBackgrounds)
        assertEquals(1, cleanup.totalIssuesFixed)
    }

    @Test
    fun cleanupResult_addsBackgroundFixCounts() {
        val combined = BackupCleanupResult(fixedAssistantBackgrounds = 1) +
            BackupCleanupResult(
                fixedAssistantBackgrounds = 2,
                fixedAvatarPaths = 1,
                unsupportedRikkaHubSettings = 3,
            )

        assertEquals(3, combined.fixedAssistantBackgrounds)
        assertEquals(7, combined.totalIssuesFixed)
        assertEquals(3, combined.unsupportedRikkaHubSettings)
    }
}
