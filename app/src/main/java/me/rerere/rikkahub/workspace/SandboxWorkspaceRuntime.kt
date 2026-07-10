package me.rerere.rikkahub.workspace

import android.util.Log
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import org.tukaani.xz.XZInputStream

data class SandboxFileEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val updatedAt: Long,
)

data class SandboxCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
    val truncated: Boolean = false,
)

data class SandboxShellContext(
    val workspaceId: String,
    val command: String,
    val cwd: String,
    val filesDir: File,
    val linuxDir: File,
    val tempDir: File,
    val timeoutMillis: Long,
    val stdin: ByteArray? = null,
)

interface SandboxShellRunner {
    fun execute(context: SandboxShellContext): SandboxCommandResult
}

data class SandboxBindMount(val source: File, val target: String) {
    init {
        require(target.startsWith('/')) { "Bind mount target must be absolute" }
    }
}

/**
 * 应用私有沙盒目录：每个 workspace 都有 files、linux 和 tmp 三块存储。
 * 所有直接文件操作只允许在 files 目录内；Rootfs 只由安装器与 PRoot 使用。
 */
class SandboxWorkspaceManager(
    private val baseDir: File,
    private val shellRunner: SandboxShellRunner,
) {
    init { baseDir.mkdirs() }

    fun ensureWorkspace(id: String) {
        filesDir(id).mkdirs()
        tempDir(id).mkdirs()
    }

    fun workspaceDir(id: String): File = File(baseDir, requireWorkspaceId(id))
    fun filesDir(id: String): File = File(workspaceDir(id), "files")
    fun linuxDir(id: String): File = File(workspaceDir(id), "linux")
    fun tempDir(id: String): File = File(workspaceDir(id), "tmp")
    fun hasRootfs(id: String): Boolean = File(linuxDir(id), "bin/sh").isFile

    fun deleteWorkspace(id: String): Boolean = workspaceDir(id).deleteRecursively()

    /**
     * 删除工作区的 rootfs 残留（`linux/` 与 `tmp/`），保留 `files/` 用户文件。
     *
     * 备份只含 `files/`，不含 rootfs；恢复到本机时，旧 `linux/` 会留下导致 [hasRootfs]
     * 误判为已就绪。调用方在恢复后逐个工作区清理一次，让状态降级为未安装。
     *
     * `deleteRecursively()` 失败时不抛异常而返回 false（部分子项删不掉），因此这里
     * 收集每项目录的删除结果：只要任一目录存在但未删干净就返回 false，便于调用方
     * 记录并决定是否重试，而不是把失败当成成功。
     */
    fun cleanRootfsResidue(id: String): Boolean {
        requireWorkspaceId(id)
        var allCleaned = true
        for (name in listOf("linux", "tmp")) {
            val target = File(workspaceDir(id), name)
            if (!target.exists()) continue
            // 存在但删失败（返回 false）才算未清干净；抛异常按未清干净处理。
            val deleted = runCatching { target.deleteRecursively() }.getOrDefault(false)
            if (!deleted) {
                allCleaned = false
                Log.w(TAG, "cleanRootfsResidue: $id/$name 未完全删除（deleteRecursively 返回 false）")
            }
        }
        return allCleaned
    }

    /**
     * 只清理工作区的 `tmp/` 残留（强杀进程后留下的下载/解压临时文件），保留 `linux/`
     * 与 `files/`。
     *
     * 与 [cleanRootfsResidue] 的区别：当启动时检测到 INSTALLING 残留但 rootfs 实际完整
     * （[hasRootfs] 为真）时，说明上次安装已落盘成功、只是进程在收尾前被杀，此时 `linux/`
     * 是可用的，只能清 `tmp/`、把状态回收为 READY；若用 [cleanRootfsResidue] 会误删完整
     * rootfs。`linux/` 与 `files/` 在此路径下不应被改动。
     */
    fun cleanTempResidue(id: String): Boolean {
        requireWorkspaceId(id)
        val target = File(workspaceDir(id), "tmp")
        if (!target.exists()) return true
        val deleted = runCatching { target.deleteRecursively() }.getOrDefault(false)
        if (!deleted) {
            Log.w(TAG, "cleanTempResidue: $id/tmp 未完全删除（deleteRecursively 返回 false）")
        }
        return deleted
    }

    fun listFiles(id: String, path: String = ""): List<SandboxFileEntry> {
        val root = filesDir(id).also { it.mkdirs() }
        val dir = resolve(root, path)
        require(dir.exists()) { "Path does not exist: $path" }
        require(dir.isDirectory) { "Path is not a directory: $path" }
        return dir.listFiles().orEmpty()
            .sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .take(MAX_LIST_ENTRIES)
            .map { it.toEntry(root) }
    }

    fun readText(id: String, path: String): String {
        val root = filesDir(id).also { it.mkdirs() }
        val file = resolve(root, path)
        require(file.isFile) { "Path is not a file: $path" }
        require(file.length() <= MAX_READ_BYTES) { "File is too large to read" }
        return file.readText(StandardCharsets.UTF_8)
    }

    fun writeText(id: String, path: String, text: String, overwrite: Boolean): SandboxFileEntry {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_WRITE_BYTES) { "Content is too large" }
        val root = filesDir(id).also { it.mkdirs() }
        val file = resolve(root, path)
        require(!file.exists() || overwrite) { "File already exists: $path" }
        require(!file.exists() || file.isFile) { "Path is not a file: $path" }
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file.toEntry(root)
    }

    fun importFile(id: String, destinationPath: String, fileName: String, input: InputStream): SandboxFileEntry {
        val root = filesDir(id).also { it.mkdirs() }
        val directory = resolve(root, destinationPath)
        directory.mkdirs()
        require(directory.isDirectory) { "Destination is not a directory" }
        val target = resolveConflict(File(directory, safeFileName(fileName)))
        input.use { source -> target.outputStream().use(source::copyTo) }
        return target.toEntry(root)
    }

    fun exportFile(id: String, path: String, output: OutputStream) {
        val root = filesDir(id).also { it.mkdirs() }
        val file = resolve(root, path)
        require(file.isFile) { "Path is not a file: $path" }
        file.inputStream().use { it.copyTo(output) }
    }

    fun deleteFile(id: String, path: String, recursive: Boolean): Boolean {
        require(path.isNotBlank() && path != ".") { "Refusing to delete workspace root" }
        val file = resolve(filesDir(id), path)
        if (!file.exists()) return false
        return if (file.isDirectory) {
            require(recursive) { "Directory delete requires recursive = true" }
            file.deleteRecursively()
        } else file.delete()
    }

    fun executeCommand(
        id: String,
        command: String,
        cwd: String = "",
        timeoutMillis: Long = DEFAULT_COMMAND_TIMEOUT_MS,
        stdin: ByteArray? = null,
    ): SandboxCommandResult {
        require(command.isNotBlank()) { "Command is required" }
        ensureWorkspace(id)
        val workingDir = resolve(filesDir(id), cwd)
        require(workingDir.isDirectory) { "Working directory does not exist: $cwd" }
        return shellRunner.execute(
            SandboxShellContext(
                workspaceId = id,
                command = command,
                cwd = cwd,
                filesDir = filesDir(id),
                linuxDir = linuxDir(id),
                tempDir = tempDir(id),
                timeoutMillis = timeoutMillis,
                stdin = stdin,
            )
        )
    }

    private fun resolve(root: File, path: String): File {
        root.mkdirs()
        val normalized = path.replace('\\', '/').trim().trimStart('/').ifBlank { "." }
        require(!normalized.contains('\u0000')) { "Path contains invalid character" }
        val rootFile = root.canonicalFile
        val target = if (normalized == ".") rootFile else File(rootFile, normalized).canonicalFile
        require(target.path == rootFile.path || target.path.startsWith(rootFile.path + File.separator)) {
            "Path escapes workspace: $path"
        }
        return target
    }

    private fun File.toEntry(root: File) = SandboxFileEntry(
        path = relativeTo(root.canonicalFile).path.replace(File.separatorChar, '/'),
        name = name,
        isDirectory = isDirectory,
        sizeBytes = if (isFile) length() else 0,
        updatedAt = lastModified(),
    )

    private fun resolveConflict(file: File): File {
        if (!file.exists()) return file
        val stem = file.nameWithoutExtension
        val extension = file.extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
        var index = 1
        while (true) {
            val candidate = File(file.parentFile, "$stem ($index)$extension")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private fun safeFileName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "imported_file" }

    private fun requireWorkspaceId(id: String): String {
        require(id.matches(WORKSPACE_ID)) { "Invalid workspace id" }
        return id
    }

    companion object {
        private const val TAG = "SandboxWorkspace"
        private val WORKSPACE_ID = Regex("[A-Za-z0-9._-]+")
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000L
        private const val MAX_READ_BYTES = 8L * 1024L * 1024L
        private const val MAX_WRITE_BYTES = 2L * 1024L * 1024L
        private const val MAX_LIST_ENTRIES = 500
    }
}

