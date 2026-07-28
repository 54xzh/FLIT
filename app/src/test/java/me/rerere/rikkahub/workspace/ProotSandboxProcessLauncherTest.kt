package me.rerere.rikkahub.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class ProotSandboxProcessLauncherTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `raw process command bypasses shell and preserves arguments cwd and env`() {
        val nativeDir = temp.newFolder("native")
        val filesDir = temp.newFolder("files")
        val linuxDir = temp.newFolder("linux")
        val tempDir = temp.newFolder("tmp")
        val mountSource = temp.newFolder("mount source")
        val launcher = ProotSandboxProcessLauncher(
            nativeLibraryDir = nativeDir,
            extraBindMounts = emptyList(),
        )
        val proot = File(nativeDir, "libproot_exec.so")
        val context = SandboxProcessContext(
            workspaceId = "workspace-a",
            command = "/usr/bin/node",
            args = listOf("server.js", "value with spaces", "${'$'}literal", ""),
            workingDirectory = "/workspace/project with spaces",
            environment = mapOf("TOKEN" to "secret value", "EMPTY" to ""),
            filesDir = filesDir,
            linuxDir = linuxDir,
            tempDir = tempDir,
            workspaceBindMounts = listOf(SandboxBindMount(mountSource, "/workspace/mounted")),
        )

        val command = launcher.buildCommand(context, proot)

        assertFalse(command.contains("/bin/bash"))
        assertFalse(command.contains("-c"))
        assertFalse(command.any { it.contains("eval") })
        assertEquals("/workspace/project with spaces", command[command.indexOf("-w") + 1])
        assertTrue(command.contains("TOKEN=secret value"))
        assertTrue(command.contains("EMPTY="))
        assertEquals(
            listOf("/usr/bin/node", "server.js", "value with spaces", "${'$'}literal", ""),
            command.takeLast(5),
        )
    }

    @Test
    fun `raw process close escalates through terminate and force kill`() {
        val process = TrackingProcess()
        val rawProcess = SandboxRawProcess(process)

        assertNull(rawProcess.exitCodeOrNull())
        rawProcess.close()

        assertTrue(process.stdin.closed)
        assertTrue(process.destroyCalled)
        assertTrue(process.forceDestroyCalled)
        assertFalse(process.isAlive)
        assertEquals(137, rawProcess.exitCodeOrNull())
    }

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }

    private class TrackingProcess : Process() {
        val stdin = TrackingOutputStream()
        var alive = true
        var destroyCalled = false
        var forceDestroyCalled = false

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            alive = false
            return 0
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 137
        override fun destroy() {
            destroyCalled = true
        }
        override fun destroyForcibly(): Process {
            forceDestroyCalled = true
            alive = false
            return this
        }
        override fun isAlive(): Boolean = alive
    }
}
