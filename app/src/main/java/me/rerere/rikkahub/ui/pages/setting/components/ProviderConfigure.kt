package me.rerere.rikkahub.ui.pages.setting.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.ai.provider.ClaudePromptCacheTtl
import me.rerere.ai.provider.AgentPlatformMode
import me.rerere.ai.provider.GooglePlatform
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.context.LocalToaster
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.size
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.AutoAIIconWithUrl
import me.rerere.rikkahub.ui.components.ui.ClickableIconPicker
import me.rerere.rikkahub.ui.components.ui.ProviderIcon
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.theme.AppShapes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.reflect.KClass

@Composable
fun ProviderConfigure(
    provider: ProviderSetting,
    modifier: Modifier = Modifier,
    onEdit: (provider: ProviderSetting) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
    ) {
        // 1. Enable/Disable Toggle with text
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (provider.enabled) {
                    stringResource(id = R.string.setting_provider_page_enabled)
                } else {
                    stringResource(id = R.string.setting_provider_page_disabled)
                },
                modifier = Modifier.weight(1f)
            )
            HapticSwitch(
                checked = provider.enabled,
                onCheckedChange = { enabled ->
                    val updated = when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(enabled = enabled)
                        is ProviderSetting.Google -> provider.copy(enabled = enabled)
                        is ProviderSetting.Claude -> provider.copy(enabled = enabled)
                        is ProviderSetting.OpenAICodex -> provider.copy(enabled = enabled)
                        is ProviderSetting.Local -> provider.copy(enabled = enabled)
                    }
                    onEdit(updated)
                }
            )
        }

        // 2. Type selector (for non-built-in providers)
        if (
            !provider.builtIn &&
            (provider !is ProviderSetting.Google || provider.platform != GooglePlatform.AGENT_PLATFORM)
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth()
            ) {
                ProviderSetting.Types.forEachIndexed { index, type ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ProviderSetting.Types.size
                        ),
                        label = { Text(type.simpleName ?: "") },
                        selected = provider::class == type,
                        onClick = {
                            onEdit(provider.convertTo(type))
                        }
                    )
                }
            }
        }

        // 3. Name field with icon picker
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            ClickableIconPicker(
                currentIconUri = provider.customIconUri,
                defaultContent = {
                    ProviderIcon(
                        provider = provider,
                        modifier = Modifier.size(40.dp)
                    )
                },
                onIconSelected = { uri ->
                    val updated = when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(customIconUri = uri.toString())
                        is ProviderSetting.Google -> provider.copy(customIconUri = uri.toString())
                        is ProviderSetting.Claude -> provider.copy(customIconUri = uri.toString())
                        is ProviderSetting.OpenAICodex -> provider.copy(customIconUri = uri.toString())
                        is ProviderSetting.Local -> provider
                    }
                    onEdit(updated)
                },
                onIconCleared = {
                    val updated = when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(customIconUri = null)
                        is ProviderSetting.Google -> provider.copy(customIconUri = null)
                        is ProviderSetting.Claude -> provider.copy(customIconUri = null)
                        is ProviderSetting.OpenAICodex -> provider.copy(customIconUri = null)
                        is ProviderSetting.Local -> provider
                    }
                    onEdit(updated)
                },
                iconSize = 48.dp
            )
            OutlinedTextField(
                value = provider.name,
                onValueChange = { newName ->
                    val updated = when (provider) {
                        is ProviderSetting.OpenAI -> provider.copy(name = newName)
                        is ProviderSetting.Google -> provider.copy(name = newName)
                        is ProviderSetting.Claude -> provider.copy(name = newName)
                        is ProviderSetting.OpenAICodex -> provider.copy(name = newName)
                        is ProviderSetting.Local -> provider.copy(name = newName)
                    }
                    onEdit(updated)
                },
                label = {
                    Text(stringResource(id = R.string.setting_provider_page_name))
                },
                modifier = Modifier.weight(1f),
            )
        }

        // 4. Provider-specific configuration
        when (provider) {
            is ProviderSetting.OpenAI -> {
                ProviderConfigureOpenAI(provider, onEdit)
            }

            is ProviderSetting.Google -> {
                ProviderConfigureGoogle(provider, onEdit)
            }

            is ProviderSetting.Claude -> {
                ProviderConfigureClaude(provider, onEdit)
            }

            is ProviderSetting.OpenAICodex -> Unit
            is ProviderSetting.Local -> Unit
        }
    }
}

/**
 * Convert a provider to a different type while preserving all common properties.
 */
