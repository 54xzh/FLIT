package me.rerere.rikkahub.workspace

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SandboxMountPathResolverTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun resolvesPrimaryAndRemovableStorageDocumentIds() {
        val primary = temporaryFolder.newFolder("primary")
        val removable = temporaryFolder.newFolder("removable")

        assertEquals(
            File(primary, "Documents/Notes").canonicalFile,
            resolveExternalStorageDocumentPath(
                documentId = "primary:Documents/Notes",
                volumeRoots = mapOf("primary" to primary),
            ),
        )
        assertEquals(
            File(removable, "Projects").canonicalFile,
            resolveExternalStorageDocumentPath(
                documentId = "ABCD-1234:Projects",
                volumeRoots = mapOf("abcd-1234" to removable),
            ),
        )
    }

    @Test
    fun rejectsTraversalAndUnknownVolumes() {
        val primary = temporaryFolder.newFolder("safe")
        for (documentId in listOf("primary:../secret", "missing:Documents", "invalid")) {
            try {
                resolveExternalStorageDocumentPath(documentId, mapOf("primary" to primary))
                fail("Expected $documentId to be rejected")
            } catch (_: IllegalArgumentException) {
            } catch (_: IllegalStateException) {
            }
        }
    }
}
