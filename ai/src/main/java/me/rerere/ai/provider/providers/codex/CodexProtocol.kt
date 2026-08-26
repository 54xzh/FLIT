package me.rerere.ai.provider.providers.codex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelCapabilitySource
import me.rerere.ai.provider.ProviderProxy
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.util.configureClientWithProxy
import me.rerere.ai.util.json
import me.rerere.common.http.await
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.math.roundToInt
import kotlin.io.encoding.Base64
import kotlin.time.Clock

/** Constants belonging to the upstream Codex protocol are deliberately kept in one place. */
object CodexProtocolConfig {
    const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    const val COMPATIBILITY_VERSION = "0.144.5"
    const val AUTH_BASE_URL = "https://auth.openai.com"
    const val CHATGPT_BACKEND_URL = "https://chatgpt.com/backend-api"
    const val RESPONSES_BASE_URL = "$CHATGPT_BACKEND_URL/codex"
    const val DEVICE_VERIFICATION_URL = "$AUTH_BASE_URL/codex/device"
    const val DEVICE_CALLBACK_URL = "$AUTH_BASE_URL/deviceauth/callback"
    const val ORIGINATOR = "codex_cli_rs"
    const val USER_AGENT = "$ORIGINATOR/$COMPATIBILITY_VERSION"

    const val DEVICE_TIMEOUT_MILLIS = 15 * 60 * 1_000L
    const val DEFAULT_POLL_INTERVAL_MILLIS = 5_000L
}

@Serializable
data class CodexCredential(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMillis: Long,
    val accountId: String,
    val email: String? = null,
    val userId: String? = null,
    val planType: String? = null,
)

data class CodexDeviceCode(
    internal val deviceAuthId: String,
    val userCode: String,
    val verificationUrl: String = CodexProtocolConfig.DEVICE_VERIFICATION_URL,
    val expiresAtEpochMillis: Long,
    val intervalMillis: Long,
)

internal data class CodexDeviceAuthorization(
    val authorizationCode: String,
    val codeVerifier: String,
)

data class CodexQuotaWindow(
    val label: String,
    val usedPercent: Float,
    val windowDurationSeconds: Long?,
    val resetsAtEpochSeconds: Long?,
)

data class CodexQuotaBucket(
    val id: String,
    val name: String?,
    val primary: CodexQuotaWindow?,
    val secondary: CodexQuotaWindow?,
)

data class CodexQuotaSnapshot(
    val planType: String?,
    val buckets: List<CodexQuotaBucket>,
    val creditBalance: Double?,
)

internal data class CodexModelCatalogPage(
    val models: List<Model>,
    val nextCursor: String?,
)

class CodexProtocolException(
    val statusCode: Int?,
    message: String,
) : Exception(message)

class CodexAuthRequiredException : Exception("ChatGPT login required")

interface CodexSessionProvider {
    suspend fun getCredential(providerId: kotlin.uuid.Uuid): CodexCredential?

    suspend fun requireValidCredential(
        providerSetting: ProviderSetting.OpenAICodex,
    ): CodexCredential

    suspend fun forceRefreshCredential(
        providerSetting: ProviderSetting.OpenAICodex,
        failedAccessToken: String? = null,
    ): CodexCredential
}