fun ProviderSetting.convertTo(
    type: KClass<out ProviderSetting>,
): ProviderSetting {
    // If same type, return unchanged
    if (this::class == type) return this

    // Extract API key from current provider
    val apiKey = when (this) {
        is ProviderSetting.OpenAI -> this.apiKey
        is ProviderSetting.Google -> this.apiKey
        is ProviderSetting.Claude -> this.apiKey
        is ProviderSetting.OpenAICodex -> ""
        is ProviderSetting.Local -> ""
    }
    val multiKeyEnabled = when (this) {
        is ProviderSetting.OpenAI -> this.multiKeyEnabled
        is ProviderSetting.Google -> this.multiKeyEnabled
        is ProviderSetting.Claude -> this.multiKeyEnabled
        is ProviderSetting.OpenAICodex -> false
        is ProviderSetting.Local -> false
    }
    val apiKeys = when (this) {
        is ProviderSetting.OpenAI -> this.apiKeys
        is ProviderSetting.Google -> this.apiKeys
        is ProviderSetting.Claude -> this.apiKeys
        is ProviderSetting.OpenAICodex -> emptyList()
        is ProviderSetting.Local -> emptyList()
    }
    val keyStrategy = when (this) {
        is ProviderSetting.OpenAI -> this.keyStrategy
        is ProviderSetting.Google -> this.keyStrategy
        is ProviderSetting.Claude -> this.keyStrategy
        is ProviderSetting.OpenAICodex -> me.rerere.ai.provider.ProviderKeyStrategy.RANDOM
        is ProviderSetting.Local -> me.rerere.ai.provider.ProviderKeyStrategy.RANDOM
    }
    val legacyApiKeyBackup = when (this) {
        is ProviderSetting.OpenAI -> this.legacyApiKeyBackup
        is ProviderSetting.Google -> this.legacyApiKeyBackup
        is ProviderSetting.Claude -> this.legacyApiKeyBackup
        is ProviderSetting.OpenAICodex -> ""
        is ProviderSetting.Local -> ""
    }

    val baseUrl = when (this) {
        is ProviderSetting.OpenAI -> this.baseUrl
        is ProviderSetting.Google -> this.baseUrl
        is ProviderSetting.Claude -> this.baseUrl
        is ProviderSetting.OpenAICodex -> "https://chatgpt.com/backend-api/codex"
        is ProviderSetting.Local -> ""
    }

    val rewrittenBaseUrl = baseUrl.rewriteProviderBaseUrlForTarget(type)

    // Convert to target type while preserving common properties
    return when (type) {
        ProviderSetting.OpenAI::class -> ProviderSetting.OpenAI(
            id = this.id,
            enabled = this.enabled,
            name = this.name,
            models = this.models,
            proxy = this.proxy,
            balanceOption = this.balanceOption,
            tags = this.tags,
            customIconUri = this.customIconUri,
            apiKey = apiKey,
            multiKeyEnabled = multiKeyEnabled,
            apiKeys = apiKeys,
            keyStrategy = keyStrategy,
            legacyApiKeyBackup = legacyApiKeyBackup,
            baseUrl = rewrittenBaseUrl,
        )
        ProviderSetting.Google::class -> ProviderSetting.Google(
            id = this.id,
            enabled = this.enabled,
            name = this.name,
            models = this.models,
            proxy = this.proxy,
            balanceOption = this.balanceOption,
            tags = this.tags,
            customIconUri = this.customIconUri,
            apiKey = apiKey,
            multiKeyEnabled = multiKeyEnabled,
            apiKeys = apiKeys,
            keyStrategy = keyStrategy,
            legacyApiKeyBackup = legacyApiKeyBackup,
            baseUrl = rewrittenBaseUrl,
        )
        ProviderSetting.Claude::class -> ProviderSetting.Claude(
            id = this.id,
            enabled = this.enabled,
            name = this.name,
            models = this.models,
            proxy = this.proxy,
            balanceOption = this.balanceOption,
            tags = this.tags,
            customIconUri = this.customIconUri,
            apiKey = apiKey,
            multiKeyEnabled = multiKeyEnabled,
            apiKeys = apiKeys,
            keyStrategy = keyStrategy,
            legacyApiKeyBackup = legacyApiKeyBackup,
            baseUrl = rewrittenBaseUrl
        )
        else -> this // Return unchanged if unknown type
    }
}

