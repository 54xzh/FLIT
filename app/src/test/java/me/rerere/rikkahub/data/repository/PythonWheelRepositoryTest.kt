/*
 * 验证 Python wheel 清单的 sys.path 生成规则（排序与去重）。
 */

package me.rerere.rikkahub.data.repository

import me.rerere.rikkahub.data.model.PythonWheel
import org.junit.Assert.assertEquals
import org.junit.Test

class PythonWheelRepositoryTest {
    @Test
    fun buildExtraSysPaths_ordersByInstalledAtDesc_andDedups() {
        val wheels = listOf(
            PythonWheel(
                id = "a",
                displayName = "a.whl",
                sha256 = "sha-a",
                installedAt = 1L,
                enabled = true,
                sysPaths = listOf("/a", "/common"),
            ),
            PythonWheel(
                id = "b",
                displayName = "b.whl",
                sha256 = "sha-b",
                installedAt = 2L,
                enabled = true,
                sysPaths = listOf("/b", "/common"),
            ),
            PythonWheel(
                id = "c",
                displayName = "c.whl",
                sha256 = "sha-c",
                installedAt = 3L,
                enabled = false,
                sysPaths = listOf("/c"),
            ),
        )

        val paths = PythonWheelRepository.buildExtraSysPaths(wheels)
        assertEquals(listOf("/b", "/common", "/a"), paths)
    }

    @Test
    fun buildExtraSysPaths_ignoresBlankPaths() {
        val wheels = listOf(
            PythonWheel(
                id = "a",
                displayName = "a.whl",
                sha256 = "sha-a",
                installedAt = 1L,
                enabled = true,
                sysPaths = listOf("", "  ", "/a"),
            ),
        )

        val paths = PythonWheelRepository.buildExtraSysPaths(wheels)
        assertEquals(listOf("/a"), paths)
    }
}
