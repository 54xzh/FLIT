package me.rerere.rikkahub.workspace

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.utils.JsonInstant

const val WORKSPACE_TRANSFER_EXTENSION = "flitspace"
const val WORKSPACE_TRANSFER_MIME = "application/octet-stream"

private const val FORMAT_VERSION = 2
private const val MANIFEST_ENTRY = "manifest.json"
private const val PAYLOAD_ENTRY = "workspace.tar"
private const val TAR_BLOCK_SIZE = 512
private const val MAX_MANIFEST_BYTES = 256 * 1024
private const val MAX_ARCHIVE_ENTRIES = 1_000_000L
private const val MAX_UNPACKED_BYTES = 64L * 1024 * 1024 * 1024
private const val MAX_TAR_FILE_SIZE = 8L * 1024 * 1024 * 1024 - 1
private const val PROGRESS_STEP_BYTES = 1024L * 1024

enum class WorkspaceTransferStage {
    SCANNING,
    EXPORTING,
    READING,
    IMPORTING,
    FINALIZING,
}

data class WorkspaceTransferProgress(
    val stage: WorkspaceTransferStage,
    val processedBytes: Long = 0,
    val totalBytes: Long? = null,
    val processedEntries: Long = 0,
    val totalEntries: Long? = null,
    val currentEntry: String? = null,
)

@Serializable
data class WorkspaceTransferMount(
    val treeUri: String,
    val sourcePath: String,
    val targetPath: String,
)

@Serializable
data class WorkspaceTransferManifest(
    val formatVersion: Int = FORMAT_VERSION,
    val sourceWorkspaceId: String,
    val name: String,
    val toolApprovals: String,
    val rootfsStatus: String,
    val rootfsSourceUrl: String? = null,
    val rootfsVersion: String? = null,
    val rootfsInstalledAt: Long? = null,
    val sourceAbi: String? = null,
    val createdAt: Long,
    val payloadBytes: Long,
    val payloadEntries: Long,
    val mounts: List<WorkspaceTransferMount> = emptyList(),
)

data class WorkspaceArchiveSummary(
    val bytes: Long,
    val entries: Long,
)

internal fun estimateWorkspaceImportBytes(
    payloadBytes: Long,
    payloadEntries: Long,
    blockSizeBytes: Long,
): Long {
    require(payloadBytes >= 0 && payloadEntries >= 0 && blockSizeBytes > 0)
    val entryOverhead = Math.multiplyExact(payloadEntries, blockSizeBytes)
    val reserve = maxOf(32L * 1024 * 1024, payloadBytes / 20)
        .coerceAtMost(512L * 1024 * 1024)
    return Math.addExact(Math.addExact(payloadBytes, entryOverhead), reserve)
}

