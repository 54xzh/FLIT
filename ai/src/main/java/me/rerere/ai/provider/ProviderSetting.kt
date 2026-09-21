package me.rerere.ai.provider

import androidx.compose.runtime.Composable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.uuid.Uuid

@Serializable
sealed class ProviderProxy {
    @Serializable
    @SerialName("none")
    object None : ProviderProxy()

    @Serializable
    @SerialName("http")
    data class Http(
        val address: String,
        val port: Int,
        val username: String? = null,
        val password: String? = null,
    ) : ProviderProxy()
}

/**
 * Claude 提示词缓存(cache_control)的有效期
 * FIVE_MINUTES 是 API 默认值, 不需要显式传 ttl 字段
 */
@Serializable
enum class ClaudePromptCacheTtl(val apiValue: String?) {
    @SerialName("5m")
    FIVE_MINUTES(null),

    @SerialName("1h")
    ONE_HOUR("1h")
}

@Serializable
data class BalanceOption(
    val enabled: Boolean = false, // 是否开启余额获取功能
    val apiPath: String = "/credits", // 余额获取API路径
    val resultPath: String = "data.total_usage", // 余额获取JSON路径
)

@Serializable
data class ModelQuotaGroup(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val quota: ModelQuota = ModelQuota(enabled = true),
    val modelIds: Set<Uuid> = emptySet(),
)

/** Google 类型提供商所使用的平台。 */
@Serializable
enum class GooglePlatform {
    @SerialName("gemini")
    GEMINI,

    @SerialName("agent_platform")
    AGENT_PLATFORM,
}

/** Agent Platform 的认证与请求模式。 */
@Serializable
enum class AgentPlatformMode {
    @SerialName("standard")
    STANDARD,

    @SerialName("express")
    EXPRESS,
}

@Serializable
sealed class ProviderSetting {
    abstract val id: Uuid
    abstract val enabled: Boolean
    abstract val name: String
    abstract val models: List<Model>
    abstract val quotaGroups: List<ModelQuotaGroup>
    abstract val proxy: ProviderProxy
    abstract val balanceOption: BalanceOption
    abstract val tags: List<Uuid>
    abstract val customIconUri: String?

    abstract val builtIn: Boolean
    abstract val description: @Composable() () -> Unit
    abstract val shortDescription: @Composable() () -> Unit

    abstract fun addModel(model: Model): ProviderSetting
    abstract fun editModel(model: Model): ProviderSetting
    abstract fun delModel(model: Model): ProviderSetting
    abstract fun moveMove(from: Int, to: Int): ProviderSetting
    abstract fun copyProvider(
        id: Uuid = this.id,
        enabled: Boolean = this.enabled,
        name: String = this.name,
        models: List<Model> = this.models,
        quotaGroups: List<ModelQuotaGroup> = this.quotaGroups,
        proxy: ProviderProxy = this.proxy,
        balanceOption: BalanceOption = this.balanceOption,
        tags: List<Uuid> = this.tags,
        customIconUri: String? = this.customIconUri,
        builtIn: Boolean = this.builtIn,
        description: @Composable (() -> Unit) = this.description,
        shortDescription: @Composable (() -> Unit) = this.shortDescription,
    ): ProviderSetting