open class CodexProtocolClient(
    private val client: OkHttpClient,
) {
    open suspend fun requestDeviceCode(proxy: ProviderProxy): CodexDeviceCode = withContext(Dispatchers.IO) {
        val response = execute(
            request = Request.Builder()
                .url("${CodexProtocolConfig.AUTH_BASE_URL}/api/accounts/deviceauth/usercode")
                .codexProtocolHeaders("application/json")
                .post("{\"client_id\":\"${CodexProtocolConfig.CLIENT_ID}\"}"
                    .toRequestBody("application/json".toMediaType()))
                .build(),
            proxy = proxy,
        )
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val message = if (response.code == 404) {
                "Device code login is not enabled for this account or workspace"
            } else {
                parseError(body, "Device code request failed")
            }
            throw CodexProtocolException(response.code, message)
        }
        val objectBody = parseObject(body, "Invalid device code response")
        val deviceAuthId = objectBody.string("device_auth_id")
            ?: throw CodexProtocolException(null, "Device code response did not contain device_auth_id")
        val userCode = objectBody.string("user_code") ?: objectBody.string("usercode")
            ?: throw CodexProtocolException(null, "Device code response did not contain user_code")
        val intervalMillis = objectBody.long("interval")
            ?.times(1_000L)
            ?.coerceAtLeast(1_000L)
            ?: CodexProtocolConfig.DEFAULT_POLL_INTERVAL_MILLIS
        CodexDeviceCode(
            deviceAuthId = deviceAuthId,
            userCode = userCode,
            expiresAtEpochMillis = Clock.System.now().toEpochMilliseconds() +
                CodexProtocolConfig.DEVICE_TIMEOUT_MILLIS,
            intervalMillis = intervalMillis,
        )
    }

    open suspend fun pollDeviceAuthorization(
        deviceCode: CodexDeviceCode,
        proxy: ProviderProxy,
    ): CodexCredential {
        while (Clock.System.now().toEpochMilliseconds() < deviceCode.expiresAtEpochMillis) {
            val response = withContext(Dispatchers.IO) {
                execute(
                    request = Request.Builder()
                        .url("${CodexProtocolConfig.AUTH_BASE_URL}/api/accounts/deviceauth/token")
                        .codexProtocolHeaders("application/json")
                        .post(
                            json.encodeToString(
                                JsonObject(
                                    mapOf(
                                        "device_auth_id" to kotlinx.serialization.json.JsonPrimitive(deviceCode.deviceAuthId),
                                        "user_code" to kotlinx.serialization.json.JsonPrimitive(deviceCode.userCode),
                                    )
                                )
                            ).toRequestBody("application/json".toMediaType())
                        )
                        .build(),
                    proxy = proxy,
                )
            }
            val body = response.body?.string().orEmpty()
            if (response.isSuccessful) {
                val objectBody = parseObject(body, "Invalid device authorization response")
                val authorizationCode = objectBody.string("authorization_code")
                    ?: throw CodexProtocolException(null, "Device authorization did not contain authorization_code")
                val codeVerifier = objectBody.string("code_verifier")
                    ?: throw CodexProtocolException(null, "Device authorization did not contain code_verifier")
                return exchangeDeviceAuthorization(
                    authorization = CodexDeviceAuthorization(authorizationCode, codeVerifier),
                    proxy = proxy,
                )
            }
            if (response.code != 403 && response.code != 404) {
                throw CodexProtocolException(response.code, parseError(body, "Device authorization failed"))
            }
            val remaining = deviceCode.expiresAtEpochMillis - Clock.System.now().toEpochMilliseconds()
            if (remaining > 0) delay(deviceCode.intervalMillis.coerceAtMost(remaining))
        }
        throw CodexProtocolException(null, "Device authorization timed out")
    }

    private suspend fun exchangeDeviceAuthorization(
        authorization: CodexDeviceAuthorization,
        proxy: ProviderProxy,
    ): CodexCredential = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", authorization.authorizationCode)
            .add("redirect_uri", CodexProtocolConfig.DEVICE_CALLBACK_URL)
            .add("client_id", CodexProtocolConfig.CLIENT_ID)
            .add("code_verifier", authorization.codeVerifier)
            .build()
        val response = execute(
            Request.Builder()
                .url("${CodexProtocolConfig.AUTH_BASE_URL}/oauth/token")
                .codexProtocolHeaders("application/x-www-form-urlencoded")
                .post(form)
                .build(),
            proxy,
        )
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw CodexProtocolException(response.code, parseError(body, "Token exchange failed"))
        }
        parseCredential(body, previousCredential = null)
    }

    open suspend fun refreshCredential(
        credential: CodexCredential,
        proxy: ProviderProxy,
    ): CodexCredential = withContext(Dispatchers.IO) {
        val requestBody = JsonObject(
            mapOf(
                "client_id" to kotlinx.serialization.json.JsonPrimitive(CodexProtocolConfig.CLIENT_ID),
                "grant_type" to kotlinx.serialization.json.JsonPrimitive("refresh_token"),
                "refresh_token" to kotlinx.serialization.json.JsonPrimitive(credential.refreshToken),
            )
        )
        val response = execute(
            Request.Builder()
                .url("${CodexProtocolConfig.AUTH_BASE_URL}/oauth/token")
                .codexProtocolHeaders("application/json")
                .post(
                    json.encodeToString(requestBody)
                        .toRequestBody("application/json".toMediaType())
                )
                .build(),
            proxy,
        )
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw CodexProtocolException(response.code, parseError(body, "Token refresh failed"))
        }
        parseCredential(body, previousCredential = credential)
    }

    open suspend fun listModels(
        credential: CodexCredential,
        proxy: ProviderProxy,
    ): List<Model> = withContext(Dispatchers.IO) {
        val models = mutableListOf<Model>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val url = "${CodexProtocolConfig.RESPONSES_BASE_URL}/models".toHttpUrl().newBuilder()
                .addQueryParameter("client_version", CodexProtocolConfig.COMPATIBILITY_VERSION)
                .addQueryParameter("include_hidden", "false")
                .apply { cursor?.let { addQueryParameter("cursor", it) } }
                .build()
            val request = authenticatedRequest(url.toString(), credential).get().build()
            val response = execute(request, proxy)
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw CodexProtocolException(response.code, parseError(body, "Model catalog request failed"))
            }
            val page = parseModelCatalogPage(body)
            models += page.models
            cursor = page.nextCursor?.takeIf { it.isNotBlank() && seenCursors.add(it) }
        } while (cursor != null)
        models.distinctBy { it.modelId }
    }

    open suspend fun readQuota(
        credential: CodexCredential,
        proxy: ProviderProxy,
    ): CodexQuotaSnapshot = withContext(Dispatchers.IO) {
        val response = execute(
            authenticatedRequest(
                url = "${CodexProtocolConfig.CHATGPT_BACKEND_URL}/wham/usage",
                credential = credential,
            ).get().build(),
            proxy,
        )
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw CodexProtocolException(response.code, parseError(body, "Quota request failed"))
        }
        parseQuotaResponse(body)
    }

    internal fun authenticatedRequest(url: String, credential: CodexCredential): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${credential.accessToken}")
            .header("ChatGPT-Account-ID", credential.accountId)
            .header("Accept", "application/json")
            .header("originator", CodexProtocolConfig.ORIGINATOR)
            .header("version", CodexProtocolConfig.COMPATIBILITY_VERSION)
            .header("User-Agent", CodexProtocolConfig.USER_AGENT)

    private suspend fun execute(request: Request, proxy: ProviderProxy) =
        client.configureClientWithProxy(proxy).newCall(request).await()

    private fun Request.Builder.codexProtocolHeaders(contentType: String): Request.Builder =
        header("Content-Type", contentType)
            .header("originator", CodexProtocolConfig.ORIGINATOR)
            .header("version", CodexProtocolConfig.COMPATIBILITY_VERSION)
            .header("User-Agent", CodexProtocolConfig.USER_AGENT)

    internal fun parseCredential(body: String, previousCredential: CodexCredential?): CodexCredential {
        val root = parseObject(body, "Invalid token response")
        val accessToken = root.string("access_token")
            ?: throw CodexProtocolException(null, "Token response did not contain access_token")
        val refreshToken = root.string("refresh_token") ?: previousCredential?.refreshToken
            ?: throw CodexProtocolException(null, "Token response did not contain refresh_token")
        val claims = root.string("id_token")?.let(::decodeJwtClaims)
        val authClaims = claims?.get("https://api.openai.com/auth") as? JsonObject
        val accountId = authClaims?.string("chatgpt_account_id")
            ?: previousCredential?.accountId
            ?: throw CodexProtocolException(null, "ID token did not contain a ChatGPT account id")
        val expiresAt = root.long("expires_in")?.let {
            Clock.System.now().toEpochMilliseconds() + it.coerceAtLeast(0L) * 1_000L
        } ?: claims?.long("exp")?.times(1_000L)
        ?: decodeJwtClaims(accessToken)?.long("exp")?.times(1_000L)
        ?: previousCredential?.let {
            Clock.System.now().toEpochMilliseconds() + FALLBACK_REFRESH_INTERVAL_MILLIS
        }
        ?: throw CodexProtocolException(null, "Token response did not contain an expiry")
        return CodexCredential(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresAtEpochMillis = expiresAt,
            accountId = accountId,
            email = claims?.string("email") ?: previousCredential?.email,
            userId = authClaims?.string("chatgpt_user_id") ?: previousCredential?.userId,
            planType = authClaims?.string("chatgpt_plan_type") ?: previousCredential?.planType,
        )
    }

    internal fun parseModelCatalog(body: String): List<Model> {
        return parseModelCatalogPage(body).models
    }

    internal fun parseModelCatalogPage(body: String): CodexModelCatalogPage {
        val root = parseObject(body, "Invalid model catalog response")
        val rows = root["models"] as? JsonArray ?: root["data"] as? JsonArray ?: JsonArray(emptyList())
        return CodexModelCatalogPage(
            models = rows.mapNotNull(::parseModel).distinctBy { it.modelId },
            nextCursor = root.string("next_cursor") ?: root.string("nextCursor"),
        )
    }

    private fun parseModel(element: JsonElement): Model? {
        val row = element as? JsonObject ?: return null
        val visibility = row.string("visibility")?.lowercase()
        if (visibility != null && visibility != "list") return null
        val showInPicker = row.boolean("show_in_picker") ?: row.boolean("showInPicker")
        if (showInPicker == false) return null
        val supportedInApi = row.boolean("supported_in_api") ?: row.boolean("supportedInApi")
        if (supportedInApi == false) return null
        val id = row.string("slug") ?: row.string("id") ?: return null
        val name = row.string("display_name") ?: row.string("displayName") ?: id
        val reasoningLevels = row.array("supported_reasoning_levels")
            ?: row.array("supportedReasoningLevels")
        val hasReasoning = reasoningLevels?.isNotEmpty() == true
        val input = (row.array("input_modalities") ?: row.array("inputModalities"))
            ?.mapNotNull { it.asString()?.lowercase() }
            ?.mapNotNull {
                when (it) {
                    "text" -> Modality.TEXT
                    "image", "vision" -> Modality.IMAGE
                    else -> null
                }
            }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(Modality.TEXT, Modality.IMAGE)
        return Model(
            modelId = id,
            displayName = name,
            inputModalities = input,
            outputModalities = listOf(Modality.TEXT),
            abilities = buildList {
                add(ModelAbility.TOOL)
                if (hasReasoning) add(ModelAbility.REASONING)
            },
            // Codex exposes image creation as a Responses built-in tool rather than a
            // separately selectable model. Keep it available on every catalog model,
            // matching the official Codex client behavior.
            tools = setOf(BuiltInTools.CodexImageGeneration),
            capabilitySource = ModelCapabilitySource.AUTO,
        )
    }

    internal fun parseQuotaResponse(body: String): CodexQuotaSnapshot =
        parseQuota(parseObject(body, "Invalid quota response"))

    private fun parseQuota(root: JsonObject): CodexQuotaSnapshot {
        val multi = root["rate_limits_by_limit_id"] as? JsonObject
            ?: root["rateLimitsByLimitId"] as? JsonObject
        val standardBuckets = if (multi != null) {
            multi.mapNotNull { (id, value) ->
                (value as? JsonObject)?.let { parseQuotaBucket(id, it) }
            }
        } else {
            val rateLimit = root["rate_limit"] as? JsonObject ?: root["rateLimit"] as? JsonObject
            listOfNotNull(rateLimit?.let { parseQuotaBucket("codex", it) })
        }
        val additional = (root["additional_rate_limits"] as? JsonArray
            ?: root["additionalRateLimits"] as? JsonArray)
            .orEmpty()
            .mapIndexedNotNull { index, element ->
                val wrapper = element as? JsonObject ?: return@mapIndexedNotNull null
                val value = wrapper["rate_limit"] as? JsonObject
                    ?: wrapper["rateLimit"] as? JsonObject
                    ?: wrapper
                val id = wrapper.string("limit_id")
                    ?: wrapper.string("limitId")
                    ?: wrapper.string("limit_name")
                    ?: wrapper.string("limitName")
                    ?: "additional-$index"
                parseQuotaBucket(id, value).let { bucket ->
                    bucket.copy(
                        name = wrapper.string("limit_name")
                            ?: wrapper.string("limitName")
                            ?: bucket.name,
                    )
                }
            }
        val buckets = (standardBuckets + additional).distinctBy { it.id }
        val credits = root["credits"] as? JsonObject
        return CodexQuotaSnapshot(
            planType = root.string("plan_type") ?: root.string("planType"),
            buckets = buckets,
            creditBalance = credits?.double("balance"),
        )
    }

    private fun parseQuotaBucket(id: String, value: JsonObject): CodexQuotaBucket = CodexQuotaBucket(
        id = value.string("limit_id") ?: value.string("limitId") ?: id,
        name = value.string("limit_name") ?: value.string("limitName"),
        primary = parseQuotaWindow(
            value["primary_window"] as? JsonObject ?: value["primary"] as? JsonObject,
            "Primary",
        ),
        secondary = parseQuotaWindow(
            value["secondary_window"] as? JsonObject ?: value["secondary"] as? JsonObject,
            "Secondary",
        ),
    )

    private fun parseQuotaWindow(value: JsonObject?, fallbackLabel: String): CodexQuotaWindow? {
        value ?: return null
        val durationSeconds = value.long("limit_window_seconds")
            ?: value.long("windowDurationMins")?.times(60L)
        val durationHours = durationSeconds?.div(3_600.0)?.roundToInt()
        return CodexQuotaWindow(
            label = durationHours?.let { if (it >= 24 && it % 24 == 0) "${it / 24}d" else "${it}h" }
                ?: fallbackLabel,
            usedPercent = (value.double("used_percent") ?: value.double("usedPercent") ?: 0.0)
                .toFloat().coerceIn(0f, 100f),
            windowDurationSeconds = durationSeconds,
            resetsAtEpochSeconds = value.long("reset_at") ?: value.long("resetsAt"),
        )
    }

    private fun parseObject(body: String, prefix: String): JsonObject = runCatching {
        json.parseToJsonElement(body).jsonObject
    }.getOrElse {
        throw CodexProtocolException(null, "$prefix: ${it.message}")
    }

    private fun parseError(body: String, prefix: String): String {
        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val code = parsed?.string("error")
        val description = parsed?.string("error_description")
            ?: (parsed?.get("error") as? JsonObject)?.string("message")
            ?: parsed?.string("message")
        return listOfNotNull(prefix, code, description).joinToString(": ")
            .take(2_048)
    }

    private fun decodeJwtClaims(token: String): JsonObject? = runCatching {
        val payload = token.split('.').getOrNull(1) ?: return null
        val paddedPayload = payload.padEnd((payload.length + 3) / 4 * 4, '=')
        val decoded = Base64.UrlSafe.decode(paddedPayload)
        json.parseToJsonElement(decoded.decodeToString()).jsonObject
    }.getOrNull()

    private companion object {
        const val FALLBACK_REFRESH_INTERVAL_MILLIS = 8L * 24L * 60L * 60L * 1_000L
    }
}

private fun JsonObject.string(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

private fun JsonObject.long(key: String): Long? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.let { primitive ->
        primitive.longOrNull ?: primitive.contentOrNull?.toLongOrNull()
    }

private fun JsonObject.double(key: String): Double? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.doubleOrNull
        ?: (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

private fun JsonObject.boolean(key: String): Boolean? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull

private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

private fun JsonElement.asString(): String? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
