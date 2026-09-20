package me.rerere.rikkahub.data.localai

import android.content.Context
import android.os.Build
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.security.MessageDigest

/**
 * The native runtime packages are kept in separate roots so installing one engine never replaces
 * the other engine's marker or libraries.
 */
enum class LocalRuntimePackage(
    val format: LocalModelFormat,
    val displayName: String,
    val engine: String,
    private val rootName: String,
) {
    GGUF(
        format = LocalModelFormat.GGUF,
        displayName = "GGUF",
        engine = "llama.cpp",
        rootName = "runtime",
    ),
    LITERT_LM(
        format = LocalModelFormat.LITERT_LM,
        displayName = "LiteRT-LM",
        engine = "litert-lm",
        rootName = "runtime-litert",
    ),
    ;

    /** Root directory for this package under the app's no-backup storage. */
    fun root(context: Context): File = File(context.noBackupFilesDir, "local-ai/$rootName")

    /**
     * Finds a complete installation after validating its marker and all files listed by it.
     * Callers should invoke this method from a background dispatcher because it reads and hashes
     * files from app storage.
     */
    @Volatile private var verifiedFilesFingerprint: String? = null

    fun installedDirectory(context: Context): File? = runCatching { readInstalledDirectory(context) }.getOrNull()

    private fun readInstalledDirectory(context: Context): File? {
        val packageRoot = root(context)
        val marker = File(packageRoot, MARKER_NAME)
        if (!marker.isFile || marker.length() !in 1..MAX_MARKER_BYTES) return null

        val markerObject = runCatching {
            JsonInstant.parseToJsonElement(marker.readText()).asJsonObjectOrNull()
        }.getOrNull() ?: return null
        val version = markerObject.stringValue("version") ?: return null
        if (!VERSION_PATTERN.matches(version)) return null
        if (this == LITERT_LM && version != LITERT_VERSION) return null

        val abi = markerObject.stringValue("abi") ?: return null
        if (abi !in Build.SUPPORTED_ABIS) return null
        if (this == LITERT_LM && markerObject.stringValue("engine") != engine) return null

        val directory = File(packageRoot, version)
        val rootPath = runCatching { packageRoot.canonicalPath }.getOrNull() ?: return null
        val directoryPath = runCatching { directory.canonicalPath }.getOrNull() ?: return null
        if (!directoryPath.startsWith(rootPath + File.separator) || !directory.isDirectory) return null

        val library = File(directory, libraryRelativePath)
        if (!library.isFile) return null

        // New LiteRT packages include every extracted shared library in the marker. Validate the
        // complete list so a partially extracted AAR cannot be reported as ready. Older GGUF
        // markers only contained version and ABI, so their required main library is sufficient.
        if (this == LITERT_LM) {
            val files = markerObject["files"] as? JsonArray ?: return null
            val seen = HashSet<String>(files.size)
            val checks = mutableListOf<Pair<File, String>>()
            val fingerprint = StringBuilder(directoryPath)
            for (fileElement in files) {
                val fileObject = fileElement.asJsonObjectOrNull() ?: return null
                val path = fileObject.stringValue("path") ?: return null
                val expectedSha256 = fileObject.stringValue("sha256") ?: return null
                if (!isSafeRelativePath(path) || !seen.add(path) || !SHA256_PATTERN.matches(expectedSha256)) {
                    return null
                }
                val installedFile = File(directory, path)
                if (!isWithin(directory, installedFile) || !installedFile.isFile) return null
                if (installedFile.length() <= 0) return null
                fingerprint.append('|').append(path).append(':').append(expectedSha256)
                    .append(':').append(installedFile.length()).append(':').append(installedFile.lastModified())
                checks += installedFile to expectedSha256
            }
            if (!seen.contains(libraryRelativePath)) return null
            val key = fingerprint.toString()
            if (verifiedFilesFingerprint != key) {
                if (checks.any { (file, expected) -> !sha256(file).equals(expected, ignoreCase = true) }) return null
                verifiedFilesFingerprint = key
            }
        }

        return directory
    }

    /** Returns the main JNI library for a complete installation, or null when it is unavailable. */
    fun libraryFile(context: Context): File? = installedDirectory(context)?.let { File(it, libraryRelativePath) }

    private val libraryRelativePath: String
        get() = when (this) {
            GGUF -> "lib/libflit_local_llama.so"
            LITERT_LM -> "lib/liblitertlm_jni.so"
        }

    companion object {
        const val LITERT_VERSION = "0.16.1"
        const val LITERT_AAR_SHA256 = "e407719c1a29f2685fcb6aa3feea0b9f7155fe316c66dae053c1b5b2f54cda73"

        const val LITERT_AAR_URL =
            "https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/0.16.1/litertlm-android-0.16.1.aar"

        private const val MARKER_NAME = "installed.json"
        private const val MAX_MARKER_BYTES = 64 * 1024L
        private val VERSION_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
        private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")

        /** Parses a WorkManager engine value while accepting the enum name for compatibility. */
        fun fromEngine(value: String?): LocalRuntimePackage? = when (value?.lowercase()) {
            null, "", "gguf", "llama.cpp", "llama_cpp" -> GGUF
            "litert-lm", "litert_lm", "litertlm" -> LITERT_LM
            else -> null
        }

        internal fun isSafeRelativePath(path: String): Boolean {
            if (path.isEmpty() || path.startsWith('/') || path.contains('\\')) return false
            if (path.split('/').any { it.isEmpty() || it == "." || it == ".." }) return false
            return true
        }

        internal fun isWithin(root: File, child: File): Boolean {
            val rootPath = runCatching { root.canonicalPath }.getOrNull() ?: return false
            val childPath = runCatching { child.canonicalPath }.getOrNull() ?: return false
            return childPath.startsWith(rootPath + File.separator)
        }

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

        private fun JsonObject.stringValue(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

        private fun kotlinx.serialization.json.JsonElement.asJsonObjectOrNull(): JsonObject? = this as? JsonObject
    }
}
