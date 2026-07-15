package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.net.toUri
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import me.rerere.rikkahub.workspace.SandboxRootfsPatcher
import me.rerere.rikkahub.workspace.SandboxWorkspaceManager
import me.rerere.rikkahub.workspace.sandboxBindMounts

internal fun createWorkspaceTerminalSession(
    context: Context,
    workspaceId: String,
    manager: SandboxWorkspaceManager,
    client: TerminalSessionClient,
): TerminalSession {
    val appContext = context.applicationContext
    val filesDir = manager.filesDir(workspaceId)
    val linuxDir = manager.linuxDir(workspaceId)
    val tempDir = manager.tempDir(workspaceId)
    val nativeLibraryDir = File(appContext.applicationInfo.nativeLibraryDir)
    val proot = File(nativeLibraryDir, PROOT_EXEC)
    val loader = File(nativeLibraryDir, PROOT_LOADER)

    val args = mutableListOf(
        "--root-id",
        "--link2symlink",
        "--kill-on-exit",
        "-r",
        linuxDir.absolutePath,
        "-w",
        WORKSPACE_DIR,
        "-b",
        "${filesDir.absolutePath}:$WORKSPACE_DIR",
    )
    sandboxBindMounts(appContext)
        .filter { it.source.exists() }
        .forEach { mount ->
            args += "-b"
            args += "${mount.source.absolutePath}:${mount.target.trimEnd('/')}"
        }
    listOf("/dev", "/proc", "/sys").forEach { path ->
        if (File(path).exists()) {
            args += "-b"
            args += path
        }
    }
    args += listOf(
        "/usr/bin/env",
        "-i",
        "HOME=/root",
        "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        "TERM=xterm-256color",
        "LANG=C.UTF-8",
        "LC_ALL=C.UTF-8",
        "USER=root",
        "SHELL=/bin/bash",
        "/bin/bash",
        "-l",
    )

    val env = arrayOf(
        "PROOT_LOADER=${loader.absolutePath}",
        "PROOT_TMP_DIR=${tempDir.absolutePath}",
        "TMPDIR=${tempDir.absolutePath}",
    )

    return TerminalSession(
        proot.absolutePath,
        filesDir.absolutePath,
        args.toTypedArray(),
        env,
        2_000,
        client,
    ).apply {
        mSessionName = workspaceId
    }
}

internal fun prepareWorkspaceTerminalSession(
    workspaceId: String,
    manager: SandboxWorkspaceManager,
) {
    manager.ensureWorkspace(workspaceId)
    SandboxRootfsPatcher().patch(manager.linuxDir(workspaceId))
}

internal fun workspaceRootfsReady(
    workspaceId: String,
    manager: SandboxWorkspaceManager,
): Boolean = manager.hasRootfs(workspaceId)

internal fun workspaceTerminalRuntimeReady(context: Context): Boolean {
    val nativeLibraryDir = File(context.applicationContext.applicationInfo.nativeLibraryDir)
    return File(nativeLibraryDir, PROOT_EXEC).isFile && File(nativeLibraryDir, PROOT_LOADER).isFile
}

internal class WorkspaceTerminalSessionClient(
    private val context: Context,
    private val onFinished: () -> Unit,
) : TerminalSessionClient {
    var terminalView: TerminalView? = null

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
        onFinished()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?: return
        val bytes = text.toByteArray()
        session.write(bytes, 0, bytes.size)
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        terminalView?.invalidate()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        terminalView?.invalidate()
    }

    override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String, message: String) = Log.e(tag, message).let { Unit }
    override fun logWarn(tag: String, message: String) = Log.w(tag, message).let { Unit }
    override fun logInfo(tag: String, message: String) = Log.i(tag, message).let { Unit }
    override fun logDebug(tag: String, message: String) = Log.d(tag, message).let { Unit }
    override fun logVerbose(tag: String, message: String) = Log.v(tag, message).let { Unit }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Log.e(tag, message, e).let { Unit }
    override fun logStackTrace(tag: String, e: Exception) = Log.e(tag, "Terminal error", e).let { Unit }
}

internal class WorkspaceTerminalViewClient(
    private val context: Context,
) : TerminalViewClient {
    var terminalView: TerminalView? = null
    var controlDown: Boolean = false
    var altDown: Boolean = false

    override fun onScale(scale: Float): Float = scale.coerceIn(0.8f, 1.25f)

    override fun onSingleTapUp(e: MotionEvent) {
        if (openUrlAtTap(e)) return
        focusAndShowKeyboard()
    }

    private fun openUrlAtTap(e: MotionEvent): Boolean {
        val view = terminalView ?: return false
        if (view.isSelectingText) return false
        val emulator = view.mEmulator ?: return false
        val screen = emulator.getScreen()
        val columns = emulator.mColumns
        val columnAndRow = view.getColumnAndRow(e, true)
        val column = columnAndRow[0]
        val row = columnAndRow[1]
        val minAccessibleRow = -screen.activeTranscriptRows
        val maxAccessibleRow = emulator.mRows - 1
        if (column !in 0 until columns || row !in minAccessibleRow..maxAccessibleRow) return false

        val minRow = (row - URL_MAX_WRAP_ROWS).coerceAtLeast(minAccessibleRow)
        val maxRow = (row + URL_MAX_WRAP_ROWS).coerceAtMost(maxAccessibleRow)
        var startRow = row
        while (startRow > minRow && screen.getLineWrap(startRow - 1)) startRow--
        var endRow = row
        while (endRow < maxRow && screen.getLineWrap(endRow)) endRow++

        val line = StringBuilder()
        var tapIndex = -1
        for (currentRow in startRow..endRow) {
            if (currentRow == row) {
                tapIndex = line.length +
                    (screen.getSelectedText(0, currentRow, column, currentRow).length - 1).coerceAtLeast(0)
            }
            line.append(screen.getSelectedText(0, currentRow, columns - 1, currentRow))
        }
        if (tapIndex < 0) return false

        val match = URL_REGEX.findAll(line).firstOrNull { tapIndex in it.range } ?: return false
        val url = match.value.trimEnd(*URL_TRAILING_TRIM)
        return runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        }.getOrElse {
            Log.w(TAG, "Failed to open url: $url", it)
            false
        }
    }

    fun focusAndShowKeyboard() {
        val view = terminalView ?: return
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        view.post {
            view.requestFocus()
            inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = controlDown
    override fun readAltKey(): Boolean = altDown
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() = Unit
    override fun logError(tag: String, message: String) = Log.e(tag, message).let { Unit }
    override fun logWarn(tag: String, message: String) = Log.w(tag, message).let { Unit }
    override fun logInfo(tag: String, message: String) = Log.i(tag, message).let { Unit }
    override fun logDebug(tag: String, message: String) = Log.d(tag, message).let { Unit }
    override fun logVerbose(tag: String, message: String) = Log.v(tag, message).let { Unit }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) = Log.e(tag, message, e).let { Unit }
    override fun logStackTrace(tag: String, e: Exception) = Log.e(tag, "Terminal view error", e).let { Unit }

    private companion object {
        private const val TAG = "WorkspaceTerminal"
    }
}

private const val PROOT_EXEC = "libproot_exec.so"
private const val PROOT_LOADER = "libproot_loader.so"
private const val WORKSPACE_DIR = "/workspace"
private const val URL_MAX_WRAP_ROWS = 50
private val URL_REGEX = Regex(
    """(https?|ftp)://[\w\-._~:/?#\[\]@!$&'()*+,;=%]+""",
    RegexOption.IGNORE_CASE,
)
private val URL_TRAILING_TRIM = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"')
