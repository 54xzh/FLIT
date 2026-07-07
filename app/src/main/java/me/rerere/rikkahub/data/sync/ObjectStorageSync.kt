package me.rerere.rikkahub.data.sync

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.backup.BackupRemoteResult
import me.rerere.rikkahub.data.datastore.ObjectStorageConfig
import me.rerere.rikkahub.data.datastore.WebDavConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val DEFAULT_BACKUP_PREFIX = "flit_backups"
// 旧版备份存储在 lastchat_backups 路径下，列出时一并兼容
private const val LEGACY_BACKUP_PREFIX = "lastchat_backups"
private val BACKUP_FILE_PREFIXES = setOf("FLIT_backup_", "LastChat_backup_")
private fun String.isBackupFileName(): Boolean =
    endsWith(".zip") && BACKUP_FILE_PREFIXES.any { startsWith(it) }

private const val AWS_ALGORITHM = "AWS4-HMAC-SHA256"
private const val AWS_TERMINATOR = "aws4_request"
private const val AWS_SERVICE = "s3"

private const val EMPTY_SHA256_HEX =
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

private val AWS_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

private val AWS_AMZ_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

data class ObjectStorageBackupItem(
    val key: String,
    val displayName: String,
    val size: Long,
    val lastModified: Instant,
)