/** PRoot 只隔离 Rootfs 视图，不替代 Android 权限或工具审批。 */
class ProotSandboxShellRunner(
    private val nativeLibraryDir: File,
    private val extraBindMounts: List<SandboxBindMount>,
    private val patcher: SandboxRootfsPatcher = SandboxRootfsPatcher(),
) : SandboxShellRunner {
    override fun execute(context: SandboxShellContext): SandboxCommandResult {
        if (!File(context.linuxDir, "bin/sh").isFile) return SandboxCommandResult(127, "", "Rootfs is not installed")
        val proot = File(nativeLibraryDir, "libproot_exec.so")
        val loader = File(nativeLibraryDir, "libproot_loader.so")
        if (!proot.isFile || !loader.isFile) return SandboxCommandResult(127, "", "Sandbox runtime is unavailable")
        context.tempDir.mkdirs()
        patcher.patch(context.linuxDir)
        val process = ProcessBuilder(buildCommand(context, proot))
            .directory(context.filesDir)
            .redirectErrorStream(false)
            .apply {
                environment()["PROOT_LOADER"] = loader.absolutePath
                environment()["PROOT_TMP_DIR"] = context.tempDir.absolutePath
                environment()["TMPDIR"] = context.tempDir.absolutePath
            }
            .start()
        return process.readResult(context.timeoutMillis, context.stdin)
    }

    private fun buildCommand(context: SandboxShellContext, proot: File): List<String> = buildList {
        addAll(listOf(proot.absolutePath, "--root-id", "--link2symlink", "--kill-on-exit", "-r", context.linuxDir.absolutePath, "-w", context.prootCwd(), "-b", "${context.filesDir.absolutePath}:/workspace"))
        extraBindMounts.filter { it.source.exists() }.forEach { mount -> addAll(listOf("-b", "${mount.source.absolutePath}:${mount.target.trimEnd('/')}")) }
        listOf("/dev", "/proc", "/sys").filter { File(it).exists() }.forEach { addAll(listOf("-b", it)) }
        addAll(listOf("/usr/bin/env", "-i", "HOME=/root", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "TERM=xterm-256color", "LANG=C.UTF-8", "LC_ALL=C.UTF-8", "/bin/bash", "-l", "-c", "cd -- \"\$1\" && eval \"\$2\"", "flit-sandbox", context.prootCwd(), context.command))
    }

    private fun SandboxShellContext.prootCwd(): String = cwd.trim().trim('/').takeIf { it.isNotEmpty() }?.let { "/workspace/$it" } ?: "/workspace"
}