private fun String.rewriteProviderBaseUrlForTarget(
    targetType: KClass<out ProviderSetting>
): String {
    val trimmed = trim()
    if (trimmed.isBlank()) return trimmed

    val noTrailingSlash = trimmed.removeSuffix("/")

    return when (targetType) {
        ProviderSetting.Google::class -> {
            if (noTrailingSlash.endsWith("/v1")) {
                noTrailingSlash.removeSuffix("/v1") + "/v1beta"
            } else {
                noTrailingSlash
            }
        }

        ProviderSetting.OpenAI::class,
        ProviderSetting.Claude::class -> {
            if (noTrailingSlash.endsWith("/v1beta")) {
                noTrailingSlash.removeSuffix("/v1beta") + "/v1"
            } else {
                noTrailingSlash
            }
        }

        else -> noTrailingSlash
    }
}

@Composable
private fun ColumnScope.ProviderConfigureOpenAI(
    provider: ProviderSetting.OpenAI,
    onEdit: (provider: ProviderSetting.OpenAI) -> Unit
) {
    val toaster = LocalToaster.current

    provider.description()

    var apiKeyVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = provider.apiKey,
        onValueChange = {
            onEdit(provider.copy(apiKey = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_api_key))
        },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) apiKeyVisible = false },
        enabled = !provider.multiKeyEnabled,
        maxLines = if (apiKeyVisible) 3 else 1,
        visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                Icon(
                    imageVector = if (apiKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (apiKeyVisible) "Hide" else "Show"
                )
            }
        }
    )

    ProviderMultiKeySection(provider = provider) { updated ->
        (updated as? ProviderSetting.OpenAI)?.let(onEdit)
    }

    OutlinedTextField(
        value = provider.baseUrl,
        onValueChange = {
            onEdit(provider.copy(baseUrl = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_api_base_url))
        },
        modifier = Modifier.fillMaxWidth()
    )

    if (!provider.useResponseApi) {
        OutlinedTextField(
            value = provider.chatCompletionsPath,
            onValueChange = {
                onEdit(provider.copy(chatCompletionsPath = it.trim()))
            },
            label = {
                Text(stringResource(id = R.string.setting_provider_page_api_path))
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !provider.builtIn
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(id = R.string.setting_provider_page_response_api), modifier = Modifier.weight(1f))
        val responseAPIWarning = stringResource(id = R.string.setting_provider_page_response_api_warning)
        Checkbox(
            checked = provider.useResponseApi,
            onCheckedChange = {
                onEdit(provider.copy(useResponseApi = it))

                if(it && provider.baseUrl.toHttpUrlOrNull()?.host != "api.openai.com") {
                    toaster.show(
                        message = responseAPIWarning,
                        type = ToastType.Warning
                    )
                }
            }
        )
    }
}

