package me.rerere.rikkahub.workspace

import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import me.rerere.rikkahub.data.db.entity.WorkspaceType
import me.rerere.rikkahub.data.db.entity.workspaceToolNames

class SandboxWorkspaceManagerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private fun manager() = SandboxWorkspaceManager(
        baseDir = temporaryFolder.newFolder("sandboxes"),
        shellRunner = object : SandboxShellRunner {
            override fun execute(context: SandboxShellContext) = SandboxCommandResult(0, "", "")
        },
    )

    @Test
    fun filesStayInsideTheirSandbox() {
        val manager = manager()
        val entry = manager.writeText("sandbox-1", "notes/todo.txt", "hello", overwrite = true)

        assertEquals("notes/todo.txt", entry.path)
        assertEquals("hello", manager.readText("sandbox-1", "notes/todo.txt"))
        try {
            manager.writeText("sandbox-1", "../outside.txt", "blocked", overwrite = true)
            fail("Expected traversal path to be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun deletingWorkspaceDoesNotTouchAnotherWorkspace() {
        val manager = manager()
        manager.writeText("sandbox-a", "a.txt", "a", overwrite = true)
        manager.writeText("sandbox-b", "b.txt", "b", overwrite = true)

        manager.deleteWorkspace("sandbox-a")

        assertEquals("b", manager.readText("sandbox-b", "b.txt"))
    }

    @Test
    fun deletingWorkspaceDoesNotFollowDirectorySymlink() {
        val manager = manager()
        manager.ensureWorkspace("sandbox-link")
        val outside = temporaryFolder.newFolder("outside").apply {
            resolve("keep.txt").writeText("keep")
        }
        Files.createSymbolicLink(
            File(manager.filesDir("sandbox-link"), "outside-link").toPath(),
            outside.toPath(),
        )

        assertTrue(manager.deleteWorkspace("sandbox-link"))

        assertEquals("keep", File(outside, "keep.txt").readText())
    }

    @Test
    fun startupCleanupRemovesOnlyUnknownWorkspaceDirectories() {
        val manager = manager()
        manager.writeText("known", "keep.txt", "keep", overwrite = true)
        manager.writeText("orphan", "remove.txt", "remove", overwrite = true)

        manager.cleanupOrphanedWorkspaceDirectories(setOf("known"))

        assertEquals("keep", manager.readText("known", "keep.txt"))
        assertEquals(false, manager.workspaceDir("orphan").exists())
    }

    @Test
    fun cleanRootfsResidueRemovesLinuxAndTmpButKeepsFiles() {
        val manager = manager()
        // 模拟恢复后本机残留的旧 rootfs：linux/、tmp/ 与用户 files/ 共存
        manager.ensureWorkspace("sandbox-1")
        File(manager.linuxDir("sandbox-1"), "bin").mkdirs()
        File(manager.linuxDir("sandbox-1"), "bin/sh").writeText("old")
        File(manager.tempDir("sandbox-1"), "scratch").writeText("tmp")
        manager.writeText("sandbox-1", "docs/note.txt", "keep me", overwrite = true)

        val cleaned = manager.cleanRootfsResidue("sandbox-1")

        assertTrue(cleaned)
        assertEquals(false, manager.hasRootfs("sandbox-1"))          // 旧 bin/sh 被删
        assertEquals(false, manager.linuxDir("sandbox-1").exists())
        assertEquals(false, manager.tempDir("sandbox-1").exists())
        // files/ 用户文件保留
        assertEquals("keep me", manager.readText("sandbox-1", "docs/note.txt"))
    }

    @Test
    fun cleanRootfsResidueIsSafeWhenNothingToClean() {
        val manager = manager()
        manager.ensureWorkspace("sandbox-1")
        manager.writeText("sandbox-1", "a.txt", "a", overwrite = true)

        // 没有 linux/ 与 tmp/ 残留，清理应无副作用并返回成功
        val cleaned = manager.cleanRootfsResidue("sandbox-1")

        assertTrue(cleaned)
        assertEquals("a", manager.readText("sandbox-1", "a.txt"))
    }

    @Test
    fun sandboxAndLightweightExposeDifferentToolProtocols() {
        val sandboxTools = workspaceToolNames(WorkspaceType.SANDBOX)
        val lightweightTools = workspaceToolNames(WorkspaceType.LIGHTWEIGHT)

        assertEquals(listOf("sandbox_read_file", "sandbox_write_file", "sandbox_edit_file", "sandbox_shell"), sandboxTools)
        assertEquals(false, sandboxTools.any { it == "eval_python" || it == "run_skill_script" })
        assertEquals(false, lightweightTools.any { it.startsWith("sandbox_") })
    }

    @Test
    fun rootfsTarMatchesUpstreamPaxAndLinkHandling() {
        val manager = manager()
        val installer = SandboxRootfsInstaller(manager)
        val archive = temporaryFolder.newFile("rootfs.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { output ->
            val pax = paxRecord("path", "bin/hello")
            output.writeTarEntry(name = "PaxHeaders/hello", type = 'x', content = pax.toByteArray())
            output.writeTarEntry(name = "ignored", type = '0', mode = 0b111_101_101, content = "hello".toByteArray())
            output.writeTarEntry(name = "bin/sh", type = '2', linkName = "hello")
            output.writeTarEntry(name = "bin/absolute-link", type = '2', linkName = "/usr/bin/hello")
            // Debian/Ubuntu rootfs commonly has usr/bin/X11 -> .
            output.writeTarEntry(name = "usr/bin/", type = '5', mode = 0b111_101_101)
            output.writeTarEntry(name = "usr/bin/X11", type = '2', linkName = ".")
            output.writeTarEntry(name = "bin/hello-copy", type = '1', linkName = "bin/hello")
            output.write(ByteArray(1024))
        }
        val target = temporaryFolder.newFolder("rootfs")

        installer.extractTar(archive, target)

        assertEquals("hello", File(target, "bin/hello").readText())
        assertTrue(File(target, "bin/hello").canExecute())
        assertEquals("hello", File(target, "bin/hello-copy").readText())
        assertEquals("hello", Files.readSymbolicLink(File(target, "bin/sh").toPath()).toString())
        assertEquals("/usr/bin/hello", Files.readSymbolicLink(File(target, "bin/absolute-link").toPath()).toString())
        assertEquals(".", Files.readSymbolicLink(File(target, "usr/bin/X11").toPath()).toString())
    }

    @Test
    fun rootfsTarSkipsUnsupportedEntryDataOnlyOnce() {
        val archive = temporaryFolder.newFile("rootfs-other.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { output ->
            output.writeTarEntry(name = "a.txt", type = '0', content = "hello".toByteArray())
            output.writeTarEntry(name = "sparse.bin", type = 'S', content = ByteArray(700) { 1 })
            output.writeTarEntry(name = "b.txt", type = '0', content = "world".toByteArray())
            output.write(ByteArray(1024))
        }
        val target = temporaryFolder.newFolder("rootfs-other")

        SandboxRootfsInstaller(manager()).extractTar(archive, target)

        assertEquals("hello", File(target, "a.txt").readText())
        assertEquals("world", File(target, "b.txt").readText())
    }

    @Test
    fun rootfsPatcherAppliesAndroidProotDefaults() {
        val linuxDir = temporaryFolder.newFolder("rootfs-patch")
        File(linuxDir, "etc").mkdirs()
        File(linuxDir, "etc/resolv.conf").writeText("nameserver 127.0.0.53\n")
        File(linuxDir, "etc/group").writeText("root:x:0:\n")

        SandboxRootfsPatcher().patch(
            linuxDir,
            SandboxRootfsPatchOptions(
                nameservers = listOf("9.9.9.9", "8.8.8.8"),
                hostname = "workspace-test",
                groupIds = listOf(3003, 9997),
            )
        )

        assertEquals(
            """
            # Generated by RikkaHub workspace.
            nameserver 9.9.9.9
            nameserver 8.8.8.8
            options edns0 trust-ad

            """.trimIndent(),
            File(linuxDir, "etc/resolv.conf").readText()
        )
        assertTrue(File(linuxDir, "etc/hosts").readText().contains("127.0.0.1 localhost workspace-test"))
        assertEquals("workspace-test\n", File(linuxDir, "etc/hostname").readText())
        assertEquals("LANG=C.UTF-8\n", File(linuxDir, "etc/default/locale").readText())
        assertTrue(File(linuxDir, "etc/group").readText().contains("android_gid_3003:x:3003:"))
        assertTrue(File(linuxDir, "etc/group").readText().contains("android_gid_9997:x:9997:"))
        assertTrue(File(linuxDir, "tmp").canWrite())
        assertTrue(File(linuxDir, "var/tmp").canWrite())
        assertTrue(File(linuxDir, "root").isDirectory)
    }

    private fun paxRecord(key: String, value: String): String {
        val body = "$key=$value\n"
        var length = body.toByteArray().size + 2
        while (true) {
            val record = "$length $body"
            if (record.toByteArray().size == length) return record
            length = record.toByteArray().size
        }
    }

    private fun java.io.OutputStream.writeTarEntry(
        name: String,
        type: Char,
        mode: Int = 0b110_100_100,
        linkName: String = "",
        content: ByteArray = ByteArray(0),
    ) {
        val header = ByteArray(512)
        header.writeTarString(0, 100, name)
        header.writeTarString(100, 8, mode.toString(8))
        header.writeTarString(124, 12, content.size.toString(8))
        header[156] = type.code.toByte()
        header.writeTarString(157, 100, linkName)
        write(header)
        write(content)
        write(ByteArray((512 - content.size % 512) % 512))
    }

    private fun ByteArray.writeTarString(offset: Int, length: Int, value: String) {
        value.toByteArray().copyInto(this, destinationOffset = offset, endIndex = minOf(value.length, length - 1))
    }
}
