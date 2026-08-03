package me.rerere.rikkahub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import me.rerere.rikkahub.data.db.entity.SandboxWorkspaceMountEntity

class SandboxMountPathTest {
    @Test
    fun normalizesWorkspaceMountTarget() {
        assertEquals("/workspace", normalizeSandboxMountParentPath(" /workspace/ "))
        assertEquals(
            "/workspace/projects/Documents",
            normalizeSandboxMountTarget("/workspace//projects/", " Documents "),
        )
        assertEquals("projects/Documents", sandboxMountRelativePath("/workspace/projects/Documents"))
        assertEquals("", sandboxMountRelativePath("/workspace"))
    }

    @Test
    fun rejectsTargetsOutsideWorkspaceAndInvalidNames() {
        for ((parent, name) in listOf(
            "/root" to "Docs",
            "/workspace/../root" to "Docs",
            "/workspace" to "../Docs",
            "/workspace" to "a/b",
        )) {
            try {
                normalizeSandboxMountTarget(parent, name)
                fail("Expected $parent/$name to be rejected")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun addsStableNumberWhenFolderNameExists() {
        assertEquals("Docs", uniqueSandboxMountName("Docs", emptyList()))
        assertEquals("Docs 2", uniqueSandboxMountName("Docs", listOf("Docs")))
        assertEquals("Docs 3", uniqueSandboxMountName("Docs", listOf("Docs", "Docs 2")))
    }

    @Test
    fun detectsParentDirectoryContainingMount() {
        val target = "/workspace/projects/Documents"

        assertEquals(true, sandboxMountTargetIsDescendantOf(target, ""))
        assertEquals(true, sandboxMountTargetIsDescendantOf(target, "projects"))
        assertEquals(false, sandboxMountTargetIsDescendantOf(target, "projects/Documents"))
        assertEquals(false, sandboxMountTargetIsDescendantOf(target, "other"))
    }

    @Test
    fun resolvesMountedFileToExternalRelativePath() {
        val mount = mount("/workspace/mount")

        val resolved = resolveSandboxMount(listOf(mount), "mount/hello.txt")

        assertEquals("mount", resolved?.targetRelative)
        assertEquals("hello.txt", resolved?.relativePath)
    }

    @Test
    fun doesNotMatchAPathWithOnlyTheSamePrefix() {
        val mount = mount("/workspace/mount")

        assertNull(resolveSandboxMount(listOf(mount), "mount-old/hello.txt"))
    }

    @Test
    fun nestedMountUsesTheLongestMatchingTarget() {
        val outer = mount("/workspace/project")
        val inner = mount("/workspace/project/docs")

        val resolved = resolveSandboxMount(listOf(outer, inner), "project/docs/note.txt")

        assertEquals(inner, resolved?.mount)
        assertEquals("note.txt", resolved?.relativePath)
    }

    @Test
    fun rejectsMalformedMountTargetsInsteadOfFallingBack() {
        try {
            resolveSandboxMount(listOf(mount("/workspace/../private")), "hello.txt")
            fail("Expected malformed mount target to be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    private fun mount(targetPath: String) = SandboxWorkspaceMountEntity(
        id = targetPath,
        workspaceId = "workspace",
        treeUri = "content://mount",
        sourcePath = "/storage/emulated/0/mount",
        targetPath = targetPath,
        createdAt = 0,
    )
}
