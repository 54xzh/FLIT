package me.rerere.rikkahub.workspace

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class SandboxProcessCoordinatorTest {
    @Test
    fun `workspace and global maintenance stop registered processes`() = runBlocking {
        val coordinator = SandboxProcessCoordinator()
        val owner = RecordingOwner()
        coordinator.register("test", owner)

        coordinator.stopWorkspace("workspace-a")
        coordinator.stopAll()

        assertEquals(listOf("workspace-a"), owner.stoppedWorkspaces)
        assertEquals(1, owner.stopAllCalls)
    }

    @Test
    fun `maintenance blocks new process starts until operation finishes`() = runBlocking {
        val coordinator = SandboxProcessCoordinator()

        assertTrue(coordinator.isStartAllowed("workspace-a"))
        coordinator.withWorkspaceMaintenance("workspace-a") {
            assertFalse(coordinator.isStartAllowed("workspace-a"))
            assertTrue(coordinator.isStartAllowed("workspace-b"))
        }
        assertTrue(coordinator.isStartAllowed("workspace-a"))

        coordinator.withGlobalMaintenance {
            assertFalse(coordinator.isStartAllowed("workspace-a"))
            assertFalse(coordinator.isStartAllowed("workspace-b"))
        }
    }

    @Test
    fun `workspace maintenance closes owned raw process before destructive operation`() = runBlocking {
        val coordinator = SandboxProcessCoordinator()
        val process = TrackingProcess()
        val owner = RawProcessOwner("workspace-a", SandboxRawProcess(process), coordinator)
        coordinator.register("stdio-test", owner)

        coordinator.withWorkspaceMaintenance("workspace-a") {
            assertFalse(coordinator.isStartAllowed("workspace-a"))
            assertFalse(process.isAlive)
            assertFalse(owner.restartWasAllowed)
        }

        assertTrue(process.destroyCalled)
        assertTrue(coordinator.isStartAllowed("workspace-a"))
    }

    private class RecordingOwner : SandboxProcessOwner {
        val stoppedWorkspaces = mutableListOf<String>()
        var stopAllCalls = 0

        override suspend fun stopWorkspace(workspaceId: String) {
            stoppedWorkspaces += workspaceId
        }

        override suspend fun stopAll() {
            stopAllCalls++
        }
    }

    private class RawProcessOwner(
        private val workspaceId: String,
        private val process: SandboxRawProcess,
        private val coordinator: SandboxProcessCoordinator,
    ) : SandboxProcessOwner {
        var restartWasAllowed = true

        override suspend fun stopWorkspace(workspaceId: String) {
            if (workspaceId != this.workspaceId) return
            process.close()
            restartWasAllowed = coordinator.isStartAllowed(workspaceId)
        }

        override suspend fun stopAll() {
            process.close()
        }
    }

    private class TrackingProcess : Process() {
        private val stdin = ByteArrayOutputStream()
        private var alive = true
        var destroyCalled = false
            private set

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int {
            alive = false
            return 0
        }
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive
        override fun exitValue(): Int = if (alive) throw IllegalThreadStateException() else 0
        override fun destroy() {
            destroyCalled = true
            alive = false
        }
        override fun destroyForcibly(): Process {
            alive = false
            return this
        }
        override fun isAlive(): Boolean = alive
    }
}