class ObjectStorageSync(
    private val context: Context,
    private val webdavSync: WebdavSync,
) {
    // Do not reuse the app-wide OkHttp client, to avoid leaking auth headers via logging interceptors.
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun testConnection(config: ObjectStorageConfig) = withContext(Dispatchers.IO) {
        val url = buildBucketUrl(
            config = config,
            queryParams = listOf(
                "list-type" to "2",
                "prefix" to "${DEFAULT_BACKUP_PREFIX.trimEnd('/')}/",
                "max-keys" to "1",
            ),
        )
        val request = buildSignedRequest(
            config = config,
            method = "GET",
            url = url,
            payloadSha256Hex = EMPTY_SHA256_HEX,
            body = null,
            extraHeaders = emptyMap(),
        )
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string()?.take(1024).orEmpty()
                throw Exception("Connection failed (${response.code}): ${body.ifBlank { response.message }}")
            }
        }
    }

    suspend fun backupNow(config: ObjectStorageConfig) = withContext(Dispatchers.IO) {
        val backupFile = webdavSync.prepareBackupFile(
            webDavConfig = WebDavConfig(items = config.items)
        )
        try {
            val key = "${DEFAULT_BACKUP_PREFIX.trim('/')}/${backupFile.name}"
            val url = buildObjectUrl(config = config, key = key)
            val payloadSha256Hex = sha256HexOfFile(backupFile)

            val request = buildSignedRequest(
                config = config,
                method = "PUT",
                url = url,
                payloadSha256Hex = payloadSha256Hex,
                body = backupFile.asRequestBody("application/zip".toMediaTypeOrNull()),
                extraHeaders = mapOf(
                    "Content-Type" to "application/zip",
                ),
            )

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string()?.take(2048).orEmpty()
                    throw Exception("Backup failed (${response.code}): ${body.ifBlank { response.message }}")
                }
            }
        } finally {
            runCatching { backupFile.delete() }
        }
    }

    suspend fun backupNowAuto(
        config: ObjectStorageConfig,
        subfolder: String,
    ): BackupRemoteResult = withContext(Dispatchers.IO) {
        webdavSync.cleanupStaleBackupTempFiles()
        testConnection(config)

        val backupFile = webdavSync.prepareBackupFile(
            webDavConfig = WebDavConfig(items = config.items)
        )
        try {
            val prefix = joinPath(DEFAULT_BACKUP_PREFIX, subfolder)
            val key = "${prefix.trim('/')}/${backupFile.name}"
            val url = buildObjectUrl(config = config, key = key)
            val payloadSha256Hex = sha256HexOfFile(backupFile)

            val request = buildSignedRequest(
                config = config,
                method = "PUT",
                url = url,
                payloadSha256Hex = payloadSha256Hex,
                body = backupFile.asRequestBody("application/zip".toMediaTypeOrNull()),
                extraHeaders = mapOf(
                    "Content-Type" to "application/zip",
                ),
            )

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string()?.take(2048).orEmpty()
                    throw Exception("Backup failed (${response.code}): ${body.ifBlank { response.message }}")
                }
            }

            BackupRemoteResult(
                fileName = backupFile.name,
                fileSizeBytes = backupFile.length(),
            )
        } finally {
            runCatching { backupFile.delete() }
        }
    }

    suspend fun listBackupFiles(config: ObjectStorageConfig): List<ObjectStorageBackupItem> =
        withContext(Dispatchers.IO) {
            (listBackupObjects(config, pathPrefix = DEFAULT_BACKUP_PREFIX) +
                listBackupObjects(config, pathPrefix = LEGACY_BACKUP_PREFIX))
                .sortedByDescending { it.lastModified }
        }

    suspend fun listBackupFilesAuto(
        config: ObjectStorageConfig,
        subfolder: String,
    ): List<ObjectStorageBackupItem> = withContext(Dispatchers.IO) {
        (listBackupObjects(config, pathPrefix = joinPath(DEFAULT_BACKUP_PREFIX, subfolder)) +
            listBackupObjects(config, pathPrefix = joinPath(LEGACY_BACKUP_PREFIX, subfolder)))
            .sortedByDescending { it.lastModified }
    }

    /**
     * 列出对象存储中指定路径前缀下的备份文件，合并新旧两个路径前缀的结果。
     * 旧版备份在 [LEGACY_BACKUP_PREFIX] 路径下，新版写入 [DEFAULT_BACKUP_PREFIX]。
     */
    private suspend fun listBackupObjects(
        config: ObjectStorageConfig,
        pathPrefix: String,
    ): List<ObjectStorageBackupItem> = withContext(Dispatchers.IO) {
        val url = buildBucketUrl(
            config = config,
            queryParams = listOf(
                "list-type" to "2",
                "prefix" to "${pathPrefix.trimEnd('/')}/",
            ),
        )
        val request = buildSignedRequest(
            config = config,
            method = "GET",
            url = url,
            payloadSha256Hex = EMPTY_SHA256_HEX,
            body = null,
            extraHeaders = emptyMap(),
        )

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string()?.take(2048).orEmpty()
                throw Exception("Failed to list backups (${response.code}): ${body.ifBlank { response.message }}")
            }

            val bodyStream = response.body?.byteStream()
                ?: throw Exception("Empty response body")
            parseListObjectsV2Response(bodyStream)
                .asSequence()
                .filter { it.displayName.isBackupFileName() }
                .toList()
        }
    }

    suspend fun deleteBackupFile(config: ObjectStorageConfig, item: ObjectStorageBackupItem) =
        withContext(Dispatchers.IO) {
            val url = buildObjectUrl(config = config, key = item.key)
            val request = buildSignedRequest(
                config = config,
                method = "DELETE",
                url = url,
                payloadSha256Hex = EMPTY_SHA256_HEX,
                body = null,
                extraHeaders = emptyMap(),
            )

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string()?.take(2048).orEmpty()
                    throw Exception("Delete failed (${response.code}): ${body.ifBlank { response.message }}")
                }
            }
        }

    suspend fun restoreFromObjectStorage(
        config: ObjectStorageConfig,
        item: ObjectStorageBackupItem,
    ): WebdavSync.RestoreResult = withContext(Dispatchers.IO) {
        val url = buildObjectUrl(config = config, key = item.key)
        val request = buildSignedRequest(
            config = config,
            method = "GET",
            url = url,
            payloadSha256Hex = EMPTY_SHA256_HEX,
            body = null,
            extraHeaders = emptyMap(),
        )

        val tempFile = File(context.cacheDir, "temp_restore_${System.currentTimeMillis()}.zip")
        if (tempFile.exists()) tempFile.delete()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string()?.take(2048).orEmpty()
                    throw Exception("Download failed (${response.code}): ${body.ifBlank { response.message }}")
                }

                val stream = response.body?.byteStream()
                    ?: throw Exception("Empty response body")
                FileOutputStream(tempFile).use { out ->
                    stream.use { input ->
                        input.copyTo(out)
                    }
                }
            }

            webdavSync.restoreFromLocalFile(
                file = tempFile,
                webDavConfig = WebDavConfig(items = config.items),
            )
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun buildBucketUrl(
        config: ObjectStorageConfig,
        queryParams: List<Pair<String, String>>,
    ): HttpUrl {
        val endpoint = config.endpoint.toNormalizedHttpUrl()
        return endpoint.newBuilder()
            .addPathSegment(config.bucket.trim())
            .apply {
                queryParams.forEach { (k, v) -> addQueryParameter(k, v) }
            }
            .build()
    }

    private fun buildObjectUrl(
        config: ObjectStorageConfig,
        key: String,
    ): HttpUrl {
        val endpoint = config.endpoint.toNormalizedHttpUrl()
        val safeKey = key.trimStart('/')
        return endpoint.newBuilder()
            .addPathSegment(config.bucket.trim())
            .addPathSegments(safeKey)
            .build()
    }

    private fun buildSignedRequest(
        config: ObjectStorageConfig,
        method: String,
        url: HttpUrl,
        payloadSha256Hex: String,
        body: RequestBody?,
        extraHeaders: Map<String, String>,
    ): Request {
        val normalizedRegion = config.region.trim()
        val normalizedAccessKeyId = config.accessKeyId.trim()
        val normalizedSecretAccessKey = config.secretAccessKey

        if (url.host.isBlank()) throw IllegalArgumentException("Endpoint host is empty")
        if (normalizedAccessKeyId.isBlank()) throw IllegalArgumentException("Access Key ID is empty")
        if (normalizedSecretAccessKey.isBlank()) throw IllegalArgumentException("Secret Access Key is empty")
        if (config.bucket.trim().isBlank()) throw IllegalArgumentException("Bucket is empty")
        if (normalizedRegion.isBlank()) throw IllegalArgumentException("Region is empty")

        val now = Instant.now()
        val amzDate = AWS_AMZ_DATE_FORMATTER.format(now)
        val dateStamp = AWS_DATE_FORMATTER.format(now)
        val hostHeader = buildHostHeader(url)

        // Only sign the required headers to reduce provider quirks.
        val headersToSign = linkedMapOf(
            "host" to hostHeader,
            "x-amz-content-sha256" to payloadSha256Hex,
            "x-amz-date" to amzDate,
        )

        val canonicalHeaders = headersToSign
            .entries
            .sortedBy { it.key }
            .joinToString(separator = "") { (k, v) -> "${k.lowercase()}:${v.trim()}\n" }

        val signedHeaders = headersToSign
            .keys
            .map { it.lowercase() }
            .sorted()
            .joinToString(";")

        val canonicalRequest = buildString {
            append(method.uppercase())
            append('\n')
            append(url.encodedPath.ifBlank { "/" })
            append('\n')
            append(canonicalQueryString(url))
            append('\n')
            append(canonicalHeaders)
            append('\n')
            append(signedHeaders)
            append('\n')
            append(payloadSha256Hex)
        }

        val canonicalRequestHash = sha256Hex(canonicalRequest.toByteArray(StandardCharsets.UTF_8))
        val credentialScope = "$dateStamp/$normalizedRegion/$AWS_SERVICE/$AWS_TERMINATOR"
        val stringToSign = buildString {
            append(AWS_ALGORITHM)
            append('\n')
            append(amzDate)
            append('\n')
            append(credentialScope)
            append('\n')
            append(canonicalRequestHash)
        }

        val signingKey = buildSigningKey(
            secretAccessKey = normalizedSecretAccessKey,
            dateStamp = dateStamp,
            region = normalizedRegion,
            service = AWS_SERVICE,
        )
        val signature = hmacSha256Hex(signingKey, stringToSign)

        val authorization = "$AWS_ALGORITHM Credential=$normalizedAccessKeyId/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"

        return Request.Builder()
            .url(url)
            .method(method.uppercase(), body)
            .header("x-amz-date", amzDate)
            .header("x-amz-content-sha256", payloadSha256Hex)
            .header("Authorization", authorization)
            .apply {
                extraHeaders.forEach { (k, v) -> header(k, v) }
            }
            .build()
    }
}

