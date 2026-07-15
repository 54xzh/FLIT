package me.rerere.rikkahub.ui.pages.storage

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StorageStringsZhCompletenessTest {
    @Test
    fun `values zh rCN contains required storage keys`() {
        val stringsFile = resolveZhStringsFile()
        val content = stringsFile.readText(Charsets.UTF_8)
        val requiredKeys = listOf(
            "storage_images_empty_global_hint",
            "storage_files_empty_global_hint",
            "storage_chat_records_sheet_selected_summary",
            "storage_confirm_delete_selected_images_desc_global",
            "storage_confirm_delete_selected_files_desc_global",
        )

        val missingKeys = requiredKeys.filterNot { key -> content.contains("name=\"$key\"") }
        assertTrue("Missing keys in values-zh-rCN/strings.xml: $missingKeys", missingKeys.isEmpty())
    }

    private fun resolveZhStringsFile(): File {
        val fromModuleDir = File("src/main/res/values-zh-rCN/strings.xml")
        if (fromModuleDir.exists()) return fromModuleDir

        val fromRepoRoot = File("app/src/main/res/values-zh-rCN/strings.xml")
        if (fromRepoRoot.exists()) return fromRepoRoot

        error("Cannot find values-zh-rCN/strings.xml from current working directory")
    }
}
