/*
 * 管理应用内导入的 Python Wheel（.whl）依赖清单，并提供运行时 sys.path 注入所需的路径列表。
 */

package me.rerere.rikkahub.data.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.rerere.rikkahub.data.model.PythonWheel
import me.rerere.rikkahub.data.model.PythonWheelManifest
import java.io.File

class PythonWheelRepository(context: Context) {
    private val appContext = context.applicationContext

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val manifestMutex = Mutex()

    fun wheelsRootDir(): File = File(appContext.filesDir, "python/wheels")

    fun manifestFile(): File = File(wheelsRootDir(), "manifest.json")

    fun wheelRootDir(id: String): File = File(wheelsRootDir(), id)

    fun wheelFile(id: String): File = File(wheelRootDir(id), "wheel.whl")

    fun wheelUnpackedDir(id: String): File = File(wheelRootDir(id), "unpacked")

    suspend fun listWheels(): List<PythonWheel> = readManifest().wheels

    suspend fun readManifest(): PythonWheelManifest = withContext(Dispatchers.IO) {
        val file = manifestFile()
        if (!file.exists() || !file.isFile) return@withContext PythonWheelManifest()

        runCatching { json.decodeFromString<PythonWheelManifest>(file.readText()) }
            .getOrElse { PythonWheelManifest() }
    }

    suspend fun writeManifest(manifest: PythonWheelManifest) = withContext(Dispatchers.IO) {
        val file = manifestFile()
        file.parentFile?.mkdirs()

        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(manifest))
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("Failed to replace manifest")
        }
        if (!tmp.renameTo(file)) {
            throw IllegalStateException("Failed to write manifest")
        }
    }

    suspend fun updateManifest(transform: (PythonWheelManifest) -> PythonWheelManifest): PythonWheelManifest =
        manifestMutex.withLock {
            val current = readManifest()
            val updated = transform(current)
            writeManifest(updated)
            updated
        }

    suspend fun setWheelEnabled(id: String, enabled: Boolean): Boolean {
        val updated = updateManifest { manifest ->
            val wheels = manifest.wheels.map { wheel ->
                if (wheel.id == id) wheel.copy(enabled = enabled) else wheel
            }
            manifest.copy(wheels = wheels)
        }
        return updated.wheels.any { it.id == id && it.enabled == enabled }
    }

    suspend fun deleteWheel(id: String): Boolean = manifestMutex.withLock {
        withContext(Dispatchers.IO) {
            val manifest = readManifest()
            val target = manifest.wheels.firstOrNull { it.id == id } ?: return@withContext false

            val dirOk = runCatching { wheelRootDir(target.id).deleteRecursively() }.getOrDefault(false)
            if (!dirOk) return@withContext false

            val updated = manifest.copy(wheels = manifest.wheels.filterNot { it.id == id })
            writeManifest(updated)
            true
        }
    }

    suspend fun getEnabledSysPaths(): List<String> = withContext(Dispatchers.IO) {
        val wheels = readManifest().wheels
        val paths = buildExtraSysPaths(wheels)
        paths.filter { path ->
            path.isNotBlank() && runCatching { File(path).isDirectory }.getOrDefault(false)
        }
    }

    companion object {
        fun buildExtraSysPaths(wheels: List<PythonWheel>): List<String> {
            val ordered = wheels
                .asSequence()
                .filter { it.enabled }
                .sortedByDescending { it.installedAt }
                .toList()

            val out = LinkedHashSet<String>()
            for (wheel in ordered) {
                for (path in wheel.sysPaths) {
                    if (path.isNotBlank()) out.add(path)
                }
            }
            return out.toList()
        }
    }
}

