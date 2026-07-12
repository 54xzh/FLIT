package me.rerere.rikkahub.data.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RestoreDirectoryTransactionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `sandbox restore path rejects traversal`() {
        assertEquals(
            "sandbox_workspaces/ws/files",
            sandboxRestoreRollbackPath("ws/files/project/main.kt"),
        )
        assertEquals(null, sandboxRestoreRollbackPath("ws-a/files/../../ws-b/files/x"))
        assertEquals(null, sandboxRestoreRollbackPath("ws/files//x"))
        assertEquals(null, sandboxRestoreRollbackPath("ws/linux/bin/sh"))
    }

    @Test
    fun `ordinary directories overlay while skills replace`() {
        val filesDir = temporaryFolder.newFolder("files")
        val stagedRoot = temporaryFolder.newFolder("staged")

        File(filesDir, "avatars").mkdirs()
        File(filesDir, "avatars/keep.txt").writeText("keep")
        File(filesDir, "avatars/avatar.png").writeText("old")
        File(filesDir, "skills/old-skill").mkdirs()
        File(filesDir, "skills/old-skill/SKILL.md").writeText("old")

        File(stagedRoot, "avatars").mkdirs()
        File(stagedRoot, "avatars/avatar.png").writeText("new")
        File(stagedRoot, "skills/new-skill").mkdirs()
        File(stagedRoot, "skills/new-skill/SKILL.md").writeText("new")

        landStagedRestoreDirectories(
            filesDir = filesDir,
            stagedFilesRoot = stagedRoot,
            relativePaths = listOf("avatars", "skills"),
        )

        assertEquals("new", File(filesDir, "avatars/avatar.png").readText())
        assertEquals("keep", File(filesDir, "avatars/keep.txt").readText())
        assertFalse(File(filesDir, "skills/old-skill").exists())
        assertTrue(File(filesDir, "skills/new-skill/SKILL.md").isFile)
    }

    @Test
    fun `rollback restores overwritten directories and removes newly created ones`() {
        val filesDir = temporaryFolder.newFolder("rollback-files")
        val stagedRoot = temporaryFolder.newFolder("rollback-staged")
        val rollbackRoot = File(temporaryFolder.root, "rollback-snapshots")

        File(filesDir, "avatars").mkdirs()
        File(filesDir, "avatars/avatar.png").writeText("old")
        File(filesDir, "avatars/keep.txt").writeText("keep")
        File(stagedRoot, "avatars").mkdirs()
        File(stagedRoot, "avatars/avatar.png").writeText("new")
        File(stagedRoot, "images").mkdirs()
        File(stagedRoot, "images/new.png").writeText("new-image")

        val snapshots = prepareRestoreDirectorySnapshots(
            filesDir = filesDir,
            rollbackRoot = rollbackRoot,
            relativePaths = listOf("avatars", "images"),
        )
        landStagedRestoreDirectories(
            filesDir = filesDir,
            stagedFilesRoot = stagedRoot,
            relativePaths = listOf("avatars", "images"),
        )

        restoreDirectorySnapshots(filesDir, snapshots)

        assertEquals("old", File(filesDir, "avatars/avatar.png").readText())
        assertEquals("keep", File(filesDir, "avatars/keep.txt").readText())
        assertFalse(File(filesDir, "images").exists())
    }

    @Test
    fun `nested managed path can be landed and rolled back`() {
        val filesDir = temporaryFolder.newFolder("nested-files")
        val stagedRoot = temporaryFolder.newFolder("nested-staged")
        val rollbackRoot = File(temporaryFolder.root, "nested-rollback")

        File(filesDir, "python/wheels").mkdirs()
        File(filesDir, "python/wheels/old.whl").writeText("old")
        File(stagedRoot, "python/wheels").mkdirs()
        File(stagedRoot, "python/wheels/new.whl").writeText("new")

        val snapshots = prepareRestoreDirectorySnapshots(
            filesDir = filesDir,
            rollbackRoot = rollbackRoot,
            relativePaths = listOf("python/wheels"),
        )
        landStagedRestoreDirectories(filesDir, stagedRoot, listOf("python/wheels"))
        restoreDirectorySnapshots(filesDir, snapshots)

        assertTrue(File(filesDir, "python/wheels/old.whl").isFile)
        assertFalse(File(filesDir, "python/wheels/new.whl").exists())
    }

    @Test
    fun `sandbox snapshot only copies affected workspace files`() {
        val filesDir = temporaryFolder.newFolder("sandbox-files")
        val rollbackRoot = File(temporaryFolder.root, "sandbox-rollback")
        File(filesDir, "sandbox_workspaces/ws/files").mkdirs()
        File(filesDir, "sandbox_workspaces/ws/files/user.txt").writeText("user")
        File(filesDir, "sandbox_workspaces/ws/linux/bin").mkdirs()
        File(filesDir, "sandbox_workspaces/ws/linux/bin/sh").writeText("large-rootfs")

        val snapshots = prepareRestoreDirectorySnapshots(
            filesDir = filesDir,
            rollbackRoot = rollbackRoot,
            relativePaths = listOf("sandbox_workspaces/ws/files"),
        )

        assertEquals("user", File(snapshots.single().backupDir, "user.txt").readText())
        assertFalse(snapshots.single().backupDir.walkTopDown().any { it.name == "sh" })
        assertTrue(File(filesDir, "sandbox_workspaces/ws/linux/bin/sh").isFile)
    }
}
