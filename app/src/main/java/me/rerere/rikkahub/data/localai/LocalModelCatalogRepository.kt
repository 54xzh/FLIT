package me.rerere.rikkahub.data.localai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import me.rerere.rikkahub.utils.JsonInstant
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
data class LocalModelCatalog(
    val schemaVersion: Int,
    val models: List<LocalModelCatalogEntry>,
)

@Serializable
data class LocalModelCatalogEntry(
    val id: String,
    val displayName: String,
    val format: String,
    val sourceUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val parameterSize: String,
    val supportsTools: Boolean = false,
) {
    fun asDownload(): CatalogModelDownload = CatalogModelDownload(
        id = id,
        displayName = displayName,
        format = LocalModelFormat.valueOf(format),
        sourceUrl = sourceUrl,
        sha256 = sha256,
        sizeBytes = sizeBytes,
        supportsTools = supportsTools,
    )
}

/** Fetches the small public catalog; model binaries never ship with the APK. */
class LocalModelCatalogRepository(
    private val client: OkHttpClient,
) {
    suspend fun fetch(): LocalModelCatalog = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(CATALOG_URL).get().build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Unable to load recommended models (${response.code})" }
            val body = response.body.string()
            val catalog = JsonInstant.decodeFromString<LocalModelCatalog>(body)
            check(catalog.schemaVersion == 1) { "This model catalog version is not supported" }
            check(catalog.models.all { entry ->
                entry.id.isNotBlank() &&
                    entry.displayName.isNotBlank() &&
                    entry.sourceUrl.startsWith("https://") &&
                    entry.sha256.matches(Regex("[0-9a-fA-F]{64}")) &&
                    entry.sizeBytes > 0L &&
                    runCatching { LocalModelFormat.valueOf(entry.format) }.isSuccess
            }) { "The model catalog contains an invalid entry" }
            catalog
        }
    }

    companion object {
        const val CATALOG_URL = "https://flit-runtime.54xzh.com/catalog.json"
    }
}