private fun String.toNormalizedHttpUrl(): HttpUrl {
    val raw = this.trim()
    require(raw.isNotBlank()) { "Endpoint is empty" }
    val withScheme = if (raw.contains("://")) raw else "https://$raw"
    return withScheme.trimEnd('/').toHttpUrl()
}

private fun buildHostHeader(url: HttpUrl): String {
    val defaultPort = when (url.scheme) {
        "http" -> 80
        "https" -> 443
        else -> -1
    }
    return if (url.port != defaultPort && defaultPort != -1) {
        "${url.host}:${url.port}"
    } else {
        url.host
    }
}

private fun buildSigningKey(
    secretAccessKey: String,
    dateStamp: String,
    region: String,
    service: String,
): ByteArray {
    val kSecret = ("AWS4$secretAccessKey").toByteArray(StandardCharsets.UTF_8)
    val kDate = hmacSha256(kSecret, dateStamp)
    val kRegion = hmacSha256(kDate, region)
    val kService = hmacSha256(kRegion, service)
    return hmacSha256(kService, AWS_TERMINATOR)
}

private fun sha256Hex(input: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(input)
    return bytes.toHexLower()
}

private fun sha256HexOfFile(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(file).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHexLower()
}

private fun hmacSha256(key: ByteArray, data: String): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
}

