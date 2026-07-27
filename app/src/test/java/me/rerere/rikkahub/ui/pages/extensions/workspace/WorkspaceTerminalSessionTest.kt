package me.rerere.rikkahub.ui.pages.extensions.workspace

import java.io.File
import me.rerere.rikkahub.workspace.SandboxBindMount
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceTerminalSessionTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun interactiveTerminalIncludesWorkspaceFolderMounts() {
        val filesDir = temporaryFolder.newFolder("files")
        val linuxDir = temporaryFolder.newFolder("linux")
        val phoneDir = temporaryFolder.newFolder("phone")
        val binding = "${phoneDir.absolutePath}:/workspace/Phone"

        val args = buildWorkspaceTerminalArgs(
            filesDir = filesDir,
            linuxDir = linuxDir,
            bindMounts = listOf(SandboxBindMount(phoneDir, "/workspace/Phone")),
        )

        assertTrue(args.windowed(2).any { it == listOf("-b", binding) })
        assertTrue(args.windowed(2).any { it == listOf("-b", "${filesDir.absolutePath}:/workspace") })
        assertTrue(args.containsAll(listOf("-r", File(linuxDir.absolutePath).absolutePath, "/bin/bash", "-l")))
    }
}
