package me.rerere.rikkahub.utils

import android.app.DownloadManager
import android.content.Context
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.net.toUri
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.common.http.await
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import okhttp3.OkHttpClient
import okhttp3.Request

private const val GITHUB_API_URL = "https://api.github.com/repos/54xzh/LastChat/releases"
private const val CLOUDFLARE_FALLBACK_URL = "https://update-cache.54xzh.com/api/releases"

enum class UpdateSource {
    GITHUB,
    CLOUDFLARE
}

class UpdateChecker(private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    fun checkUpdate(): Flow<UiState<UpdateInfo>> = flow {
        emit(UiState.Loading)
        val updateInfo = try {
            fetchFromGitHub()
        } catch (e: CancellationException) {
            throw e
        } catch (e: GitHubUnavailableException) {
            fetchFromCloudflare()
        } catch (e: IOException) {
            fetchFromCloudflare()
        } catch (e: Exception) {
            throw e
        }
        emit(UiState.Success(data = updateInfo))
    }.catch {
        emit(UiState.Error(it))
    }.flowOn(Dispatchers.IO)

    fun checkUpdate(source: UpdateSource): Flow<UiState<UpdateInfo>> = flow {
        emit(UiState.Loading)
        val updateInfo = when (source) {
            UpdateSource.GITHUB -> fetchFromGitHub()
            UpdateSource.CLOUDFLARE -> fetchFromCloudflare()
        }
        emit(UiState.Success(data = updateInfo))
    }.catch {
        emit(UiState.Error(it))
    }.flowOn(Dispatchers.IO)

    private suspend fun fetchFromGitHub(): UpdateInfo {
        val response = client.newCall(
            Request.Builder()
                .url(GITHUB_API_URL)
                .get()
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader(
                    "User-Agent",
                    "LastChat ${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}"
                )
                .build()
        ).await()

        if (!response.isSuccessful) {
            throw GitHubUnavailableException("GitHub API returned ${response.code}")
        }

        val responseBody = response.body?.string()
            ?: throw GitHubUnavailableException("Empty response body")
        val releases = json.decodeFromString<List<GitHubRelease>>(responseBody)
        val release = releases.firstOrNull { it.tag_name.contains("plus", ignoreCase = true) }
            ?: throw Exception("No plus release found")
        return release.toUpdateInfo()
    }

    private suspend fun fetchFromCloudflare(): UpdateInfo {
        val response = client.newCall(
            Request.Builder()
                .url(CLOUDFLARE_FALLBACK_URL)
                .get()
                .build()
        ).await()

        if (!response.isSuccessful) {
            throw Exception("Cloudflare fallback returned ${response.code}")
        }

        val responseBody = response.body?.string()
            ?: throw Exception("Empty response body")
        val cached = json.decodeFromString<CachedRelease>(responseBody)
        return cached.toUpdateInfo()
    }

    private fun GitHubRelease.toUpdateInfo(): UpdateInfo = buildUpdateInfo(
        tagName = tag_name,
        body = body,
        publishedAt = published_at,
        assets = assets
    )

    private fun CachedRelease.toUpdateInfo(): UpdateInfo = buildUpdateInfo(
        tagName = tag_name,
        body = body,
        publishedAt = published_at,
        assets = assets
    )

    private fun buildUpdateInfo(
        tagName: String,
        body: String?,
        publishedAt: String,
        assets: List<GitHubAsset>
    ): UpdateInfo {
        val arch = getDeviceArchitecture()
        val downloads = assets
            .filter { it.name.endsWith(".apk") }
            .map { asset ->
                UpdateDownload(
                    name = asset.name,
                    url = asset.browser_download_url,
                    size = formatFileSize(asset.size)
                )
            }

        val sortedDownloads = downloads.sortedByDescending { download ->
            when {
                download.name.contains(arch, ignoreCase = true) -> 2
                download.name.contains("universal", ignoreCase = true) -> 1
                else -> 0
            }
        }

        return UpdateInfo(
            version = tagName.removePrefix("v").removePrefix("V"),
            publishedAt = publishedAt,
            changelog = body.orEmpty(),
            downloads = sortedDownloads
        )
    }

    private fun getDeviceArchitecture(): String {
        val abis = Build.SUPPORTED_ABIS
        return when {
            abis.any { it.contains("arm64") } -> "arm64-v8a"
            abis.any { it.contains("armeabi") } -> "armeabi-v7a"
            abis.any { it.contains("x86_64") } -> "x86_64"
            abis.any { it.contains("x86") } -> "x86"
            else -> "universal"
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
            bytes >= 1_024 -> String.format("%.1f KB", bytes / 1_024.0)
            else -> "$bytes B"
        }
    }

    fun downloadUpdate(context: Context, download: UpdateDownload) {
        runCatching {
            val request = DownloadManager.Request(download.url.toUri()).apply {
                setTitle(context.getString(R.string.update_download_notification_title))
                setDescription(context.getString(R.string.update_download_notification_desc, download.name))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, download.name)
                setMimeType("application/vnd.android.package-archive")
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
        }.onFailure {
            Toast.makeText(context, context.getString(R.string.toast_failed_to_download_update), Toast.LENGTH_SHORT).show()
            context.openUrl(download.url)
        }
    }
}

@Serializable
data class GitHubRelease(
    val tag_name: String,
    val name: String,
    val body: String? = null,
    val published_at: String,
    val assets: List<GitHubAsset>
)

@Serializable
data class GitHubAsset(
    val name: String,
    val browser_download_url: String,
    val size: Long
)

@Serializable
data class CachedRelease(
    val tag_name: String,
    val name: String,
    val body: String? = null,
    val published_at: String,
    val assets: List<GitHubAsset>,
    val cached_at: String? = null
)

@Serializable
data class UpdateDownload(
    val name: String,
    val url: String,
    val size: String
)

@Serializable
data class UpdateInfo(
    val version: String,
    val publishedAt: String,
    val changelog: String,
    val downloads: List<UpdateDownload>
)

@JvmInline
value class Version(val value: String) : Comparable<Version> {

    private fun parseVersion(): List<Int> {
        val normalized = value
            .trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('+')
            .substringBefore('-')

        return normalized
            .split(".")
            .filter { it.isNotBlank() }
            .map { part ->
                part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            }
    }

    override fun compareTo(other: Version): Int {
        val thisParts = this.parseVersion()
        val otherParts = other.parseVersion()

        val maxLength = maxOf(thisParts.size, otherParts.size)

        for (i in 0 until maxLength) {
            val thisPart = if (i < thisParts.size) thisParts[i] else 0
            val otherPart = if (i < otherParts.size) otherParts[i] else 0

            when {
                thisPart > otherPart -> return 1
                thisPart < otherPart -> return -1
            }
        }

        return 0
    }

    companion object {
        fun compare(version1: String, version2: String): Int {
            return Version(version1).compareTo(Version(version2))
        }
    }
}

operator fun String.compareTo(other: Version): Int = Version(this).compareTo(other)
operator fun Version.compareTo(other: String): Int = this.compareTo(Version(other))

private class GitHubUnavailableException(message: String) : IOException(message)
