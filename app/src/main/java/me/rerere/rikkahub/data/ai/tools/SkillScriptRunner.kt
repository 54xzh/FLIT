package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.repository.PythonWheelRepository
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File

class SkillScriptRunner(private val context: Context) {
    private fun ensurePython(): Python {
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(context))
        }
        return Python.getInstance()
    }

    suspend fun run(
        scriptFile: File,
        inputJson: String,
        workDir: File,
        timeoutMs: Long,
        maxStdoutChars: Int,
        maxStderrChars: Int,
    ): JsonElement = withContext(Dispatchers.IO) {
        if (!scriptFile.exists() || !scriptFile.isFile) {
            return@withContext buildJsonObject {
                put("ok", false)
                put("error", "Script file not found")
            }
        }

        workDir.mkdirs()

        val py = runCatching { ensurePython() }
            .getOrElse { e ->
                return@withContext buildJsonObject {
                    put("ok", false)
                    put("error", "Python runtime init failed: ${e.message}")
                }
            }

        val extraSysPaths = try {
            PythonWheelRepository(context).getEnabledSysPaths()
        } catch (_: Exception) {
            emptyList()
        }

        val jsonResult = withTimeoutOrNull(timeoutMs) {
            val runner = py.getModule("skill_runner")
            runner.callAttr(
                "run_script",
                scriptFile.absolutePath,
                inputJson,
                workDir.absolutePath,
                maxStdoutChars,
                maxStderrChars,
                extraSysPaths,
            ).toString()
        } ?: return@withContext buildJsonObject {
            put("ok", false)
            put("error", "Timeout")
        }

        runCatching {
            JsonInstant.parseToJsonElement(jsonResult)
        }.getOrElse { e ->
            buildJsonObject {
                put("ok", false)
                put("error", "Invalid script output: ${e.message}")
            }
        }
    }
}

