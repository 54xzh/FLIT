package me.rerere.rikkahub.workspace

import android.content.Context
import java.io.File

fun sandboxBindMounts(context: Context): List<SandboxBindMount> {
    val appContext = context.applicationContext
    return listOf(
        SandboxBindMount(File(appContext.filesDir, "skills").apply { mkdirs() }, "/skills"),
        SandboxBindMount(File(appContext.filesDir, "upload").apply { mkdirs() }, "/upload"),
        SandboxBindMount(File(appContext.filesDir, "tool_outputs").apply { mkdirs() }, "/tool_outputs"),
    )
}
