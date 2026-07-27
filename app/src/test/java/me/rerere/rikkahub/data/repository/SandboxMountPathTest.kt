package me.rerere.rikkahub.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SandboxMountPathTest {
    @Test
    fun normalizesWorkspaceMountTarget() {
        assertEquals("/workspace", normalizeSandboxMountParentPath(" /workspace/ "))
        assertEquals(
            "/workspace/projects/Documents",
            normalizeSandboxMountTarget("/workspace//projects/", " Documents "),
        )
        assertEquals("projects/Documents", sandboxMountRelativePath("/workspace/projects/Documents"))
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
}
