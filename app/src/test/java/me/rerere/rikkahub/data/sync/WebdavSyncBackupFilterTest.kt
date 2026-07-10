package me.rerere.rikkahub.data.sync

import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebdavSyncBackupFilterTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun sandboxBackupOnlyIncludesFilesNotLinuxOrTmp() {
        // 还原真实沙盒结构：<base>/sandbox_workspaces/<id>/{files,linux,tmp}
        val base = temporaryFolder.newFolder("filesDir")
        val sandboxRoot = File(base, "sandbox_workspaces").apply { mkdirs() }

        val workspaceId = "abc-123"
        val workspaceDir = File(sandboxRoot, workspaceId).apply { mkdirs() }

        // files/ 用户文件 —— 应进入备份
        val filesDir = File(workspaceDir, "files").apply { mkdirs() }
        File(filesDir, "notes.txt").writeText("hello")
        File(filesDir, "project").apply { mkdirs() }
        File(filesDir, "project/main.py").writeText("print('hi')")
        // 用户在 files/ 下自建的 linux/、tmp/ 子目录 —— 是 /workspace 里的用户文件，必须保留
        File(filesDir, "linux").apply { mkdirs() }
        File(filesDir, "linux/readme.md").writeText("user linux notes")
        File(filesDir, "tmp").apply { mkdirs() }
        File(filesDir, "tmp/important.txt").writeText("must not be lost")

        // linux/ rootfs —— 体积大，应被排除
        val linuxDir = File(workspaceDir, "linux").apply { mkdirs() }
        File(linuxDir, "bin").apply { mkdirs() }
        File(linuxDir, "bin/sh").writeText("fake-rootfs-binary")
        File(linuxDir, "etc").apply { mkdirs() }
        File(linuxDir, "etc/hostname").writeText("workspace")

        // tmp/ 临时文件 —— 应被排除
        val tmpDir = File(workspaceDir, "tmp").apply { mkdirs() }
        File(tmpDir, "scratch.tmp").writeText("temp")

        val backupFile = temporaryFolder.newFile("backup.zip")
        ZipOutputStream(backupFile.outputStream()).use { zipOut ->
            addSandboxWorkspacesToZip(zipOut, sandboxRoot)
        }

        val entries = ZipFile(backupFile).use { zip ->
            zip.entries().asSequence().map { it.name }.toList()
        }

        // files/ 内容应被完整保留（含子目录）
        assertTrue(
            "files/notes.txt should be backed up, got: $entries",
            entries.any { it == "sandbox_workspaces/$workspaceId/files/notes.txt" },
        )
        assertTrue(
            "files/project/main.py should be backed up, got: $entries",
            entries.any { it == "sandbox_workspaces/$workspaceId/files/project/main.py" },
        )
        // 反例：files/linux/、files/tmp/ 是用户文件，必须保留
        assertTrue(
            "files/linux/readme.md is a user file and must be backed up, got: $entries",
            entries.any { it == "sandbox_workspaces/$workspaceId/files/linux/readme.md" },
        )
        assertTrue(
            "files/tmp/important.txt is a user file and must be backed up, got: $entries",
            entries.any { it == "sandbox_workspaces/$workspaceId/files/tmp/important.txt" },
        )

        // linux/ 与 tmp/ 整棵子树不应出现（工作区根下，不含 files/ 内的同名目录）
        assertFalse(
            "linux/ rootfs must be excluded, got: $entries",
            entries.any { it.startsWith("sandbox_workspaces/$workspaceId/linux/") },
        )
        assertFalse(
            "tmp/ must be excluded, got: $entries",
            entries.any { it.startsWith("sandbox_workspaces/$workspaceId/tmp/") },
        )
    }

    @Test
    fun filesOnlyBackupAcrossMultipleWorkspaces() {
        val base = temporaryFolder.newFolder("filesDir")
        val sandboxRoot = File(base, "sandbox_workspaces").apply { mkdirs() }

        listOf("ws-one", "ws-two").forEach { id ->
            val ws = File(sandboxRoot, id).apply { mkdirs() }
            File(ws, "files").apply { mkdirs() }.let { File(it, "a.txt").writeText("a") }
            // 用户在 files 下自建的同名目录必须保留
            File(ws, "files").apply { mkdirs() }.let {
                File(it, "tmp").apply { mkdirs() }
                File(File(it, "tmp"), "keep.txt").writeText("keep")
            }
            File(ws, "linux").apply { mkdirs() }.let { File(it, "bin").apply { mkdirs() } }
            File(ws, "tmp").apply { mkdirs() }.let { File(it, "b.tmp").writeText("b") }
        }

        val backupFile = temporaryFolder.newFile("backup.zip")
        ZipOutputStream(backupFile.outputStream()).use { zipOut ->
            addSandboxWorkspacesToZip(zipOut, sandboxRoot)
        }

        val entries = ZipFile(backupFile).use { zip ->
            zip.entries().asSequence().map { it.name }.toList()
        }

        assertEquals(
            listOf(
                "sandbox_workspaces/ws-one/files/a.txt",
                "sandbox_workspaces/ws-one/files/tmp/keep.txt",
                "sandbox_workspaces/ws-two/files/a.txt",
                "sandbox_workspaces/ws-two/files/tmp/keep.txt",
            ),
            entries.sorted(),
        )
    }

    @Test
    fun workspaceWithoutFilesDirIsSkipped() {
        // 没有 files/ 的工作区不打任何条目
        val base = temporaryFolder.newFolder("filesDir")
        val sandboxRoot = File(base, "sandbox_workspaces").apply { mkdirs() }

        File(sandboxRoot, "empty-ws").apply { mkdirs() }
        File(sandboxRoot, "empty-ws/linux").apply { mkdirs() }
        File(sandboxRoot, "empty-ws/linux/x").writeText("x")
        File(sandboxRoot, "has-files").apply { mkdirs() }
        File(sandboxRoot, "has-files/files").apply { mkdirs() }
        File(sandboxRoot, "has-files/files/keep.txt").writeText("k")

        val backupFile = temporaryFolder.newFile("backup.zip")
        ZipOutputStream(backupFile.outputStream()).use { zipOut ->
            addSandboxWorkspacesToZip(zipOut, sandboxRoot)
        }

        val entries = ZipFile(backupFile).use { zip ->
            zip.entries().asSequence().map { it.name }.toList()
        }

        assertEquals(
            listOf("sandbox_workspaces/has-files/files/keep.txt"),
            entries.sorted(),
        )
    }
}
