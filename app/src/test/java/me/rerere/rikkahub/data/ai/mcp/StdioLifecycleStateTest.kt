package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import me.rerere.rikkahub.workspace.SandboxRawProcess
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class StdioLifecycleStateTest {
    @Test
    fun `disabled stdio config is excluded from running sessions`() {
        val enabled = McpServerConfig.StdioServer(
            commonOptions = McpCommonOptions(enable = true),
            workspaceId = "a",
            command = "server",
        )
        val disabled = enabled.copy(
            id = kotlin.uuid.Uuid.random(),
            commonOptions = enabled.commonOptions.copy(enable = false),
        )

        assertEquals(setOf(enabled.id), enabledStdioConfigs(listOf(enabled, disabled)).keys)
    }

    @Test
    fun `stderr keeps only bounded tail`() {
        val tail = StderrTail(maxChars = 8)
        tail.append("first-")
        tail.append("diagnostic")

        assertEquals("agnostic", tail.value())
    }

    @Test
    fun `stderr reader drains final diagnostic before shutdown completes`() = runBlocking {
        val tail = StderrTail(maxChars = 32)
        val reader = launch {
            delay(20)
            tail.append("last diagnostic")
        }

        drainStderrReader(reader, timeoutMillis = 1_000)

        assertEquals("last diagnostic", tail.value())
        assertTrue(reader.isCompleted)
    }

    @Test
    fun `hung stderr reader is cancelled after bounded drain`() = runBlocking {
        val reader = launch { delay(10_000) }

        drainStderrReader(reader, timeoutMillis = 1)

        assertTrue(reader.isCancelled)
    }

    @Test
    fun `idle close never becomes eligible during active call`() {
        val activity = StdioCallActivity(idleTimeoutMillis = 1_000)
        activity.begin(now = 100)

        assertFalse(activity.canClose(now = 10_000))

        activity.finish(now = 10_000)
        assertFalse(activity.canClose(now = 10_999))
        assertTrue(activity.canClose(now = 11_000))
    }

    @Test
    fun `initialization retries once and cleans failed attempt`() = runBlocking {
        var attempts = 0
        var currentProcess: TrackingProcess? = null
        val failedProcesses = mutableListOf<TrackingProcess>()

        val value = withSingleStdioInitializationRetry(
            cleanup = {
                currentProcess?.let { process ->
                    SandboxRawProcess(process).close()
                    failedProcesses += process
                }
                currentProcess = null
            },
        ) {
            attempts++
            currentProcess = TrackingProcess()
            if (attempts == 1) error("first initialization failed")
            "connected"
        }

        assertEquals("connected", value)
        assertEquals(2, attempts)
        assertEquals(1, failedProcesses.size)
        assertFalse(failedProcesses.single().isAlive)
        assertTrue(failedProcesses.single().destroyCalled)
        SandboxRawProcess(currentProcess!!).close()
    }

    @Test
    fun `initialization timeout is retried only once`() = runBlocking {
        var attempts = 0
        var cleanups = 0

        runCatching {
            withSingleStdioInitializationRetry(
                cleanup = { cleanups++ },
            ) {
                attempts++
                withTimeout(1) { delay(100) }
            }
        }

        assertEquals(2, attempts)
        assertEquals(2, cleanups)
    }

    @Test
    fun `tool call failure is never replayed`() = runBlocking {
        var calls = 0

        runCatching {
            callStdioToolOnce {
                calls++
                error("side effect may already have happened")
            }
        }

        assertEquals(1, calls)
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
