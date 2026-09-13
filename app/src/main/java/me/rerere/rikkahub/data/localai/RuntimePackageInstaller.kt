package me.rerere.rikkahub.data.localai

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile

/**
 * Installs a runtime archive into app-private storage. Locally selected archives are checked
 * against their manifest before activation.
 */
class RuntimePackageInstaller(private val context: Context) {
    data class VerifiedPackage(
        val version: String,
        val abi: String,
        val expectedSha256: String,
        val expectedLibrarySha256: String? = null,
    )

    /** Imports a locally selected runtime archive. */
    suspend fun installFromUri(uri: Uri): VerifiedPackage = withContext(Dispatchers.IO) {
        val importDirectory = File(context.cacheDir, "local-ai-runtime-import")
        check(importDirectory.mkdirs() || importDirectory.isDirectory) { "Unable to prepare runtime import" }
        val archive = File.createTempFile("runtime-", ".zip", importDirectory)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                archive.outputStream().use(input::copyTo)
            } ?: error("Could not open the selected runtime package")
            check(archive.length() > 0L) { "The selected runtime package is empty" }
            val manifest = readManifest(archive)
            val specification = VerifiedPackage(
                version = manifest.version,
                abi = manifest.abi,
                expectedSha256 = sha256(archive),
                expectedLibrarySha256 = manifest.librarySha256,
            )
            install(archive, specification)
            specification
        } finally {
            archive.delete()
        }
    }

    /** Verifies and activates an archive that has already been downloaded into private storage. */
    suspend fun installDownloadedArchive(archive: File): VerifiedPackage = withContext(Dispatchers.IO) {
        check(archive.isFile && archive.length() > 0L) { "The downloaded runtime package is empty" }
        val manifest = readManifest(archive)
        val specification = VerifiedPackage(
            version = manifest.version,
            abi = manifest.abi,
            expectedSha256 = sha256(archive),
            expectedLibrarySha256 = manifest.librarySha256,
        )
        install(archive, specification)
        specification
    }

    suspend fun install(archive: File, specification: VerifiedPackage) = withContext(Dispatchers.IO) {
        require(specification.version.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid runtime version" }
        check(sha256(archive).equals(specification.expectedSha256, ignoreCase = true)) {
            "Runtime package integrity check failed"
        }
        check(specification.abi == currentAbi()) { "Runtime package is for a different device" }

        val root = File(context.noBackupFilesDir, "local-ai/runtime")
        val staging = File(root, ".staging-${specification.version}")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Unable to prepare runtime installation" }
        try {
            ZipInputStream(archive.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val destination = File(staging, entry.name)
                    val rootPath = staging.canonicalPath + File.separator
                    check(destination.canonicalPath.startsWith(rootPath)) { "Unsafe runtime archive entry" }
                    if (entry.isDirectory) {
                        check(destination.mkdirs() || destination.isDirectory) { "Unable to unpack runtime" }
                    } else {
                        destination.parentFile?.mkdirs()
                        destination.outputStream().use(zip::copyTo)
                    }
                    zip.closeEntry()
                }
            }
            check(File(staging, "runtime.json").isFile) { "Runtime package is incomplete" }
            val library = File(staging, "lib/libflit_local_llama.so")
            check(library.isFile) { "GGUF runtime library is missing" }
            specification.expectedLibrarySha256?.let { expected ->
                check(sha256(library).equals(expected, ignoreCase = true)) {
                    "Runtime library integrity check failed"
                }
            }
            val destination = File(root, specification.version)
            destination.deleteRecursively()
            check(staging.renameTo(destination)) { "Unable to activate runtime" }
            File(root, "installed.json").writeText(
                "{\"version\":\"${specification.version}\",\"abi\":\"${specification.abi}\"}",
            )
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    private fun currentAbi(): String = android.os.Build.SUPPORTED_ABIS.firstOrNull()
        ?: error("No supported ABI")

    private fun readManifest(archive: File): RuntimeManifest = ZipFile(archive).use { zip ->
        val entry = zip.getEntry("runtime.json") ?: error("Runtime package is missing runtime.json")
        check(entry.size in 1..MAX_MANIFEST_BYTES) { "Runtime package manifest is invalid" }
        val root = zip.getInputStream(entry).bufferedReader().use { it.readText() }
        val objectValue = JsonInstant.parseToJsonElement(root).jsonObject
        val version = objectValue["version"]?.jsonPrimitive?.contentOrNull
            ?: error("Runtime package has no version")
        val abi = objectValue["abi"]?.jsonPrimitive?.contentOrNull
            ?: error("Runtime package has no ABI")
        val librarySha256 = objectValue["files"]?.jsonArray
            ?.firstOrNull { file ->
                file.jsonObject["path"]?.jsonPrimitive?.contentOrNull == "lib/libflit_local_llama.so"
            }
            ?.jsonObject?.get("sha256")?.jsonPrimitive?.contentOrNull
            ?: error("Runtime package has no GGUF library checksum")
        RuntimeManifest(version, abi, librarySha256)
    }

    private data class RuntimeManifest(
        val version: String,
        val abi: String,
        val librarySha256: String,
    )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_MANIFEST_BYTES = 64 * 1024L
    }
}