@Composable
private fun ColumnScope.ProviderConfigureClaude(
    provider: ProviderSetting.Claude,
    onEdit: (provider: ProviderSetting.Claude) -> Unit
) {
    provider.description()

    var apiKeyVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = provider.apiKey,
        onValueChange = {
            onEdit(provider.copy(apiKey = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_api_key))
        },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) apiKeyVisible = false },
        enabled = !provider.multiKeyEnabled,
        maxLines = 1,
        visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                Icon(
                    imageVector = if (apiKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (apiKeyVisible) "Hide" else "Show"
                )
            }
        }
    )

    ProviderMultiKeySection(provider = provider) { updated ->
        (updated as? ProviderSetting.Claude)?.let(onEdit)
    }

    OutlinedTextField(
        value = provider.baseUrl,
        onValueChange = {
            onEdit(provider.copy(baseUrl = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_api_base_url))
        },
        modifier = Modifier.fillMaxWidth()
    )

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(id = R.string.setting_provider_page_claude_prompt_caching),
            modifier = Modifier.weight(1f)
        )
        HapticSwitch(
            checked = provider.promptCaching,
            onCheckedChange = {
                onEdit(provider.copy(promptCaching = it))
            }
        )
    }

    AnimatedVisibility(
        visible = provider.promptCaching,
        enter = expandVertically(
            animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f)
        ) + fadeIn(
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)
        ),
        exit = shrinkVertically(
            animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f)
        ) + fadeOut(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(id = R.string.setting_provider_page_claude_prompt_cache_ttl))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ClaudePromptCacheTtl.entries.forEachIndexed { index, ttl ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ClaudePromptCacheTtl.entries.size
                        ),
                        label = {
                            Text(
                                when (ttl) {
                                    ClaudePromptCacheTtl.FIVE_MINUTES -> stringResource(id = R.string.setting_provider_page_claude_prompt_cache_ttl_5m)
                                    ClaudePromptCacheTtl.ONE_HOUR -> stringResource(id = R.string.setting_provider_page_claude_prompt_cache_ttl_1h)
                                }
                            )
                        },
                        selected = provider.promptCacheTtl == ttl,
                        onClick = { onEdit(provider.copy(promptCacheTtl = ttl)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ProviderConfigureGoogle(
    provider: ProviderSetting.Google,
    onEdit: (provider: ProviderSetting.Google) -> Unit
) {
    if (provider.platform == GooglePlatform.AGENT_PLATFORM) {
        ProviderConfigureAgentPlatform(provider, onEdit)
        return
    }

    val toaster = LocalToaster.current
    provider.description()

    var apiKeyVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = provider.apiKey,
        onValueChange = {
            onEdit(provider.copy(apiKey = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_api_key))
        },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) apiKeyVisible = false },
        enabled = !provider.multiKeyEnabled,
        maxLines = if (apiKeyVisible) 3 else 1,
        visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                Icon(
                    imageVector = if (apiKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (apiKeyVisible) "Hide" else "Show"
                )
            }
        }
    )

    ProviderMultiKeySection(provider = provider) { updated ->
        (updated as? ProviderSetting.Google)?.let(onEdit)
    }

    OutlinedTextField(
        value = provider.baseUrl,
        onValueChange = {
            onEdit(provider.copy(baseUrl = it.trim()))
        },
        label = {
            Text(stringResource(id = R.string.setting_provider_page_api_base_url))
        },
        modifier = Modifier.fillMaxWidth(),
        isError = !provider.baseUrl.endsWith("/v1beta"),
        supportingText = if (!provider.baseUrl.endsWith("/v1beta")) {
            {
                Text(stringResource(R.string.setting_provider_page_base_url_hint_v1beta))
            }
        } else null
    )

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(stringResource(id = R.string.setting_provider_page_interactions_api), modifier = Modifier.weight(1f))
        val interactionsAPIWarning = stringResource(id = R.string.setting_provider_page_interactions_api_warning)
        Checkbox(
            checked = provider.useInteractionsApi,
            onCheckedChange = {
                onEdit(provider.copy(useInteractionsApi = it))

                if (it && !provider.baseUrl.contains("googleapis.com")) {
                    toaster.show(
                        message = interactionsAPIWarning,
                        type = ToastType.Warning
                    )
                }
            }
        )
    }
}

