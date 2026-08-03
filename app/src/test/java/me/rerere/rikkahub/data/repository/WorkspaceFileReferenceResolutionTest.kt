package me.rerere.rikkahub.data.repository

import java.io.File
import me.rerere.rikkahub.data.db.entity.SandboxWorkspaceMountEntity
import me.rerere.rikkahub.workspace.SandboxWorkspaceManager
import me.rerere.rikkahub.workspace.SandboxShellContext
import me.rerere.rikkahub.workspace.SandboxShellRunner
import me.rerere.rikkahub.workspace.SandboxCommandResult
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceFileReferenceResolutionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun manager() = SandboxWorkspaceManager(
        baseDir = temporaryFolder.newFolder("sandboxes"),
        shellRunner = object : SandboxShellRunner {
            override fun execute(context: SandboxShellContext) = SandboxCommandResult(0, "", "")
        },
    )

    @Test
    fun resolvesPrivateSandboxFilesByCompletePath() {
        val manager = manager()
        manager.writeText("workspace", "output/report.txt", "report", overwrite = true)

        val entry = resolveSandboxWorkspaceEntry(
            manager = manager,
            workspaceId = "workspace",
            mounts = emptyList(),
            normalizedPath = "output/report.txt",
            sourceForMount = { error("No mount should be resolved") },
        )

        assertEquals("output/report.txt", entry?.path)
        assertEquals("report.txt", entry?.name)
    }

    @Test
    fun resolvesMountedFilesFromTheExternalSourceByCompletePath() {
        val manager = manager()
        val source = temporaryFolder.newFolder("mounted-source")
        File(source, "hello.txt").writeText("hello")
        val mount = SandboxWorkspaceMountEntity(
            id = "mount-1",
            workspaceId = "workspace",
            treeUri = "content://mount",
            sourcePath = source.absolutePath,
            targetPath = "/workspace/mount",
            createdAt = 0,
        )

        val entry = resolveSandboxWorkspaceEntry(
            manager = manager,
            workspaceId = "workspace",
            mounts = listOf(mount),
            normalizedPath = "mount/hello.txt",
            sourceForMount = { source },
        )

        assertEquals("mount/hello.txt", entry?.path)
        assertEquals("hello.txt", entry?.name)
        assertEquals("mount-1", entry?.mountId)
    }
}
