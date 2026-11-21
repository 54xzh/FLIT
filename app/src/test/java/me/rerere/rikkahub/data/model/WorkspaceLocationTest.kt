package me.rerere.rikkahub.data.model

import kotlinx.coroutines.runBlocking
import me.rerere.rikkahub.data.ai.tools.sandboxCwdSystemPromptNote
import me.rerere.rikkahub.data.db.entity.WorkspaceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceLocationTest {

    @Test
    fun `normalizeWorkspaceCwd preserves null vs root vs relative path`() {
        assertNull(normalizeWorkspaceCwd(null))
        assertEquals("", normalizeWorkspaceCwd(""))
        assertEquals("", normalizeWorkspaceCwd("   "))
        assertEquals("", normalizeWorkspaceCwd("/"))
        assertEquals("", normalizeWorkspaceCwd("."))
        assertEquals("", normalizeWorkspaceCwd("/workspace"))
        assertEquals("", normalizeWorkspaceCwd("/workspace/"))
        assertEquals("blog", normalizeWorkspaceCwd("blog"))
        assertEquals("blog/drafts", normalizeWorkspaceCwd("/workspace/blog/drafts/"))
        assertEquals("notes/2026", normalizeWorkspaceCwd("\\notes\\2026\\"))
        assertEquals("a/b", normalizeWorkspaceCwd("///a//b///"))
    }

    @Test
    fun `normalizeWorkspaceCwd rejects traversal and NUL`() {
        assertNull(normalizeWorkspaceCwd(".."))
        assertNull(normalizeWorkspaceCwd("blog/../secret"))
        assertNull(normalizeWorkspaceCwd("/workspace/./blog"))
        assertNull(normalizeWorkspaceCwd("blog\u0000name"))
    }

    @Test
    fun `three-layer inheritance follows conversation over project over assistant`() {
        val existing = setOf("ws-a", "ws-b", "ws-c")

        // Inherit assistant only
        val base = resolveEffectiveWorkspaceLocation(
            assistantWorkspaceId = "ws-a",
            workspaceExists = { it in existing },
        )
        assertEquals("ws-a", base.workspaceId)
        assertEquals("", base.cwd)
        assertEquals(WorkspaceLocationSource.ASSISTANT, base.workspaceSource)
        assertEquals(WorkspaceLocationSource.ASSISTANT, base.cwdSource)

        // Project sets cwd on assistant workspace
        val projectCwd = resolveEffectiveWorkspaceLocation(
            assistantWorkspaceId = "ws-a",
            projectWorkspaceCwd = "blog",
            workspaceExists = { it in existing },
        )
        assertEquals("ws-a", projectCwd.workspaceId)
        assertEquals("blog", projectCwd.cwd)
        assertEquals(WorkspaceLocationSource.ASSISTANT, projectCwd.workspaceSource)
        assertEquals(WorkspaceLocationSource.PROJECT, projectCwd.cwdSource)

        // Conversation overrides cwd explicitly back to root ("")
        val convRoot = resolveEffectiveWorkspaceLocation(
            assistantWorkspaceId = "ws-a",
            projectWorkspaceCwd = "blog",
            conversationWorkspaceCwd = "",
            allowConversationOverride = true,
            workspaceExists = { it in existing },
        )
        assertEquals("ws-a", convRoot.workspaceId)
        assertEquals("", convRoot.cwd)
        assertEquals(WorkspaceLocationSource.ASSISTANT, convRoot.workspaceSource)
        assertEquals(WorkspaceLocationSource.CONVERSATION, convRoot.cwdSource)

        // Conversation overrides subfolder on project workspace
        val convSub = resolveEffectiveWorkspaceLocation(
            assistantWorkspaceId = "ws-a",
            projectWorkspaceId = "ws-b",
            projectWorkspaceCwd = "blog",
            conversationWorkspaceCwd = "blog/drafts",
            allowConversationOverride = true,
            workspaceExists = { it in existing },
        )
        assertEquals("ws-b", convSub.workspaceId)
        assertEquals("blog/drafts", convSub.cwd)
        assertEquals(WorkspaceLocationSource.PROJECT, convSub.workspaceSource)
        assertEquals(WorkspaceLocationSource.CONVERSATION, convSub.cwdSource)
    }

    @Test
    fun `switching workspace truncates parent cwd to avoid cross-workspace mismatch`() {
        val existing = setOf("ws-a", "ws-b")

        val switched = resolveEffectiveWorkspaceLocation(
            assistantWorkspaceId = "ws-a",
            projectWorkspaceCwd = "blog",
            conversationWorkspaceId = "ws-b",
            conversationWorkspaceCwd = null,
            allowConversationOverride = true,
            workspaceExists = { it in existing },
        )
        assertEquals("ws-b", switched.workspaceId)
        assertEquals("", switched.cwd)
        assertEquals(WorkspaceLocationSource.CONVERSATION, switched.workspaceSource)
        assertEquals(WorkspaceLocationSource.CONVERSATION, switched.cwdSource)
    }

    @Test
    fun `disabled conversation override ignores conversation workspace and cwd but keeps project`() {
        val existing = setOf("ws-a", "ws-b", "ws-c")

        val resolved = resolveEffectiveWorkspaceLocation(
            assistantWorkspaceId = "ws-a",
            projectWorkspaceId = "ws-b",
            projectWorkspaceCwd = "proj",
            conversationWorkspaceId = "ws-c",
            conversationWorkspaceCwd = "conv",
            allowConversationOverride = false,
            workspaceExists = { it in existing },
        )
        assertEquals("ws-b", resolved.workspaceId)
        assertEquals("proj", resolved.cwd)
        assertEquals(WorkspaceLocationSource.PROJECT, resolved.workspaceSource)
        assertEquals(WorkspaceLocationSource.PROJECT, resolved.cwdSource)
    }

    @Test
    fun `deleted workspace override safely falls back and discards stale cwd`() {
        val existing = setOf("ws-a")

        val deletedConvWs = resolveEffectiveWorkspaceLocation(
            assistantWorkspaceId = "ws-a",
            projectWorkspaceCwd = "proj",
            conversationWorkspaceId = "ws-deleted",
            conversationWorkspaceCwd = "stale-dir",
            allowConversationOverride = true,
            workspaceExists = { it in existing },
        )
        assertEquals("ws-a", deletedConvWs.workspaceId)
        assertEquals("proj", deletedConvWs.cwd)
        assertEquals(WorkspaceLocationSource.PROJECT, deletedConvWs.cwdSource)

        val deletedProjWs = resolveEffectiveWorkspaceLocation(
            assistantWorkspaceId = "ws-a",
            projectWorkspaceId = "ws-deleted",
            projectWorkspaceCwd = "stale-proj-dir",
            workspaceExists = { it in existing },
        )
        assertEquals("ws-a", deletedProjWs.workspaceId)
        assertEquals("", deletedProjWs.cwd)
        assertEquals(WorkspaceLocationSource.ASSISTANT, deletedProjWs.workspaceSource)
    }

    @Test
    fun `computeWorkspaceLocationOverride pins target workspace whenever cwd or workspace differs`() {
        // Matches inherited exactly -> (null, null)
        assertEquals(
            null to null,
            computeWorkspaceLocationOverride("ws-a", "blog", "ws-a", "blog"),
        )
        // Same workspace, different cwd -> pins workspace ("ws-a", "blog/drafts") so moving projects never misapplies cwd
        assertEquals(
            "ws-a" to "blog/drafts",
            computeWorkspaceLocationOverride("ws-a", "blog", "ws-a", "blog/drafts"),
        )
        // Same workspace, explicit root overriding project subdir -> ("ws-a", "")
        assertEquals(
            "ws-a" to "",
            computeWorkspaceLocationOverride("ws-a", "blog", "ws-a", ""),
        )
        // Switched workspace at root -> ("ws-b", "")
        assertEquals(
            "ws-b" to "",
            computeWorkspaceLocationOverride("ws-a", "blog", "ws-b", ""),
        )
        // Switched workspace at subdir -> ("ws-b", "notes")
        assertEquals(
            "ws-b" to "notes",
            computeWorkspaceLocationOverride("ws-a", "blog", "ws-b", "notes"),
        )
    }

    @Test
    fun `moving conversation to project with another workspace keeps original workspace instead of applying cwd to new workspace`() {
        val existing = setOf("ws-a", "ws-b")
        // Conversation picked "drafts" while in Project A (ws-a)
        val (savedWs, savedCwd) = computeWorkspaceLocationOverride("ws-a", "", "ws-a", "drafts")
        assertEquals("ws-a", savedWs)
        assertEquals("drafts", savedCwd)

        // While still in Project A (ws-a): workspaceSource stays inherited, cwdSource is CONVERSATION
        val inProjectA = resolveEffectiveWorkspaceLocation(
            assistantWorkspaceId = "ws-a",
            projectWorkspaceId = "ws-a",
            conversationWorkspaceId = savedWs,
            conversationWorkspaceCwd = savedCwd,
            allowConversationOverride = true,
            workspaceExists = { it in existing },
        )
        assertEquals("ws-a", inProjectA.workspaceId)
        assertEquals("drafts", inProjectA.cwd)
        assertEquals(WorkspaceLocationSource.ASSISTANT, inProjectA.workspaceSource)
        assertEquals(WorkspaceLocationSource.CONVERSATION, inProjectA.cwdSource)

        // Moved to Project B (ws-b): never reinterprets "drafts" under ws-b; stays safely on ws-a/drafts and marks workspaceSource=CONVERSATION
        val inProjectB = resolveEffectiveWorkspaceLocation(
            assistantWorkspaceId = "ws-a",
            projectWorkspaceId = "ws-b",
            conversationWorkspaceId = savedWs,
            conversationWorkspaceCwd = savedCwd,
            allowConversationOverride = true,
            workspaceExists = { it in existing },
        )
        assertEquals("ws-a", inProjectB.workspaceId)
        assertEquals("drafts", inProjectB.cwd)
        assertEquals(WorkspaceLocationSource.CONVERSATION, inProjectB.workspaceSource)
        assertEquals(WorkspaceLocationSource.CONVERSATION, inProjectB.cwdSource)
    }

    @Test
    fun `disabled project feature ignores project workspace and cwd to match UI`() {
        val existing = setOf("ws-assistant", "ws-project")
        val resolved = resolveEffectiveWorkspaceLocation(
            assistantWorkspaceId = "ws-assistant",
            projectWorkspaceId = "ws-project",
            projectWorkspaceCwd = "proj-dir",
            allowConversationOverride = true,
            projectFeatureEnabled = false,
            workspaceExists = { it in existing },
        )
        assertEquals("ws-assistant", resolved.workspaceId)
        assertEquals("", resolved.cwd)
        assertEquals(WorkspaceLocationSource.ASSISTANT, resolved.workspaceSource)
        assertEquals(WorkspaceLocationSource.ASSISTANT, resolved.cwdSource)
    }

    @Test
    fun `resolveExistingWorkspaceDirectoryPath walks up to nearest existing parent or root`() = runBlocking {
        val existingDirs = setOf("blog", "blog/2026")

        val exact = resolveExistingWorkspaceDirectoryPath("blog/2026") { it in existingDirs }
        assertEquals("blog/2026", exact.resolvedCwd)
        assertFalse(exact.fellBack)

        val fallbackParent = resolveExistingWorkspaceDirectoryPath("blog/2026/deleted/sub") { it in existingDirs }
        assertEquals("blog/2026", fallbackParent.resolvedCwd)
        assertTrue(fallbackParent.fellBack)

        val fallbackRoot = resolveExistingWorkspaceDirectoryPath("removed/only") { it in existingDirs }
        assertEquals("", fallbackRoot.resolvedCwd)
        assertTrue(fallbackRoot.fellBack)

        val root = resolveExistingWorkspaceDirectoryPath("") { it in existingDirs }
        assertEquals("", root.resolvedCwd)
        assertFalse(root.fellBack)
    }

    @Test
    fun `display formatting and sandbox cwd prompt note`() {
        assertEquals("/workspace", formatWorkspaceCwdForDisplay("", WorkspaceType.SANDBOX))
        assertEquals("/workspace/blog", formatWorkspaceCwdForDisplay("blog", WorkspaceType.SANDBOX))
        assertEquals("/", formatWorkspaceCwdForDisplay("", WorkspaceType.LIGHTWEIGHT))
        assertEquals("/blog", formatWorkspaceCwdForDisplay("blog", WorkspaceType.LIGHTWEIGHT))

        assertEquals("", sandboxCwdSystemPromptNote(""))
        val note = sandboxCwdSystemPromptNote("blog/drafts")
        assertTrue(note.contains("/workspace/blog/drafts"))
        assertTrue(note.contains("sandbox_shell"))
    }
}
