package me.rerere.rikkahub.data.localai

import android.content.Context
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import me.rerere.rikkahub.utils.JsonInstant
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.util.zip.ZipFile

internal data class RuntimeManifestFile(
    val path: String,
    val sha256: String,
)

internal data class RuntimeArchiveManifest(
    val runtimePackage: LocalRuntimePackage,
    val version: String,
    val abi: String,
    val files: List<RuntimeManifestFile>,
)

/** Pure manifest parsing is kept separate from ZIP and Android storage operations for testing. */
internal fun parseRuntimeManifest(json: String): RuntimeArchiveManifest {
    val root = runCatching { JsonInstant.parseToJsonElement(json) as? JsonObject }
        .getOrNull() ?: error("Runtime package manifest is invalid")
    val version = root.stringValue("version") ?: error("Runtime package has no version")
    val abi = root.stringValue("abi") ?: error("Runtime package has no ABI")
    require(VERSION_PATTERN.matches(version)) { "Invalid runtime version" }
    require(ABI_PATTERN.matches(abi)) { "Invalid runtime ABI" }

    val engineElement = root["engine"]
    val engine = (engineElement as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    require(engineElement == null || engine != null) { "Runtime package engine is invalid" }
    val runtimePackage = when (engine) {
        null -> LocalRuntimePackage.GGUF // Existing GGUF archives predate the engine field.
        "llama.cpp" -> LocalRuntimePackage.GGUF
        "litert-lm" -> LocalRuntimePackage.LITERT_LM
        else -> error("Unsupported runtime engine")
    }
    if (runtimePackage == LocalRuntimePackage.LITERT_LM) {
        check(version == LocalRuntimePackage.LITERT_VERSION) {
            "Unsupported LiteRT-LM runtime version"
        }
    }

    val files = root["files"] as? JsonArray ?: error("Runtime package has no files")
    val seen = HashSet<String>(files.size)
    val parsedFiles = files.map { fileElement ->
        val file = fileElement as? JsonObject ?: error("Runtime package file entry is invalid")
        val path = file.stringValue("path") ?: error("Runtime package file has no path")
        val sha256 = file.stringValue("sha256") ?: error("Runtime package file has no checksum")
        require(LocalRuntimePackage.isSafeRelativePath(path)) { "Unsafe runtime archive entry" }
        require(path != RUNTIME_MANIFEST_NAME) { "Runtime manifest cannot list itself" }
        require(SHA256_PATTERN.matches(sha256)) { "Runtime package checksum is invalid" }
        check(seen.add(path)) { "Runtime package lists a file more than once" }
        RuntimeManifestFile(path, sha256.lowercase())
    }
    val mainPath = when (runtimePackage) {
        LocalRuntimePackage.GGUF -> GGUF_LIBRARY_PATH
        LocalRuntimePackage.LITERT_LM -> LITERT_LIBRARY_PATH
    }
    check(parsedFiles.any { it.path == mainPath }) {
        "Runtime package has no ${runtimePackage.displayName} library checksum"
    }
    return RuntimeArchiveManifest(runtimePackage, version, abi, parsedFiles)
}

private fun JsonObject.stringValue(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

private val VERSION_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
private val ABI_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
private const val RUNTIME_MANIFEST_NAME = "runtime.json"
private const val GGUF_LIBRARY_PATH = "lib/libflit_local_llama.so"
private const val LITERT_LIBRARY_PATH = "lib/liblitertlm_jni.so"

/**
 * Installs a runtime archive into app-private storage. Locally selected archives are checked
 * against their manifest before activation.
 */
class RuntimePackageInstaller(private val context: Context) {
    data class PackageFile(
        val path: String,
        val sha256: String,
    )

    data class VerifiedPackage(
        val version: String,
        val abi: String,
        val expectedSha256: String,
        val expectedLibrarySha256: String? = null,
        val runtimePackage: LocalRuntimePackage = LocalRuntimePackage.GGUF,
        val expectedFiles: List<PackageFile> = emptyList(),
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

            val expectedSha256 = sha256(archive)
            val manifest = if (hasRuntimeManifest(archive)) {
                readManifest(archive)
            } else {
                // A locally selected LiteRT AAR is also safe to import because the complete AAR
                // is pinned to the same checksum as the downloaded package.
                check(expectedSha256.equals(LocalRuntimePackage.LITERT_AAR_SHA256, ignoreCase = true)) {
                    "Runtime package is missing runtime.json"
                }
                null
            }
            val specification = if (manifest != null) {
                manifest.toSpecification(expectedSha256)
            } else {
                VerifiedPackage(
                    version = LocalRuntimePackage.LITERT_VERSION,
                    abi = selectLiteRtAbi(archive),
                    expectedSha256 = expectedSha256,
                    runtimePackage = LocalRuntimePackage.LITERT_LM,
                )
            }
            install(archive, specification)
            specification
        } finally {
            archive.delete()
        }
    }

    /** Verifies and activates an archive that has already been downloaded into private storage. */
    suspend fun installDownloadedArchive(
        archive: File,
        runtimePackage: LocalRuntimePackage = LocalRuntimePackage.GGUF,
    ): VerifiedPackage = withContext(Dispatchers.IO) {
        check(archive.isFile && archive.length() > 0L) { "The downloaded runtime package is empty" }
        val expectedSha256 = sha256(archive)
        if (runtimePackage == LocalRuntimePackage.LITERT_LM) {
            check(expectedSha256.equals(LocalRuntimePackage.LITERT_AAR_SHA256, ignoreCase = true)) {
                "LiteRT-LM AAR integrity check failed"
            }
        }
        val manifest = if (hasRuntimeManifest(archive)) readManifest(archive) else null
        if (manifest != null) {
            check(manifest.runtimePackage == runtimePackage) {
                "Runtime package engine does not match the requested package"
            }
            val specification = manifest.toSpecification(expectedSha256)
            install(archive, specification)
            specification
        } else {
            check(runtimePackage == LocalRuntimePackage.LITERT_LM) {
                "GGUF runtime package is missing runtime.json"
            }
            check(expectedSha256.equals(LocalRuntimePackage.LITERT_AAR_SHA256, ignoreCase = true)) {
                "LiteRT-LM AAR integrity check failed"
            }
            val specification = VerifiedPackage(
                version = LocalRuntimePackage.LITERT_VERSION,
                abi = selectLiteRtAbi(archive),
                expectedSha256 = expectedSha256,
                runtimePackage = LocalRuntimePackage.LITERT_LM,
            )
            install(archive, specification)
            specification
        }
    }

    /**
     * Verifies and activates an archive. GGUF callers from older code can keep using the existing
     * two-argument API; LiteRT callers pass a specification produced from its manifest or AAR.
     */
    suspend fun install(archive: File, specification: VerifiedPackage) = withContext(Dispatchers.IO) {
        installationLock.withLock { installLocked(archive, specification) }
    }

    private fun installLocked(archive: File, specification: VerifiedPackage) {
        require(specification.version.matches(VERSION_PATTERN)) { "Invalid runtime version" }
        check(archive.isFile && archive.length() > 0L) { "The runtime package is empty" }
        check(sha256(archive).equals(specification.expectedSha256, ignoreCase = true)) {
            "Runtime package integrity check failed"
        }
        check(Build.SUPPORTED_ABIS.contains(specification.abi)) {
            "Runtime package is for a different device"
        }

        if (specification.runtimePackage == LocalRuntimePackage.LITERT_LM &&
            specification.version != LocalRuntimePackage.LITERT_VERSION
        ) {
            error("Unsupported LiteRT-LM runtime version")
        }

        if (hasRuntimeManifest(archive)) {
            installManifestArchive(archive, specification, readManifest(archive))
        } else {
            check(specification.runtimePackage == LocalRuntimePackage.LITERT_LM) {
                "Runtime package is missing runtime.json"
            }
            installLiteRtAar(archive, specification)
        }
    }

    private fun installManifestArchive(
        archive: File,
        specification: VerifiedPackage,
        manifest: RuntimeArchiveManifest,
    ) {
        check(manifest.runtimePackage == specification.runtimePackage) {
            "Runtime package engine does not match its specification"
        }
        check(manifest.version == specification.version && manifest.abi == specification.abi) {
            "Runtime package manifest does not match its specification"
        }
        val manifestFiles = manifest.files.map { PackageFile(it.path, it.sha256) }
        if (specification.expectedFiles.isNotEmpty()) {
            check(specification.expectedFiles.toSet() == manifestFiles.toSet()) {
                "Runtime package file manifest changed"
            }
        }
        val mainFile = manifestFiles.first {
            it.path == when (specification.runtimePackage) {
                LocalRuntimePackage.GGUF -> GGUF_LIBRARY_PATH
                LocalRuntimePackage.LITERT_LM -> LITERT_LIBRARY_PATH
            }
        }
        specification.expectedLibrarySha256?.let { expected ->
            check(expected.equals(mainFile.sha256, ignoreCase = true)) {
                "Runtime library checksum does not match the manifest"
            }
        }

        // LiteRT 0.16.1 is immutable. A valid existing package is kept in place so a re-download
        // cannot unlink a .so that another thread has already loaded or memory-mapped.
        if (specification.runtimePackage == LocalRuntimePackage.LITERT_LM &&
            LocalRuntimePackage.LITERT_LM.installedDirectory(context) != null
        ) {
            return
        }

        val packageRoot = specification.runtimePackage.root(context)
        val staging = createStagingDirectory(packageRoot, specification)
        try {
            extractManifestArchive(archive, staging, manifestFiles)
            val library = File(staging, mainFile.path)
            check(library.isFile) { "${specification.runtimePackage.displayName} runtime library is missing" }
            check(sha256(library).equals(mainFile.sha256, ignoreCase = true)) {
                "Runtime library integrity check failed"
            }
            activate(
                packageRoot = packageRoot,
                staging = staging,
                specification = specification,
                files = manifestFiles,
            )
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    private fun extractManifestArchive(
        archive: File,
        staging: File,
        expectedFiles: List<PackageFile>,
    ) {
        val expectedByPath = expectedFiles.associateBy(PackageFile::path)
        val seenFiles = HashSet<String>(expectedByPath.size)
        val seenEntries = HashSet<String>()
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val entryPath = entry.name.trimEnd('/')
                check(entryPath.isNotEmpty() && LocalRuntimePackage.isSafeRelativePath(entryPath)) {
                    "Unsafe runtime archive entry"
                }
                check(seenEntries.add(entryPath)) { "Runtime archive contains duplicate entries" }
                val destination = File(staging, entryPath)
                check(LocalRuntimePackage.isWithin(staging, destination)) { "Unsafe runtime archive entry" }
                if (entry.isDirectory) {
                    check(destination.mkdirs() || destination.isDirectory) { "Unable to unpack runtime" }
                } else {
                    check(entryPath in METADATA_PATHS || expectedByPath.containsKey(entryPath)) {
                        "Runtime archive contains an unlisted file"
                    }
                    destination.parentFile?.let { check(it.mkdirs() || it.isDirectory) }
                    destination.outputStream().use(zip::copyTo)
                    if (entryPath in expectedByPath) {
                        val expected = expectedByPath.getValue(entryPath)
                        check(seenFiles.add(entryPath)) { "Runtime archive contains duplicate files" }
                        check(sha256(destination).equals(expected.sha256, ignoreCase = true)) {
                            "Runtime file checksum failed: $entryPath"
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        check(seenFiles == expectedByPath.keys) { "Runtime package is missing a declared file" }
        check(File(staging, RUNTIME_MANIFEST_NAME).isFile) { "Runtime package is incomplete" }
    }

    private fun installLiteRtAar(archive: File, specification: VerifiedPackage) {
        check(specification.version == LocalRuntimePackage.LITERT_VERSION) {
            "Unsupported LiteRT-LM runtime version"
        }
        check(sha256(archive).equals(LocalRuntimePackage.LITERT_AAR_SHA256, ignoreCase = true)) {
            "LiteRT-LM AAR integrity check failed"
        }
        val nativeEntries = readLiteRtNativeEntries(archive)
        val selectedAbi = specification.abi
        check(nativeEntries.any { it.abi == selectedAbi }) {
            "LiteRT-LM AAR does not provide ABI $selectedAbi"
        }
        val selectedEntries = nativeEntries.filter { it.abi == selectedAbi }
        check(selectedEntries.any { it.libraryPath == "liblitertlm_jni.so" }) {
            "LiteRT-LM JNI library is missing"
        }

        if (LocalRuntimePackage.LITERT_LM.installedDirectory(context) != null) return

        val packageRoot = LocalRuntimePackage.LITERT_LM.root(context)
        val staging = createStagingDirectory(packageRoot, specification)
        try {
            val files = mutableListOf<PackageFile>()
            ZipFile(archive).use { zip ->
                for (nativeEntry in selectedEntries) {
                    val relativePath = "lib/${nativeEntry.libraryPath}"
                    check(LocalRuntimePackage.isSafeRelativePath(relativePath)) {
                        "Unsafe LiteRT-LM library path"
                    }
                    check(files.none { it.path == relativePath }) {
                        "LiteRT-LM AAR contains duplicate library names"
                    }
                    val destination = File(staging, relativePath)
                    check(LocalRuntimePackage.isWithin(staging, destination)) {
                        "Unsafe LiteRT-LM library path"
                    }
                    destination.parentFile?.mkdirs()
                    val entry = zip.getEntry(nativeEntry.zipPath)
                        ?: error("LiteRT-LM native library is missing")
                    zip.getInputStream(entry).use { input -> destination.outputStream().use(input::copyTo) }
                    val checksum = sha256(destination)
                    files += PackageFile(relativePath, checksum)
                }
                // Keep the notices next to the native package for users who need to inspect the
                // third-party licenses. They are not native payload and are intentionally absent
                // from the executable file checksum list.
                copyOptionalEntry(zip, "LICENSE", staging)
                copyOptionalEntry(zip, "THIRD_PARTY_NOTICE.txt", staging)
            }
            val library = File(staging, LITERT_LIBRARY_PATH)
            check(library.isFile) { "LiteRT-LM JNI library is missing" }
            activate(
                packageRoot = packageRoot,
                staging = staging,
                specification = specification,
                files = files,
            )
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    private fun copyOptionalEntry(zip: ZipFile, path: String, staging: File) {
        val entry = zip.getEntry(path) ?: return
        val destination = File(staging, path)
        destination.parentFile?.mkdirs()
        zip.getInputStream(entry).use { input -> destination.outputStream().use(input::copyTo) }
    }

    private fun activate(
        packageRoot: File,
        staging: File,
        specification: VerifiedPackage,
        files: List<PackageFile>,
    ) {
        val destination = File(packageRoot, specification.version)
        check(LocalRuntimePackage.isWithin(packageRoot, destination)) { "Invalid runtime destination" }
        if (specification.runtimePackage == LocalRuntimePackage.LITERT_LM && destination.exists()) {
            // Never overwrite an existing mapped LiteRT library, even if its marker was lost.
            check(files.all { file ->
                val existing = File(destination, file.path)
                existing.isFile && sha256(existing).equals(file.sha256, ignoreCase = true)
            }) { "An incompatible LiteRT-LM installation already exists. Restart the app and clear its runtime files." }
            staging.deleteRecursively()
        } else {
            destination.deleteRecursively()
            check(staging.renameTo(destination)) { "Unable to activate runtime" }
        }
        check(destination.isDirectory) { "Unable to activate runtime" }

        val marker = buildJsonObject {
            put("version", specification.version)
            put("abi", specification.abi)
            put("engine", specification.runtimePackage.engine)
            if (specification.runtimePackage == LocalRuntimePackage.LITERT_LM || files.isNotEmpty()) {
                put("files", buildJsonArray {
                    files.forEach { file ->
                        add(buildJsonObject {
                            put("path", file.path)
                            put("sha256", file.sha256.lowercase())
                        })
                    }
                })
            }
        }
        val markerFile = File(packageRoot, "installed.json")
        val temporaryMarker = File(packageRoot, ".installed-${specification.version}.json")
        temporaryMarker.writeText(JsonInstant.encodeToString(JsonElement.serializer(), marker))
        check(temporaryMarker.renameTo(markerFile)) { "Unable to write runtime marker" }
    }

    private fun createStagingDirectory(packageRoot: File, specification: VerifiedPackage): File {
        check(packageRoot.mkdirs() || packageRoot.isDirectory) { "Unable to prepare runtime installation" }
        val staging = File(
            packageRoot,
            ".staging-${specification.runtimePackage.name.lowercase()}-${specification.version}",
        )
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Unable to prepare runtime installation" }
        return staging
    }

    private fun readManifest(archive: File): RuntimeArchiveManifest = ZipFile(archive).use { zip ->
        val entry = zip.getEntry(RUNTIME_MANIFEST_NAME)
            ?: error("Runtime package is missing runtime.json")
        check(!entry.isDirectory && entry.size in 1..MAX_MANIFEST_BYTES) {
            "Runtime package manifest is invalid"
        }
        val root = zip.getInputStream(entry).bufferedReader().use { it.readText() }
        parseRuntimeManifest(root)
    }

    private fun hasRuntimeManifest(archive: File): Boolean = ZipFile(archive).use { zip ->
        zip.getEntry(RUNTIME_MANIFEST_NAME)?.isDirectory == false
    }

    private fun selectLiteRtAbi(archive: File): String {
        val entries = readLiteRtNativeEntries(archive)
        val available = entries.mapTo(LinkedHashSet(), LiteRtNativeEntry::abi)
        return Build.SUPPORTED_ABIS.firstOrNull { it in available }
            ?: error("LiteRT-LM AAR has no native library for supported ABIs (available: $available)")
    }

    private fun readLiteRtNativeEntries(archive: File): List<LiteRtNativeEntry> = ZipFile(archive).use { zip ->
        val entries = mutableListOf<LiteRtNativeEntry>()
        val seen = HashSet<String>()
        val enumeration = zip.entries()
        while (enumeration.hasMoreElements()) {
            val entry = enumeration.nextElement()
            if (entry.isDirectory || !entry.name.startsWith("jni/")) continue
            val path = entry.name.removePrefix("jni/")
            val separator = path.indexOf('/')
            if (separator <= 0 || separator == path.lastIndex) continue
            val abi = path.substring(0, separator)
            val libraryPath = path.substring(separator + 1)
            if (!libraryPath.endsWith(".so") || !LocalRuntimePackage.isSafeRelativePath(libraryPath)) continue
            check(seen.add(entry.name)) { "LiteRT-LM AAR contains duplicate native entries" }
            entries += LiteRtNativeEntry(abi, entry.name, libraryPath)
        }
        check(entries.isNotEmpty()) { "LiteRT-LM AAR contains no native libraries" }
        entries
    }

    private data class LiteRtNativeEntry(
        val abi: String,
        val zipPath: String,
        val libraryPath: String,
    )

    private fun RuntimeArchiveManifest.toSpecification(archiveSha256: String): VerifiedPackage {
        val mainPath = when (runtimePackage) {
            LocalRuntimePackage.GGUF -> GGUF_LIBRARY_PATH
            LocalRuntimePackage.LITERT_LM -> LITERT_LIBRARY_PATH
        }
        return VerifiedPackage(
            version = version,
            abi = abi,
            expectedSha256 = archiveSha256,
            expectedLibrarySha256 = files.first { it.path == mainPath }.sha256,
            runtimePackage = runtimePackage,
            expectedFiles = files.map { PackageFile(it.path, it.sha256) },
        )
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

    private companion object {
        const val MAX_MANIFEST_BYTES = 64 * 1024L
        val installationLock = Mutex()
        val METADATA_PATHS = setOf(RUNTIME_MANIFEST_NAME, "META-INF/MANIFEST.MF", "LICENSE", "THIRD_PARTY_NOTICE.txt")
    }
}