@Composable
private fun ColumnScope.ProviderConfigureAgentPlatform(
    provider: ProviderSetting.Google,
    onEdit: (provider: ProviderSetting.Google) -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val haptics = rememberPremiumHaptics()
    var showServiceAccountImportSheet by remember { mutableStateOf(false) }
    var serviceAccountJsonText by remember { mutableStateOf("") }

    fun importServiceAccountJson(content: String): Boolean {
        return try {
            val serviceAccount = Json.parseToJsonElement(content) as? JsonObject
                ?: throw IllegalArgumentException()
            val importedProjectId = (serviceAccount["project_id"] as? JsonPrimitive)
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            val importedEmail = (serviceAccount["client_email"] as? JsonPrimitive)
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            val importedPrivateKey = (serviceAccount["private_key"] as? JsonPrimitive)
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }

            if (importedProjectId == null && importedEmail == null && importedPrivateKey == null) {
                throw IllegalArgumentException()
            }

            onEdit(
                provider.copy(
                    projectId = importedProjectId ?: provider.projectId,
                    serviceAccountEmail = importedEmail ?: provider.serviceAccountEmail,
                    privateKey = importedPrivateKey ?: provider.privateKey,
                )
            )
            toaster.show(
                context.getString(R.string.setting_provider_page_service_account_import_success),
                type = ToastType.Success,
            )
            true
        } catch (_: Exception) {
            toaster.show(
                context.getString(R.string.setting_provider_page_service_account_import_failed),
                type = ToastType.Error,
            )
            false
        }
    }

    val serviceAccountJsonLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult

        try {
            serviceAccountJsonText = context.contentResolver.openInputStream(uri)
                ?.bufferedReader()
                ?.use { it.readText() }
                ?: throw IllegalArgumentException()
        } catch (_: Exception) {
            toaster.show(
                context.getString(R.string.setting_provider_page_service_account_import_failed),
                type = ToastType.Error,
            )
        }
    }

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        AgentPlatformMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = AgentPlatformMode.entries.size,
                ),
                label = {
                    Text(
                        stringResource(
                            when (mode) {
                                AgentPlatformMode.STANDARD -> R.string.setting_provider_page_agent_platform_mode_standard
                                AgentPlatformMode.EXPRESS -> R.string.setting_provider_page_agent_platform_mode_express
                            }
                        )
                    )
                },
                selected = provider.agentPlatformMode == mode,
                onClick = {
                    haptics.perform(HapticPattern.Pop)
                    onEdit(
                        provider.copy(
                            agentPlatformMode = mode,
                            useInteractionsApi = if (mode == AgentPlatformMode.EXPRESS) false else provider.useInteractionsApi,
                        )
                    )
                },
            )
        }
    }

    provider.description()

    if (provider.agentPlatformMode == AgentPlatformMode.EXPRESS) {
        var apiKeyVisible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = provider.apiKey,
            onValueChange = { onEdit(provider.copy(apiKey = it.trim())) },
            label = { Text(stringResource(id = R.string.setting_provider_page_api_key)) },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.isFocused) apiKeyVisible = false },
            enabled = !provider.multiKeyEnabled,
            maxLines = if (apiKeyVisible) 3 else 1,
            visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                    Icon(
                        imageVector = if (apiKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (apiKeyVisible) "Hide" else "Show",
                    )
                }
            },
        )

        ProviderMultiKeySection(provider = provider) { updated ->
            (updated as? ProviderSetting.Google)?.let(onEdit)
        }
    } else {
        OutlinedButton(
            onClick = {
                haptics.perform(HapticPattern.Pop)
                showServiceAccountImportSheet = true
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.setting_provider_page_import_service_account_json))
        }
        OutlinedTextField(
            value = provider.serviceAccountEmail,
            onValueChange = { onEdit(provider.copy(serviceAccountEmail = it.trim())) },
            label = { Text(stringResource(id = R.string.setting_provider_page_service_account_email)) },
            modifier = Modifier.fillMaxWidth(),
        )
        var privateKeyVisible by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = provider.privateKey,
            onValueChange = { onEdit(provider.copy(privateKey = it.trim())) },
            label = { Text(stringResource(id = R.string.setting_provider_page_private_key)) },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.isFocused) privateKeyVisible = false },
            maxLines = if (privateKeyVisible) 6 else 1,
            minLines = if (privateKeyVisible) 3 else 1,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            visualTransformation = if (privateKeyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                IconButton(onClick = { privateKeyVisible = !privateKeyVisible }) {
                    Icon(
                        imageVector = if (privateKeyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (privateKeyVisible) "Hide" else "Show",
                    )
                }
            },
        )
        OutlinedTextField(
            value = provider.location,
            onValueChange = { onEdit(provider.copy(location = it.trim())) },
            label = { Text(stringResource(id = R.string.setting_provider_page_location)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = provider.projectId,
            onValueChange = { onEdit(provider.copy(projectId = it.trim())) },
            label = { Text(stringResource(id = R.string.setting_provider_page_project_id)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(id = R.string.setting_provider_page_interactions_api), modifier = Modifier.weight(1f))
            Checkbox(
                checked = provider.useInteractionsApi,
                onCheckedChange = {
                    onEdit(provider.copy(useInteractionsApi = it))
                }
            )
        }
    }

    if (showServiceAccountImportSheet) {
        ModalBottomSheet(
            onDismissRequest = { showServiceAccountImportSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            shape = AppShapes.BottomSheet,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.setting_provider_page_import_service_account_json),
                    style = MaterialTheme.typography.titleLarge,
                )
                OutlinedTextField(
                    value = serviceAccountJsonText,
                    onValueChange = { serviceAccountJsonText = it },
                    label = { Text(stringResource(R.string.setting_provider_page_service_account_json)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 260.dp),
                    minLines = 10,
                    maxLines = 14,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            haptics.perform(HapticPattern.Pop)
                            serviceAccountJsonLauncher.launch(arrayOf("application/json", "*/*"))
                        },
                        modifier = Modifier.weight(1f),
                        shape = AppShapes.ButtonPill,
                    ) {
                        Text(stringResource(R.string.setting_provider_page_import_from_file))
                    }
                    FilledTonalButton(
                        onClick = {
                            haptics.perform(HapticPattern.Pop)
                            if (importServiceAccountJson(serviceAccountJsonText)) {
                                haptics.perform(HapticPattern.Success)
                                serviceAccountJsonText = ""
                                showServiceAccountImportSheet = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = AppShapes.ButtonPill,
                    ) {
                        Text(stringResource(R.string.confirm))
                    }
                }
            }
        }
    }
}
