package me.rerere.rikkahub.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import me.rerere.rikkahub.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * Global Crash Handler to capture uncaught exceptions and show a friendly crash screen.
 */
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            // 1. Generate Crash Report
            val report = buildString {
                appendLine("LastChat Crash Report")
                appendLine("========================")
                appendLine("Time: ${System.currentTimeMillis()}")
                appendLine("App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Flavor: ${BuildConfig.FLAVOR}")
                appendLine("Device: ${Build.BRAND} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("Thread: ${thread.name}")
                appendLine()
                appendLine("Exception:")
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                appendLine(sw.toString())
                appendLine("========================")
            }

            // 2. Save to Cache File (Avoid Intent Size Limit)
            val crashFile = File(context.cacheDir, "crash_${System.currentTimeMillis()}.txt")
            crashFile.writeText(report)

            // 3. Launch CrashActivity
            // We use a string for the class name to avoid direct dependency issues if needed,
            // but here we know the package.
            val intent = Intent().apply {
                setClassName(context.packageName, "me.rerere.rikkahub.ui.activity.CrashActivity")
                putExtra("crash_file", crashFile.absolutePath)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(intent)

            // 4. Terminate Process
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(10)
        } catch (e: Exception) {
            Log.e("CrashHandler", "Error during crash handling", e)
            // Fallback to default handler if our handler fails
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        fun init(context: Context) {
            val handler = CrashHandler(context)
            Thread.setDefaultUncaughtExceptionHandler(handler)
        }
    }
}