enum class SandboxRootfsInstallStage {
    DOWNLOADING,
    EXTRACTING,
    INSTALLED,
}

data class SandboxRootfsInstallProgress(
    val stage: SandboxRootfsInstallStage,
    val bytesRead: Long = 0,
    val totalBytes: Long? = null,
    val entriesExtracted: Int = 0,
    val currentEntry: String? = null,
)

/** 下载并安全解开 tar.gz 或 tar.xz Rootfs。 */
class SandboxRootfsInstaller(
    private val manager: SandboxWorkspaceManager,
    private val patcher: SandboxRootfsPatcher = SandboxRootfsPatcher(),
) {
    fun install(
        workspaceId: String,
        sourceUrl: String,
        onProgress: (SandboxRootfsInstallProgress) -> Unit = {},
    ) {
        require(sourceUrl.startsWith("https://") || sourceUrl.startsWith("http://")) { "Rootfs URL must use HTTP(S)" }
        manager.ensureWorkspace(workspaceId)
        val format = ArchiveFormat.fromUrl(sourceUrl)
        val temp = manager.tempDir(workspaceId)
        val archive = File(temp, "rootfs.${format.extension}")
        val staging = File(temp, "rootfs-staging")
        val linux = manager.linuxDir(workspaceId)
        try {
            staging.deleteRecursively()
            staging.mkdirs()
            download(sourceUrl, archive, onProgress)
            extractTar(archive, staging, format, onProgress)
            checkInterrupted()
            linux.deleteRecursively()
            require(staging.renameTo(linux)) { "Failed to install Rootfs" }
            patcher.patch(linux)
            onProgress(SandboxRootfsInstallProgress(stage = SandboxRootfsInstallStage.INSTALLED))
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    private fun download(
        url: String,
        target: File,
        onProgress: (SandboxRootfsInstallProgress) -> Unit,
    ) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        try {
            require(connection.responseCode in 200..299) { "Rootfs download failed: HTTP ${connection.responseCode}" }
            val totalBytes = connection.contentLengthLong.takeIf { it > 0 }
            target.parentFile?.mkdirs()
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead = 0L
                    var lastReportBytes = 0L
                    while (true) {
                        checkInterrupted()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        bytesRead += read
                        if (bytesRead - lastReportBytes >= PROGRESS_STEP_BYTES || bytesRead == totalBytes) {
                            lastReportBytes = bytesRead
                            onProgress(
                                SandboxRootfsInstallProgress(
                                    stage = SandboxRootfsInstallStage.DOWNLOADING,
                                    bytesRead = bytesRead,
                                    totalBytes = totalBytes,
                                )
                            )
                        }
                    }
                    if (bytesRead == 0L) {
                        onProgress(
                            SandboxRootfsInstallProgress(
                                stage = SandboxRootfsInstallStage.DOWNLOADING,
                                bytesRead = 0,
                                totalBytes = totalBytes,
                            )
                        )
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /** 与上游 RootfsInstaller 保持相同的 tar 读取、链接与权限处理。 */
    internal fun extractTar(
        archive: File,
        targetDir: File,
        format: ArchiveFormat = ArchiveFormat.fromFile(archive),
        onProgress: (SandboxRootfsInstallProgress) -> Unit = {},
    ) {
        format.wrapStream(BufferedInputStream(archive.inputStream())).use { input ->
            var entries = 0
            var pendingName: String? = null
            var pendingLinkName: String? = null
            while (true) {
                checkInterrupted()
                val rawHeader = input.readTarHeader() ?: break
                val header = rawHeader.copy(
                    name = pendingName ?: rawHeader.name,
                    linkName = pendingLinkName ?: rawHeader.linkName,
                )
                pendingName = null
                pendingLinkName = null
                if (header.name.isBlank()) {
                    input.skipFully(header.size.paddedTarSize())
                    continue
                }
                if (header.type == TarEntryType.LONG_NAME) {
                    pendingName = input.readExactly(header.size).toString(StandardCharsets.UTF_8).trimEnd('\u0000', '\n')
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                if (header.type == TarEntryType.LONG_LINK) {
                    pendingLinkName = input.readExactly(header.size).toString(StandardCharsets.UTF_8).trimEnd('\u0000', '\n')
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                if (header.type == TarEntryType.PAX) {
                    val pax = parsePax(input.readExactly(header.size).toString(StandardCharsets.UTF_8))
                    pendingName = pax["path"]
                    pendingLinkName = pax["linkpath"]
                    input.skipFully(header.size.paddingSize())
                    continue
                }
                val target = targetDir.safeResolve(header.name)
                target.parentFile?.mkdirs()
                when (header.type) {
                    TarEntryType.DIRECTORY -> target.mkdirs()
                    TarEntryType.SYMLINK -> createSymlink(targetDir, target, header.linkName)
                    TarEntryType.HARDLINK -> createHardLink(targetDir, target, header.linkName)
                    TarEntryType.FILE -> {
                        target.outputStream().use { output -> input.copyExactly(output, header.size) }
                        target.applyMode(header.mode)
                    }
                    TarEntryType.LONG_NAME,
                    TarEntryType.LONG_LINK,
                    TarEntryType.PAX,
                    TarEntryType.OTHER -> Unit
                }
                if (header.type != TarEntryType.FILE) input.skipFully(header.size)
                input.skipFully(header.size.paddingSize())
                if (header.modTime > 0 && header.type != TarEntryType.SYMLINK) target.setLastModified(header.modTime * 1000)
                entries++
                onProgress(
                    SandboxRootfsInstallProgress(
                        stage = SandboxRootfsInstallStage.EXTRACTING,
                        entriesExtracted = entries,
                        currentEntry = header.name,
                    )
                )
            }
        }
    }

    private fun createSymlink(root: File, target: File, linkName: String) {
        if (linkName.isBlank()) return
        val linkTarget = if (File(linkName).isAbsolute) {
            File(linkName)
        } else {
            val resolved = File(target.parentFile ?: root, linkName).canonicalFile
            val rootFile = root.canonicalFile
            require(resolved.path == rootFile.path || resolved.path.startsWith(rootFile.path + File.separator)) {
                "Symlink escapes rootfs: ${target.name}"
            }
            (target.parentFile ?: root).toPath().relativize(resolved.toPath()).toFile()
        }
        target.delete()
        Files.createSymbolicLink(target.toPath(), linkTarget.toPath())
    }

    private fun createHardLink(root: File, target: File, linkName: String) {
        if (linkName.isBlank()) return
        val source = root.safeResolve(linkName)
        if (!source.exists()) return
        target.delete()
        runCatching { Files.createLink(target.toPath(), source.toPath()) }
            .recoverCatching { error ->
                if (error !is IOException && error !is UnsupportedOperationException && error !is SecurityException) throw error
                source.copyTo(target, overwrite = true)
                target.setReadable(source.canRead(), false)
                target.setWritable(source.canWrite(), true)
                target.setExecutable(source.canExecute(), false)
            }
            .getOrThrow()
    }

    private fun InputStream.readTarHeader(): TarHeader? {
        val header = ByteArray(TAR_BLOCK_SIZE)
        val read = readFullyOrEnd(header)
        if (read == 0) return null
        if (read < TAR_BLOCK_SIZE) throw EOFException("Unexpected EOF while reading tar header")
        if (header.all { it == 0.toByte() }) return null
        val name = header.string(0, 100)
        val prefix = header.string(345, 155)
        val fullName = listOf(prefix, name).filter { it.isNotBlank() }.joinToString("/")
        return TarHeader(
            name = normalizeTarPath(fullName),
            mode = header.octal(100, 8).toInt(),
            size = header.octal(124, 12),
            modTime = header.octal(136, 12),
            type = when (header[156].toInt().toChar()) {
                '0', '\u0000' -> TarEntryType.FILE
                '5' -> TarEntryType.DIRECTORY
                '2' -> TarEntryType.SYMLINK
                '1' -> TarEntryType.HARDLINK
                'L' -> TarEntryType.LONG_NAME
                'K' -> TarEntryType.LONG_LINK
                'x' -> TarEntryType.PAX
                else -> TarEntryType.OTHER
            },
            linkName = header.string(157, 100),
        )
    }

    private fun File.applyMode(mode: Int) {
        setReadable(mode and 0b100_000_000 != 0, false)
        setWritable(mode and 0b010_000_000 != 0, true)
        setExecutable(mode and 0b001_000_000 != 0, false)
    }

    private fun InputStream.copyExactly(output: OutputStream, bytes: Long) {
        var remaining = bytes
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            checkInterrupted()
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw EOFException("Unexpected EOF while extracting tar entry")
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun InputStream.readExactly(bytes: Long): ByteArray {
        require(bytes <= Int.MAX_VALUE) { "Tar entry is too large to buffer: $bytes" }
        val buffer = ByteArray(bytes.toInt())
        val read = readFullyOrEnd(buffer)
        if (read != buffer.size) throw EOFException("Unexpected EOF while reading tar entry")
        return buffer
    }


    private fun InputStream.skipFully(bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            checkInterrupted()
            val skipped = skip(remaining)
            if (skipped > 0) remaining -= skipped
            else if (read() >= 0) remaining--
            else throw EOFException("Unexpected EOF while skipping tar data")
        }
    }

    private fun InputStream.readFullyOrEnd(buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            offset += read
        }
        return offset
    }

    private fun File.safeResolve(path: String): File {
        val normalized = normalizeTarPath(path)
        val root = canonicalFile
        val target = File(root, normalized).canonicalFile
        require(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            "Rootfs entry escapes target directory: $path"
        }
        return target
    }

    private fun normalizeTarPath(path: String): String {
        val normalized = path.replace('\\', '/').trim().trimStart('/').removePrefix("./")
        require(normalized.isNotBlank()) { "Rootfs entry path is blank" }
        require(!normalized.contains('\u0000')) { "Rootfs entry path contains invalid character" }
        require(normalized.split('/').none { it == ".." }) { "Rootfs entry escapes target directory: $path" }
        return normalized
    }

    private fun ByteArray.string(offset: Int, length: Int): String {
        val end = (offset until offset + length).firstOrNull { this[it] == 0.toByte() } ?: (offset + length)
        return copyOfRange(offset, end).toString(StandardCharsets.UTF_8).trim()
    }

    private fun ByteArray.octal(offset: Int, length: Int): Long {
        val value = string(offset, length).trim().lowercase(Locale.US).trimEnd('\u0000')
        return if (value.isBlank()) 0L else value.toLong(8)
    }

    private fun parsePax(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var index = 0
        while (index < text.length) {
            val space = text.indexOf(' ', index)
            if (space < 0) break
            val length = text.substring(index, space).toIntOrNull() ?: break
            val end = (index + length).coerceAtMost(text.length)
            val record = text.substring(space + 1, end).trimEnd('\n')
            val equals = record.indexOf('=')
            if (equals > 0) result[record.substring(0, equals)] = record.substring(equals + 1)
            index += length
        }
        return result
    }

    private fun Long.paddingSize(): Long = (TAR_BLOCK_SIZE - (this % TAR_BLOCK_SIZE)).let { if (it == TAR_BLOCK_SIZE.toLong()) 0L else it }
    private fun Long.paddedTarSize(): Long = this + paddingSize()
    private fun checkInterrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedException("Rootfs installation cancelled") }

    private data class TarHeader(
        val name: String,
        val mode: Int,
        val size: Long,
        val modTime: Long,
        val type: TarEntryType,
        val linkName: String,
    )

    private enum class TarEntryType { FILE, DIRECTORY, SYMLINK, HARDLINK, LONG_NAME, LONG_LINK, PAX, OTHER }

    internal enum class ArchiveFormat(val extension: String) {
        TAR_GZ("tar.gz") { override fun wrapStream(input: InputStream): InputStream = GZIPInputStream(input) },
        TAR_XZ("tar.xz") { override fun wrapStream(input: InputStream): InputStream = XZInputStream(input) };

        abstract fun wrapStream(input: InputStream): InputStream

        companion object {
            fun fromUrl(url: String): ArchiveFormat = when {
                url.substringBefore('?').substringBefore('#').endsWith(".tar.xz") || url.substringBefore('?').substringBefore('#').endsWith(".txz") -> TAR_XZ
                else -> TAR_GZ
            }

            fun fromFile(file: File): ArchiveFormat = fromUrl(file.name)
        }
    }

    private companion object {
        const val TAR_BLOCK_SIZE = 512
        const val BUFFER_SIZE = 64 * 1024
        const val PROGRESS_STEP_BYTES = 512 * 1024
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 60_000
    }
}

private fun Process.readResult(timeoutMillis: Long, stdin: ByteArray?): SandboxCommandResult {
    val stdout = LimitedCollector(inputStream)
    val stderr = LimitedCollector(errorStream)
    val writer = stdin?.let { bytes -> Thread { runCatching { outputStream.use { it.write(bytes) } } }.apply { isDaemon = true; start() } }
    return try {
        val finished = waitFor(timeoutMillis, TimeUnit.MILLISECONDS)
        if (!finished) destroyForcibly()
        writer?.join(1_000); stdout.join(); stderr.join()
        SandboxCommandResult(if (finished) exitValue() else -1, stdout.text(), stderr.text(), !finished, stdout.truncated || stderr.truncated)
    } catch (e: InterruptedException) {
        destroyForcibly(); stdout.join(); stderr.join(); throw e
    }
}

private class LimitedCollector(stream: InputStream) {
    private val content = StringBuilder()
    @Volatile var truncated = false; private set
    private val worker = Thread {
        runCatching { stream.bufferedReader().use { reader ->
            val buffer = CharArray(4096)
            while (true) { val read = reader.read(buffer); if (read < 0) break; synchronized(content) { val remaining = 128 * 1024 - content.length; if (remaining > 0) content.append(buffer, 0, minOf(read, remaining)); if (read > remaining) truncated = true } }
        } }
    }.apply { isDaemon = true; start() }
    fun join() = worker.join(1_000)
    fun text(): String = synchronized(content) { content.toString() }
}