    @Serializable
    @SerialName("openai")
    data class OpenAI(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "OpenAI",
        override var models: List<Model> = emptyList(),
        override var quotaGroups: List<ModelQuotaGroup> = emptyList(),
        override var proxy: ProviderProxy = ProviderProxy.None,
        override val balanceOption: BalanceOption = BalanceOption(),
        override var tags: List<Uuid> = emptyList(),
        override val customIconUri: String? = null,
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var multiKeyEnabled: Boolean = false,
        var apiKeys: List<ProviderApiKey> = emptyList(),
        var keyStrategy: ProviderKeyStrategy = ProviderKeyStrategy.RANDOM,
        var legacyApiKeyBackup: String = "",
        var baseUrl: String = "https://api.openai.com/v1",
        var chatCompletionsPath: String = "/chat/completions",
        var useResponseApi: Boolean = false,
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(
                models = models.filter { it.id != model.id },
                quotaGroups = quotaGroups.map { group ->
                    group.copy(modelIds = group.modelIds - model.id)
                }
            )
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            quotaGroups: List<ModelQuotaGroup>,
            proxy: ProviderProxy,
            balanceOption: BalanceOption,
            tags: List<Uuid>,
            customIconUri: String?,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                quotaGroups = quotaGroups,
                customIconUri = customIconUri,
                builtIn = builtIn,
                description = description,
                proxy = proxy,
                balanceOption = balanceOption,
                tags = tags,
                shortDescription = shortDescription
            )
        }
    }

    @Serializable
    @SerialName("google")
    data class Google(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Google",
        override var models: List<Model> = emptyList(),
        override var quotaGroups: List<ModelQuotaGroup> = emptyList(),
        override var proxy: ProviderProxy = ProviderProxy.None,
        override val balanceOption: BalanceOption = BalanceOption(),
        override var tags: List<Uuid> = emptyList(),
        override val customIconUri: String? = null,
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var multiKeyEnabled: Boolean = false,
        var apiKeys: List<ProviderApiKey> = emptyList(),
        var keyStrategy: ProviderKeyStrategy = ProviderKeyStrategy.RANDOM,
        var legacyApiKeyBackup: String = "",
        var baseUrl: String = "https://generativelanguage.googleapis.com/v1beta", // only for google AI
        var platform: GooglePlatform = GooglePlatform.GEMINI,
        var agentPlatformMode: AgentPlatformMode = AgentPlatformMode.STANDARD,
        var privateKey: String = "",
        var serviceAccountEmail: String = "",
        var location: String = "global",
        var projectId: String = "",
        var useInteractionsApi: Boolean = false,
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(
                models = models.filter { it.id != model.id },
                quotaGroups = quotaGroups.map { group ->
                    group.copy(modelIds = group.modelIds - model.id)
                }
            )
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            quotaGroups: List<ModelQuotaGroup>,
            proxy: ProviderProxy,
            balanceOption: BalanceOption,
            tags: List<Uuid>,
            customIconUri: String?,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                quotaGroups = quotaGroups,
                customIconUri = customIconUri,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
                proxy = proxy,
                balanceOption = balanceOption,
                tags = tags
            )
        }
    }

    @Serializable
    @SerialName("claude")
    data class Claude(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "Claude",
        override var models: List<Model> = emptyList(),
        override var quotaGroups: List<ModelQuotaGroup> = emptyList(),
        override var proxy: ProviderProxy = ProviderProxy.None,
        override val balanceOption: BalanceOption = BalanceOption(),
        override var tags: List<Uuid> = emptyList(),
        override val customIconUri: String? = null,
        @Transient override val builtIn: Boolean = false,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
        var apiKey: String = "",
        var multiKeyEnabled: Boolean = false,
        var apiKeys: List<ProviderApiKey> = emptyList(),
        var keyStrategy: ProviderKeyStrategy = ProviderKeyStrategy.RANDOM,
        var legacyApiKeyBackup: String = "",
        var baseUrl: String = "https://api.anthropic.com/v1",
        var promptCaching: Boolean = false,
        var promptCacheTtl: ClaudePromptCacheTtl = ClaudePromptCacheTtl.FIVE_MINUTES,
    ) : ProviderSetting() {
        override fun addModel(model: Model): ProviderSetting {
            return copy(models = models + model)
        }

        override fun editModel(model: Model): ProviderSetting {
            return copy(models = models.map { if (it.id == model.id) model.copy() else it })
        }

        override fun delModel(model: Model): ProviderSetting {
            return copy(
                models = models.filter { it.id != model.id },
                quotaGroups = quotaGroups.map { group ->
                    group.copy(modelIds = group.modelIds - model.id)
                }
            )
        }

        override fun moveMove(
            from: Int,
            to: Int
        ): ProviderSetting {
            return copy(models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            })
        }

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            quotaGroups: List<ModelQuotaGroup>,
            proxy: ProviderProxy,
            balanceOption: BalanceOption,
            tags: List<Uuid>,
            customIconUri: String?,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting {
            return this.copy(
                id = id,
                enabled = enabled,
                name = name,
                models = models,
                quotaGroups = quotaGroups,
                customIconUri = customIconUri,
                proxy = proxy,
                balanceOption = balanceOption,
                tags = tags,
                builtIn = builtIn,
                description = description,
                shortDescription = shortDescription,
            )
        }
    }

    /**
     * ChatGPT/Codex subscription provider.
     *
     * OAuth credentials live in the app's separate credential file instead of this settings
     * object, so this provider itself never exposes token fields to the normal settings UI.
     */
    @Serializable
    @SerialName("openai_codex")
    data class OpenAICodex(
        override var id: Uuid = Uuid.random(),
        override var enabled: Boolean = true,
        override var name: String = "OpenAI Codex",
        override var models: List<Model> = emptyList(),
        override var quotaGroups: List<ModelQuotaGroup> = emptyList(),
        override var proxy: ProviderProxy = ProviderProxy.None,
        override var tags: List<Uuid> = emptyList(),
        override val customIconUri: String? = null,
        @Transient override val builtIn: Boolean = true,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
    ) : ProviderSetting() {
        @Transient
        override val balanceOption: BalanceOption = BalanceOption()

        override fun addModel(model: Model): ProviderSetting = copy(models = models + model)

        override fun editModel(model: Model): ProviderSetting = copy(
            models = models.map { if (it.id == model.id) model.copy() else it }
        )

        override fun delModel(model: Model): ProviderSetting = copy(
            models = models.filter { it.id != model.id },
            quotaGroups = quotaGroups.map { group ->
                group.copy(modelIds = group.modelIds - model.id)
            },
        )

        override fun moveMove(from: Int, to: Int): ProviderSetting = copy(
            models = models.toMutableList().apply {
                val model = removeAt(from)
                add(to, model)
            },
        )

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            quotaGroups: List<ModelQuotaGroup>,
            proxy: ProviderProxy,
            balanceOption: BalanceOption,
            tags: List<Uuid>,
            customIconUri: String?,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting = copy(
            id = id,
            enabled = enabled,
            name = name,
            models = models,
            quotaGroups = quotaGroups,
            proxy = proxy,
            tags = tags,
            customIconUri = customIconUri,
        )
    }

    /**
     * Models stored in the application's private directory and executed by the optional
     * local runtime.  Runtime/model metadata deliberately lives outside this settings
     * object so backups never contain device-specific file paths.
     */
    @Serializable
    @SerialName("local")
    data class Local(
        override var id: Uuid = Uuid.parse("a8b35b0f-5e6a-4e7a-9b44-c3d0b38f8d11"),
        override var enabled: Boolean = true,
        override var name: String = "Local models",
        override var models: List<Model> = emptyList(),
        override var quotaGroups: List<ModelQuotaGroup> = emptyList(),
        override var proxy: ProviderProxy = ProviderProxy.None,
        override var tags: List<Uuid> = emptyList(),
        override val customIconUri: String? = null,
        @Transient override val builtIn: Boolean = true,
        @Transient override val description: @Composable (() -> Unit) = {},
        @Transient override val shortDescription: @Composable (() -> Unit) = {},
    ) : ProviderSetting() {
        @Transient override val balanceOption: BalanceOption = BalanceOption()

        override fun addModel(model: Model): ProviderSetting = copy(models = models + model)

        override fun editModel(model: Model): ProviderSetting = copy(
            models = models.map { if (it.id == model.id) model.copy() else it },
        )

        override fun delModel(model: Model): ProviderSetting = copy(
            models = models.filter { it.id != model.id },
            quotaGroups = quotaGroups.map { group -> group.copy(modelIds = group.modelIds - model.id) },
        )

        override fun moveMove(from: Int, to: Int): ProviderSetting = copy(
            models = models.toMutableList().apply { add(to, removeAt(from)) },
        )

        override fun copyProvider(
            id: Uuid,
            enabled: Boolean,
            name: String,
            models: List<Model>,
            quotaGroups: List<ModelQuotaGroup>,
            proxy: ProviderProxy,
            balanceOption: BalanceOption,
            tags: List<Uuid>,
            customIconUri: String?,
            builtIn: Boolean,
            description: @Composable (() -> Unit),
            shortDescription: @Composable (() -> Unit),
        ): ProviderSetting = copy(
            id = id,
            enabled = enabled,
            name = name,
            models = models,
            quotaGroups = quotaGroups,
            proxy = proxy,
            tags = tags,
        )
    }

    companion object {
        val Types by lazy {
            listOf(
                OpenAI::class,
                Google::class,
                Claude::class,
            )
        }
    }
}
