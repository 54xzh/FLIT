package me.rerere.rikkahub.workspace

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceTransferArchiveTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun manager() = SandboxWorkspaceManager(
        baseDir = temporaryFolder.newFolder(),
        shellRunner = object : SandboxShellRunner {
            override fun execute(context: SandboxShellContext) = SandboxCommandResult(0, "", "")
        },
    )

    @Test
    fun roundTripPreservesFilesLinksAndExecutablePermission() {
        val manager = manager()
        val archive = WorkspaceTransferArchive(SandboxRootfsInstaller(manager))
        manager.ensureWorkspace("source")
        File(manager.filesDir("source"), "文档").mkdirs()
        val longName = "一段很长的文件名用于验证扩展路径支持".repeat(4) + ".txt"
        File(manager.filesDir("source"), "文档/$longName").writeText("notes")
        File(manager.filesDir("source"), "report").writeText("plain")
        File(manager.filesDir("source"), "report ").writeText("trailing-space")
        File(manager.linuxDir("source"), "bin").mkdirs()
        val executable = File(manager.linuxDir("source"), "bin/hello").apply {
            writeText("hello")
            setExecutable(true, false)
        }
        Files.createSymbolicLink(File(manager.linuxDir("source"), "bin/sh").toPath(), File("hello").toPath())
        Files.createLink(File(manager.linuxDir("source"), "bin/hello-copy").toPath(), executable.toPath())
        // Common rootfs self-link: import must keep "." instead of rewriting to empty.
        File(manager.linuxDir("source"), "usr/bin").mkdirs()
        Files.createSymbolicLink(
            File(manager.linuxDir("source"), "usr/bin/X11").toPath(),
            File(".").toPath(),
        )

        val summary = archive.scan(manager.workspaceDir("source"))
        val manifest = manifest(summary)
        val bytes = ByteArrayOutputStream().also { output ->
            archive.export(manager.workspaceDir("source"), manifest, output)
        }.toByteArray()
        val target = temporaryFolder.newFolder("restored").also { it.delete() }

        val restoredManifest = archive.import(ByteArrayInputStream(bytes), target)

        assertEquals(manifest, restoredManifest)
        assertEquals("notes", File(target, "files/文档/$longName").readText())
        assertEquals("plain", File(target, "files/report").readText())
        assertEquals("trailing-space", File(target, "files/report ").readText())
        assertEquals("hello", File(target, "linux/bin/hello").readText())
        assertTrue(File(target, "linux/bin/hello").canExecute())
        assertEquals("hello", Files.readSymbolicLink(File(target, "linux/bin/sh").toPath()).toString())
        assertEquals(".", Files.readSymbolicLink(File(target, "linux/usr/bin/X11").toPath()).toString())
        assertTrue(Files.isSameFile(File(target, "linux/bin/hello").toPath(), File(target, "linux/bin/hello-copy").toPath()))
    }

    @Test
    fun roundTripPreservesMountedFolderMarkers() {
        val manager = manager()
        val archive = WorkspaceTransferArchive(SandboxRootfsInstaller(manager))
        manager.ensureWorkspace("mount-source")
        File(manager.filesDir("mount-source"), "keep.txt").writeText("keep")
        val summary = archive.scan(manager.workspaceDir("mount-source"))
        val manifest = manifest(summary).copy(
            mounts = listOf(
                WorkspaceTransferMount(
                    treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
                    sourcePath = "/storage/emulated/0/Documents",
                    targetPath = "/workspace/Documents",
                )
            )
        )
        val bytes = ByteArrayOutputStream().also { output ->
            archive.export(manager.workspaceDir("mount-source"), manifest, output)
        }.toByteArray()
        val target = temporaryFolder.newFolder("mount-restored").also { it.delete() }

        val restored = archive.import(ByteArrayInputStream(bytes), target)

        assertEquals(manifest.mounts, restored.mounts)
        assertEquals("keep", File(target, "files/keep.txt").readText())
        assertEquals(false, File(target, "files/Documents").exists())
    }

    @Test
    fun importRejectsPathOutsideWorkspacePayload() {
        val manifest = manifest(WorkspaceArchiveSummary(bytes = 1, entries = 1))
        val bytes = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(JsonInstant.encodeToString(manifest).toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("workspace.tar"))
                zip.writeTarFile("../outside", byteArrayOf(1))
                zip.closeEntry()
            }
        }.toByteArray()
        val target = temporaryFolder.newFolder("unsafe").also { it.delete() }

        try {
            WorkspaceTransferArchive(SandboxRootfsInstaller(manager()))
                .import(ByteArrayInputStream(bytes), target)
            fail("Expected unsafe path to be rejected")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals(false, File(target.parentFile, "outside").exists())
    }

    @Test
    fun importRejectsOversizedTarMetadataBeforeAllocatingIt() {
        val manifest = manifest(WorkspaceArchiveSummary(bytes = 0, entries = 1))
        val bytes = packageWithPayload(manifest) { zip ->
            zip.writeTarHeader(name = "PaxHeaders/huge", size = 2L * 1024 * 1024, type = 'x')
        }
        val target = temporaryFolder.newFolder("metadata").also { it.delete() }

        try {
            WorkspaceTransferArchive(SandboxRootfsInstaller(manager()))
                .import(ByteArrayInputStream(bytes), target)
            fail("Expected oversized TAR metadata to be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("metadata"))
        }
    }

    @Test
    fun scanRejectsSymlinkedWorkspaceRoot() {
        val manager = manager()
        val archive = WorkspaceTransferArchive(SandboxRootfsInstaller(manager))
        val outside = temporaryFolder.newFolder("outside-scan")
        val workspace = manager.workspaceDir("linked-workspace")
        Files.createSymbolicLink(workspace.toPath(), outside.toPath())

        try {
            archive.scan(workspace)
            fail("Expected symlinked workspace root to be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun importRejectsDataAfterTarEnding() {
        val manifest = manifest(WorkspaceArchiveSummary(bytes = 1, entries = 1))
        val bytes = packageWithPayload(manifest) { zip ->
            zip.writeTarFile("files/a", byteArrayOf(1))
            zip.write(byteArrayOf(9))
        }
        val target = temporaryFolder.newFolder("trailing").also { it.delete() }

        try {
            WorkspaceTransferArchive(SandboxRootfsInstaller(manager()))
                .import(ByteArrayInputStream(bytes), target)
            fail("Expected trailing TAR data to be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("trailing"))
        }
    }

    @Test
    fun importRejectsTopLevelFilesSymlink() {
        val manifest = manifest(WorkspaceArchiveSummary(bytes = 0, entries = 1))
        val outside = temporaryFolder.newFolder("private-data")
        val bytes = packageWithPayload(manifest) { zip ->
            zip.writeTarHeader(name = "files", size = 0, type = '2', linkName = outside.absolutePath)
            zip.write(ByteArray(1024))
        }
        val target = temporaryFolder.newFolder("linked-root").also { it.delete() }

        try {
            WorkspaceTransferArchive(SandboxRootfsInstaller(manager()))
                .import(ByteArrayInputStream(bytes), target)
            fail("Expected linked files root to be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("files directory"))
        }
    }

    @Test
    fun importRejectsDirectoryReplacedBySymlink() {
        val manifest = manifest(WorkspaceArchiveSummary(bytes = 0, entries = 2))
        val outside = temporaryFolder.newFolder("outside-metadata")
        val bytes = packageWithPayload(manifest) { zip ->
            zip.writeTarHeader(name = "files/", size = 0, type = '5')
            zip.writeTarHeader(name = "files", size = 0, type = '2', linkName = outside.absolutePath)
            zip.write(ByteArray(1024))
        }
        val target = temporaryFolder.newFolder("replaced-directory").also { it.delete() }

        try {
            WorkspaceTransferArchive(SandboxRootfsInstaller(manager()))
                .import(ByteArrayInputStream(bytes), target)
            fail("Expected replaced directory to be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("directory was replaced"))
        }
    }

    @Test
    fun importRejectsHardLinkWithMissingTarget() {
        val manifest = manifest(WorkspaceArchiveSummary(bytes = 0, entries = 2))
        val bytes = packageWithPayload(manifest) { zip ->
            zip.writeTarHeader(name = "files/", size = 0, type = '5')
            zip.writeTarHeader(name = "files/missing", size = 0, type = '1', linkName = "files/not-there")
            zip.write(ByteArray(1024))
        }
        val target = temporaryFolder.newFolder("missing-hardlink").also { it.delete() }

        try {
            WorkspaceTransferArchive(SandboxRootfsInstaller(manager()))
                .import(ByteArrayInputStream(bytes), target)
            fail("Expected missing hard link target to be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("does not exist"))
        }
    }

    @Test
    fun importSpaceEstimateIncludesOneBlockPerEntry() {
        val required = estimateWorkspaceImportBytes(
            payloadBytes = 1_000_000,
            payloadEntries = 1_000_000,
            blockSizeBytes = 4096,
        )

        assertTrue(required >= 4_097_000_000L)
    }

    private fun manifest(summary: WorkspaceArchiveSummary) = WorkspaceTransferManifest(
        sourceWorkspaceId = "source-id",
        name = "Test workspace",
        toolApprovals = "{}",
        rootfsStatus = "READY",
        sourceAbi = "arm64-v8a",
        createdAt = 1,
        payloadBytes = summary.bytes,
        payloadEntries = summary.entries,
    )
}

private fun packageWithPayload(
    manifest: WorkspaceTransferManifest,
    writePayload: (ZipOutputStream) -> Unit,
): ByteArray = ByteArrayOutputStream().also { output ->
    ZipOutputStream(output).use { zip ->
        zip.putNextEntry(ZipEntry("manifest.json"))
        zip.write(JsonInstant.encodeToString(manifest).toByteArray(StandardCharsets.UTF_8))
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("workspace.tar"))
        writePayload(zip)
        zip.closeEntry()
    }
}.toByteArray()

private fun ZipOutputStream.writeTarFile(name: String, content: ByteArray) {
    writeTarHeader(name = name, size = content.size.toLong(), type = '0')
    write(content)
    val padding = (512 - content.size % 512) % 512
    if (padding > 0) write(ByteArray(padding))
    write(ByteArray(1024))
}

private fun ZipOutputStream.writeTarHeader(name: String, size: Long, type: Char, linkName: String = "") {
    val header = ByteArray(512)
    name.toByteArray(StandardCharsets.UTF_8).copyInto(header, 0)
    putOctal(header, 100, 8, 0b110_100_100)
    putOctal(header, 108, 8, 0)
    putOctal(header, 116, 8, 0)
    putOctal(header, 124, 12, size)
    putOctal(header, 136, 12, 0)
    for (index in 148 until 156) header[index] = ' '.code.toByte()
    header[156] = type.code.toByte()
    linkName.toByteArray(StandardCharsets.UTF_8).copyInto(header, 157)
    "ustar".toByteArray().copyInto(header, 257)
    "00".toByteArray().copyInto(header, 263)
    val checksum = header.sumOf { it.toUByte().toInt() }.toString(8).padStart(6, '0')
    checksum.toByteArray().copyInto(header, 148)
    header[154] = 0
    header[155] = ' '.code.toByte()
    write(header)
}

private fun putOctal(target: ByteArray, offset: Int, length: Int, value: Long) {
    value.toString(8).padStart(length - 1, '0').toByteArray().copyInto(target, offset)
    target[offset + length - 1] = 0
}