/** 独立的单工作区迁移格式：ZIP 负责压缩与校验，内层 TAR 保留 Linux 文件属性。 */
class WorkspaceTransferArchive(
    private val rootfsInstaller: SandboxRootfsInstaller,
) {
    fun scan(
        workspaceDir: File,
        onProgress: (WorkspaceTransferProgress) -> Unit = {},
    ): WorkspaceArchiveSummary {
        var bytes = 0L
        var entries = 0L
        var lastReport = 0L
        val hardLinks = HashSet<Any>()

        includedRoots(workspaceDir).forEach { root ->
            walkWithoutFollowingLinks(root) { _, _, attributes ->
                checkInterrupted()
                entries = Math.addExact(entries, 1)
                require(entries <= MAX_ARCHIVE_ENTRIES) { "Workspace contains too many files" }
                if (attributes.isRegularFile) {
                    require(attributes.size() <= MAX_TAR_FILE_SIZE) { "A workspace file is too large to export" }
                    val key = attributes.fileKey()
                    if (key == null || hardLinks.add(key)) {
                        bytes = Math.addExact(bytes, attributes.size())
                        require(bytes <= MAX_UNPACKED_BYTES) { "Workspace is too large to export" }
                    }
                }
                if (bytes - lastReport >= PROGRESS_STEP_BYTES) {
                    lastReport = bytes
                    onProgress(
                        WorkspaceTransferProgress(
                            stage = WorkspaceTransferStage.SCANNING,
                            processedBytes = bytes,
                            processedEntries = entries,
                        )
                    )
                }
            }
        }
        val summary = WorkspaceArchiveSummary(bytes = bytes, entries = entries)
        onProgress(
            WorkspaceTransferProgress(
                stage = WorkspaceTransferStage.SCANNING,
                processedBytes = bytes,
                totalBytes = bytes,
                processedEntries = entries,
                totalEntries = entries,
            )
        )
        return summary
    }

    fun export(
        workspaceDir: File,
        manifest: WorkspaceTransferManifest,
        output: OutputStream,
        onProgress: (WorkspaceTransferProgress) -> Unit = {},
    ) {
        require(manifest.formatVersion in 1..FORMAT_VERSION)
        validateManifest(manifest)
        ZipOutputStream(output).use { zip ->
            val manifestBytes = JsonInstant.encodeToString(manifest).toByteArray(StandardCharsets.UTF_8)
            require(manifestBytes.size <= MAX_MANIFEST_BYTES) { "Workspace metadata is too large" }
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(manifestBytes)
            zip.closeEntry()

            zip.putNextEntry(ZipEntry(PAYLOAD_ENTRY))
            TarWriter(zip).write(
                roots = includedRoots(workspaceDir),
                totalBytes = manifest.payloadBytes,
                totalEntries = manifest.payloadEntries,
                onProgress = onProgress,
            )
            zip.closeEntry()
        }
    }

    fun import(
        input: InputStream,
        stagingDir: File,
        onManifest: (WorkspaceTransferManifest) -> Unit = {},
        onProgress: (WorkspaceTransferProgress) -> Unit = {},
    ): WorkspaceTransferManifest {
        ZipInputStream(BufferedInputStream(input)).use { zip ->
            val manifestEntry = zip.nextEntry ?: error("Workspace package is empty")
            require(!manifestEntry.isDirectory && manifestEntry.name == MANIFEST_ENTRY) {
                "Workspace package metadata is missing"
            }
            val manifestBytes = zip.readBytesLimited(MAX_MANIFEST_BYTES)
            zip.closeEntry()
            val manifest = JsonInstant.decodeFromString<WorkspaceTransferManifest>(
                manifestBytes.toString(StandardCharsets.UTF_8)
            )
            validateManifest(manifest)
            onManifest(manifest)
            onProgress(
                WorkspaceTransferProgress(
                    stage = WorkspaceTransferStage.READING,
                    totalBytes = manifest.payloadBytes,
                    totalEntries = manifest.payloadEntries,
                )
            )

            val payloadEntry = zip.nextEntry ?: error("Workspace package content is missing")
            require(!payloadEntry.isDirectory && payloadEntry.name == PAYLOAD_ENTRY) {
                "Workspace package content is missing"
            }
            require(stagingDir.mkdirs() || stagingDir.isDirectory) { "Cannot prepare workspace import directory" }

            var bytes = 0L
            var entries = 0L
            var lastReport = 0L
            rootfsInstaller.extractTarStream(zip, stagingDir) { name, size, supported ->
                require(supported) { "Workspace package contains an unsupported file type" }
                validatePayloadPath(name)
                entries = Math.addExact(entries, 1)
                bytes = Math.addExact(bytes, size)
                require(entries <= manifest.payloadEntries) { "Workspace package contains unexpected files" }
                require(bytes <= manifest.payloadBytes) { "Workspace package is larger than declared" }
                if (bytes - lastReport >= PROGRESS_STEP_BYTES || entries == manifest.payloadEntries) {
                    lastReport = bytes
                    onProgress(
                        WorkspaceTransferProgress(
                            stage = WorkspaceTransferStage.IMPORTING,
                            processedBytes = bytes,
                            totalBytes = manifest.payloadBytes,
                            processedEntries = entries,
                            totalEntries = manifest.payloadEntries,
                            currentEntry = name,
                        )
                    )
                }
            }
            zip.requireWorkspaceTarEnd()
            zip.closeEntry()
            require(bytes == manifest.payloadBytes && entries == manifest.payloadEntries) {
                "Workspace package is incomplete"
            }
            require(zip.nextEntry == null) { "Workspace package contains unexpected content" }
            require(Files.isDirectory(File(stagingDir, "files").toPath(), LinkOption.NOFOLLOW_LINKS)) {
                "Workspace files directory is missing"
            }
            val linux = File(stagingDir, "linux").toPath()
            require(!Files.exists(linux, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(linux, LinkOption.NOFOLLOW_LINKS)) {
                "Workspace Linux directory is invalid"
            }
            return manifest
        }
    }

    private fun includedRoots(workspaceDir: File): List<File> {
        require(Files.isDirectory(workspaceDir.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Workspace directory is missing or invalid"
        }
        val files = File(workspaceDir, "files")
        require(Files.isDirectory(files.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "Workspace files directory is missing or invalid"
        }
        val linux = File(workspaceDir, "linux")
        return buildList {
            add(files)
            if (Files.exists(linux.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                require(Files.isDirectory(linux.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                    "Workspace Linux path is not a directory"
                }
                add(linux)
            }
        }
    }

    private fun validateManifest(manifest: WorkspaceTransferManifest) {
        require(manifest.formatVersion in 1..FORMAT_VERSION) { "Unsupported workspace package version" }
        require(manifest.sourceWorkspaceId.isNotBlank()) { "Workspace ID is missing" }
        require(manifest.name.isNotBlank() && manifest.name.length <= 200) { "Workspace name is invalid" }
        require(manifest.toolApprovals.length <= 64 * 1024) { "Workspace settings are too large" }
        require(manifest.payloadBytes in 0..MAX_UNPACKED_BYTES) { "Workspace package size is invalid" }
        require(manifest.payloadEntries in 1..MAX_ARCHIVE_ENTRIES) { "Workspace package file count is invalid" }
        require(manifest.mounts.size <= 1_000) { "Workspace package contains too many mounted folders" }
        manifest.mounts.forEach { mount ->
            require(mount.treeUri.length in 1..8_192) { "Mounted folder permission is invalid" }
            require(mount.sourcePath.length in 1..4_096) { "Mounted folder source is invalid" }
            require(mount.targetPath.length in 1..1_024) { "Mounted folder path is invalid" }
        }
    }

    private fun validatePayloadPath(name: String) {
        val normalized = name.trim('/').replace('\\', '/')
        require(
            normalized == "files" || normalized.startsWith("files/") ||
                normalized == "linux" || normalized.startsWith("linux/")
        ) { "Workspace package contains an unsupported path" }
    }
}

private class TarWriter(private val output: OutputStream) {
    private val hardLinks = HashMap<Any, String>()
    private var processedBytes = 0L
    private var processedEntries = 0L
    private var paxIndex = 0L
    private var lastReport = 0L

    fun write(
        roots: List<File>,
        totalBytes: Long,
        totalEntries: Long,
        onProgress: (WorkspaceTransferProgress) -> Unit,
    ) {
        roots.forEach { root ->
            walkWithoutFollowingLinks(root) { path, archivePath, attributes ->
                checkInterrupted()
                when {
                    attributes.isDirectory -> writeHeader(
                        name = "$archivePath/",
                        mode = path.mode(defaultMode = 0b111_101_101),
                        size = 0,
                        modifiedSeconds = attributes.lastModifiedTime().toMillis() / 1000,
                        type = '5',
                    )
                    attributes.isSymbolicLink -> writeHeader(
                        name = archivePath,
                        mode = 0b111_111_111,
                        size = 0,
                        modifiedSeconds = attributes.lastModifiedTime().toMillis() / 1000,
                        type = '2',
                        linkName = Files.readSymbolicLink(path).toString().replace('\\', '/'),
                    )
                    attributes.isRegularFile -> {
                        val key = attributes.fileKey()
                        val firstPath = key?.let(hardLinks::get)
                        if (firstPath != null) {
                            writeHeader(
                                name = archivePath,
                                mode = path.mode(defaultMode = 0b110_100_100),
                                size = 0,
                                modifiedSeconds = attributes.lastModifiedTime().toMillis() / 1000,
                                type = '1',
                                linkName = firstPath,
                            )
                        } else {
                            if (key != null) hardLinks[key] = archivePath
                            writeHeader(
                                name = archivePath,
                                mode = path.mode(defaultMode = 0b110_100_100),
                                size = attributes.size(),
                                modifiedSeconds = attributes.lastModifiedTime().toMillis() / 1000,
                                type = '0',
                            )
                            Files.newInputStream(path).use { input ->
                                val buffer = ByteArray(64 * 1024)
                                var remaining = attributes.size()
                                while (remaining > 0) {
                                    checkInterrupted()
                                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                    if (read < 0) error("Workspace file changed while exporting")
                                    output.write(buffer, 0, read)
                                    processedBytes = Math.addExact(processedBytes, read.toLong())
                                    remaining -= read
                                    reportProgress(totalBytes, totalEntries, archivePath, onProgress)
                                }
                            }
                            writePadding(attributes.size())
                        }
                    }
                    else -> error("Workspace contains an unsupported file type: $archivePath")
                }
                processedEntries = Math.addExact(processedEntries, 1)
                reportProgress(totalBytes, totalEntries, archivePath, onProgress, force = true)
            }
        }
        require(processedBytes == totalBytes && processedEntries == totalEntries) {
            "Workspace changed while exporting"
        }
        output.write(ByteArray(TAR_BLOCK_SIZE * 2))
    }

    private fun reportProgress(
        totalBytes: Long,
        totalEntries: Long,
        currentEntry: String,
        onProgress: (WorkspaceTransferProgress) -> Unit,
        force: Boolean = false,
    ) {
        if (!force && processedBytes - lastReport < PROGRESS_STEP_BYTES) return
        lastReport = processedBytes
        onProgress(
            WorkspaceTransferProgress(
                stage = WorkspaceTransferStage.EXPORTING,
                processedBytes = processedBytes,
                totalBytes = totalBytes,
                processedEntries = processedEntries,
                totalEntries = totalEntries,
                currentEntry = currentEntry,
            )
        )
    }

    private fun writeHeader(
        name: String,
        mode: Int,
        size: Long,
        modifiedSeconds: Long,
        type: Char,
        linkName: String = "",
    ) {
        require(size <= MAX_TAR_FILE_SIZE) { "A workspace file is too large to export" }
        val paxValues = linkedMapOf<String, String>()
        if (name.toByteArray(StandardCharsets.UTF_8).size > 100) paxValues["path"] = name
        if (linkName.toByteArray(StandardCharsets.UTF_8).size > 100) paxValues["linkpath"] = linkName
        if (paxValues.isNotEmpty()) {
            val content = paxValues.entries.joinToString(separator = "") { (key, value) -> paxRecord(key, value) }
                .toByteArray(StandardCharsets.UTF_8)
            writeRawHeader(
                name = "PaxHeaders/${paxIndex++}",
                mode = 0b110_100_100,
                size = content.size.toLong(),
                modifiedSeconds = modifiedSeconds,
                type = 'x',
                linkName = "",
            )
            output.write(content)
            writePadding(content.size.toLong())
        }
        writeRawHeader(
            name = if (name.toByteArray(StandardCharsets.UTF_8).size <= 100) name else "pax-entry",
            mode = mode,
            size = size,
            modifiedSeconds = modifiedSeconds,
            type = type,
            linkName = if (linkName.toByteArray(StandardCharsets.UTF_8).size <= 100) linkName else "",
        )
    }

    private fun writeRawHeader(
        name: String,
        mode: Int,
        size: Long,
        modifiedSeconds: Long,
        type: Char,
        linkName: String,
    ) {
        val header = ByteArray(TAR_BLOCK_SIZE)
        header.putString(0, 100, name)
        header.putOctal(100, 8, mode.toLong())
        header.putOctal(108, 8, 0)
        header.putOctal(116, 8, 0)
        header.putOctal(124, 12, size)
        header.putOctal(136, 12, modifiedSeconds.coerceAtLeast(0))
        for (index in 148 until 156) header[index] = ' '.code.toByte()
        header[156] = type.code.toByte()
        header.putString(157, 100, linkName)
        header.putString(257, 6, "ustar")
        header.putString(263, 2, "00")
        val checksum = header.sumOf { it.toUByte().toInt() }
        val checksumText = checksum.toString(8).padStart(6, '0')
        header.putString(148, 6, checksumText)
        header[154] = 0
        header[155] = ' '.code.toByte()
        output.write(header)
    }

    private fun writePadding(size: Long) {
        val padding = ((TAR_BLOCK_SIZE - size % TAR_BLOCK_SIZE) % TAR_BLOCK_SIZE).toInt()
        if (padding > 0) output.write(ByteArray(padding))
    }
}

private fun includedArchivePath(root: Path, path: Path): String {
    val relative = root.relativize(path).toString().replace('\\', '/')
    return if (relative.isBlank()) root.fileName.toString() else "${root.fileName}/$relative"
}

private inline fun walkWithoutFollowingLinks(
    rootFile: File,
    crossinline onEntry: (path: Path, archivePath: String, attributes: BasicFileAttributes) -> Unit,
) {
    val root = rootFile.toPath()
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                onEntry(dir, includedArchivePath(root, dir), attrs)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                onEntry(file, includedArchivePath(root, file), attrs)
                return FileVisitResult.CONTINUE
            }
        }
    )
}

private fun Path.mode(defaultMode: Int): Int = runCatching {
    Files.getPosixFilePermissions(this, LinkOption.NOFOLLOW_LINKS).fold(0) { mode, permission ->
        mode or when (permission) {
            PosixFilePermission.OWNER_READ -> 0b100_000_000
            PosixFilePermission.OWNER_WRITE -> 0b010_000_000
            PosixFilePermission.OWNER_EXECUTE -> 0b001_000_000
            PosixFilePermission.GROUP_READ -> 0b000_100_000
            PosixFilePermission.GROUP_WRITE -> 0b000_010_000
            PosixFilePermission.GROUP_EXECUTE -> 0b000_001_000
            PosixFilePermission.OTHERS_READ -> 0b000_000_100
            PosixFilePermission.OTHERS_WRITE -> 0b000_000_010
            PosixFilePermission.OTHERS_EXECUTE -> 0b000_000_001
        }
    }
}.getOrDefault(defaultMode)

private fun ByteArray.putString(offset: Int, length: Int, value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    require(bytes.size <= length) { "TAR header value is too long" }
    bytes.copyInto(this, offset)
}

private fun ByteArray.putOctal(offset: Int, length: Int, value: Long) {
    val text = value.toString(8)
    require(text.length <= length - 1) { "TAR numeric value is too large" }
    putString(offset, length - 1, text.padStart(length - 1, '0'))
    this[offset + length - 1] = 0
}

private fun paxRecord(key: String, value: String): String {
    val body = "$key=$value\n"
    var length = body.toByteArray(StandardCharsets.UTF_8).size + 2
    while (true) {
        val candidate = "$length $body"
        val actual = candidate.toByteArray(StandardCharsets.UTF_8).size
        if (actual == length) return candidate
        length = actual
    }
}

private fun InputStream.readBytesLimited(limit: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        require(total <= limit) { "Workspace metadata is too large" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun InputStream.requireWorkspaceTarEnd() {
    val finalBlock = ByteArray(TAR_BLOCK_SIZE)
    var offset = 0
    while (offset < finalBlock.size) {
        val read = read(finalBlock, offset, finalBlock.size - offset)
        require(read > 0) { "Workspace package TAR ending is incomplete" }
        offset += read
    }
    require(finalBlock.all { it == 0.toByte() }) { "Workspace package TAR ending is invalid" }
    require(read() < 0) { "Workspace package contains trailing TAR data" }
}

private fun checkInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("Workspace transfer cancelled")
}