private fun hmacSha256Hex(key: ByteArray, data: String): String {
    return hmacSha256(key, data).toHexLower()
}

private fun ByteArray.toHexLower(): String {
    val out = StringBuilder(this.size * 2)
    for (b in this) {
        out.append(((b.toInt() shr 4) and 0xF).toString(16))
        out.append((b.toInt() and 0xF).toString(16))
    }
    return out.toString()
}

private fun canonicalQueryString(url: HttpUrl): String {
    val size = url.querySize
    if (size == 0) return ""

    val pairs = ArrayList<Pair<String, String>>(size)
    for (i in 0 until size) {
        val name = url.queryParameterName(i)
        val value = url.queryParameterValue(i).orEmpty()
        pairs.add(name to value)
    }

    pairs.sortWith(compareBy({ awsPercentEncode(it.first) }, { awsPercentEncode(it.second) }))
    return pairs.joinToString("&") { (name, value) ->
        "${awsPercentEncode(name)}=${awsPercentEncode(value)}"
    }
}

private fun awsPercentEncode(value: String): String {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    val out = StringBuilder(bytes.size * 3)
    for (b in bytes) {
        val c = b.toInt() and 0xff
        val isUnreserved = (c in 'A'.code..'Z'.code) ||
            (c in 'a'.code..'z'.code) ||
            (c in '0'.code..'9'.code) ||
            c == '-'.code ||
            c == '_'.code ||
            c == '.'.code ||
            c == '~'.code
        if (isUnreserved) {
            out.append(c.toChar())
        } else {
            out.append('%')
            out.append("0123456789ABCDEF"[c ushr 4])
            out.append("0123456789ABCDEF"[c and 0x0f])
        }
    }
    return out.toString()
}

private fun parseListObjectsV2Response(inputStream: java.io.InputStream): List<ObjectStorageBackupItem> {
    val items = mutableListOf<ObjectStorageBackupItem>()

    val parser = Xml.newPullParser().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        setInput(inputStream.reader(Charsets.UTF_8))
    }

    var inContents = false
    var currentKey: String? = null
    var currentLastModified: String? = null
    var currentSize: String? = null

    fun flushCurrent() {
        val key = currentKey?.trim().orEmpty()
        val lastModifiedRaw = currentLastModified?.trim().orEmpty()
        val sizeRaw = currentSize?.trim().orEmpty()
        if (key.isBlank() || lastModifiedRaw.isBlank() || sizeRaw.isBlank()) return

        val lastModified = runCatching { Instant.parse(lastModifiedRaw) }.getOrNull() ?: return
        val size = sizeRaw.toLongOrNull() ?: return
        val displayName = key.substringAfterLast('/')

        items.add(
            ObjectStorageBackupItem(
                key = key,
                displayName = displayName,
                size = size,
                lastModified = lastModified,
            )
        )
    }

    var eventType = parser.eventType
    while (eventType != XmlPullParser.END_DOCUMENT) {
        when (eventType) {
            XmlPullParser.START_TAG -> {
                when (parser.name) {
                    "Contents" -> {
                        inContents = true
                        currentKey = null
                        currentLastModified = null
                        currentSize = null
                    }

                    "Key" -> if (inContents) currentKey = parser.nextText()
                    "LastModified" -> if (inContents) currentLastModified = parser.nextText()
                    "Size" -> if (inContents) currentSize = parser.nextText()
                }
            }

            XmlPullParser.END_TAG -> {
                if (parser.name == "Contents" && inContents) {
                    flushCurrent()
                    inContents = false
                }
            }
        }
        eventType = parser.next()
    }

    return items
}

private fun joinPath(base: String, child: String): String {
    val baseTrimmed = base.trim().trim('/')
    val childTrimmed = child.trim().trim('/')
    return when {
        baseTrimmed.isBlank() -> childTrimmed
        childTrimmed.isBlank() -> baseTrimmed
        else -> "$baseTrimmed/$childTrimmed"
    }
}
