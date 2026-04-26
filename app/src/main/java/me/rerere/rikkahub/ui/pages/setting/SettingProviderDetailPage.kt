package me.rerere.rikkahub.ui.pages.setting

import me.rerere.rikkahub.ui.theme.LocalDarkMode

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FloatingToolbarDefaults.ScreenOffset
import androidx.compose.material3.FloatingToolbarDefaults.floatingToolbarVerticalNestedScroll
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MultiChoiceSegmentedButtonRow
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFilter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ViewModule
import androidx.compose.material.icons.rounded.Widgets
import me.rerere.rikkahub.ui.components.ui.ToastType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.rerere.ai.provider.BuiltInTools
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ImageGenerationMethod
import me.rerere.ai.provider.ModelQuota
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderProxy
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.QuotaResetPeriod
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.isClaudeBuiltInSearchEnabled
import me.rerere.ai.registry.ModelRegistry
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ai.ModelAbilityTag
import me.rerere.rikkahub.ui.components.ai.ModelModalityTag
import me.rerere.rikkahub.ui.components.ai.ModelSelector
import me.rerere.rikkahub.ui.components.ai.ModelTypeTag
import me.rerere.rikkahub.ui.components.ai.ProviderBalanceText
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.AutoAIIconWithUrl
import me.rerere.rikkahub.ui.components.ui.ProviderIcon
import me.rerere.rikkahub.ui.components.ui.ModelIcon
import me.rerere.rikkahub.ui.components.ui.ShareSheet
import me.rerere.rikkahub.ui.components.ui.SiliconFlowPowerByIcon
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.TagsInput
import me.rerere.rikkahub.ui.components.ui.ItemPosition
import me.rerere.rikkahub.ui.components.ui.PhysicsSwipeToDelete
import me.rerere.rikkahub.ui.components.ui.rememberShareSheetState
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.useEditState
import me.rerere.rikkahub.ui.pages.assistant.detail.CustomBodies
import me.rerere.rikkahub.ui.pages.assistant.detail.CustomHeaders
import me.rerere.rikkahub.ui.pages.setting.components.ProviderConfigure
import me.rerere.rikkahub.ui.pages.setting.components.SettingProviderBalanceOption
import me.rerere.rikkahub.service.ModelNameGenerationService
import me.rerere.rikkahub.ui.theme.extendColors
import me.rerere.rikkahub.utils.UiState
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.model.Tag as DataTag
import me.rerere.rikkahub.ui.components.ui.FormItem
import me.rerere.rikkahub.data.repository.ModelQuotaRepository
import me.rerere.rikkahub.data.repository.QuotaUsageResult
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.hooks.HapticPattern
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Slider
import java.text.NumberFormat

@Composable
fun SettingProviderDetailPage(id: Uuid, vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val modelNameGenerationService = koinInject<ModelNameGenerationService>()
    val navController = LocalNavController.current
    val provider = settings.providers.find { it.id == id } ?: return
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current

    val onEdit = { newProvider: ProviderSetting ->
        val newSettings = settings.copy(
            providers = settings.providers.map {
                if (newProvider.id == it.id) {
                    newProvider
                } else {
                    it
                }
            }
        )
        vm.updateSettings(newSettings)
    }
    val onDelete = {
        val newSettings = settings.copy(
            providers = settings.providers - provider
        )
        vm.updateSettings(newSettings)
        navController.popBackStack()
    }

    val onUpdateModelNameIfUnchanged = { modelUuid: Uuid, expectedName: String, generatedName: String ->
        vm.updateSettings { currentSettings ->
            currentSettings.copy(
                providers = currentSettings.providers.map { currentProvider ->
                    if (currentProvider.id != id) {
                        currentProvider
                    } else {
                        currentProvider.copyProvider(
                            models = currentProvider.models.map { existingModel ->
                                if (existingModel.id == modelUuid && existingModel.displayName == expectedName) {
                                    existingModel.copy(displayName = generatedName)
                                } else {
                                    existingModel
                                }
                            }
                        )
                    }
                }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    BackButton()
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ProviderIcon(provider = provider, modifier = Modifier.size(22.dp))
                        Text(text = provider.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                actions = {
                    val shareSheetState = rememberShareSheetState()
                    ShareSheet(shareSheetState)
                    
                    // Test connection button
                    ConnectionTesterButton(
                        provider = provider,
                        scope = scope
                    )
                    
                    IconButton(
                        onClick = {
                            shareSheetState.show(provider)
                        }
                    ) {
                        Icon(Icons.Rounded.Share, null)
                    }
                }
            )
        },
        bottomBar = {
            val haptics = me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics()
            // Floating tab bar overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                // Centered floating tab bar
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier.padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        // Configuration tab
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .then(
                                    if (pager.currentPage == 0) 
                                        Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                                    else Modifier.clickable {
                                        haptics.perform(me.rerere.rikkahub.ui.hooks.HapticPattern.Tick)
                                        scope.launch { pager.animateScrollToPage(0) }
                                    }
                                )
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = stringResource(R.string.setting_provider_page_configuration),
                                tint = if (pager.currentPage == 0) 
                                    MaterialTheme.colorScheme.onPrimaryContainer 
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        
                        // Models tab
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .then(
                                    if (pager.currentPage == 1) 
                                        Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                                    else Modifier.clickable {
                                        haptics.perform(me.rerere.rikkahub.ui.hooks.HapticPattern.Tick)
                                        scope.launch { pager.animateScrollToPage(1) }
                                    }
                                )
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ViewModule,
                                contentDescription = stringResource(R.string.setting_provider_page_models),
                                tint = if (pager.currentPage == 1) 
                                    MaterialTheme.colorScheme.onPrimaryContainer 
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        
                        // Proxy tab
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .then(
                                    if (pager.currentPage == 2) 
                                        Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                                    else Modifier.clickable {
                                        haptics.perform(me.rerere.rikkahub.ui.hooks.HapticPattern.Tick)
                                        scope.launch { pager.animateScrollToPage(2) }
                                    }
                                )
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Public,
                                contentDescription = stringResource(R.string.setting_provider_page_network_proxy),
                                tint = if (pager.currentPage == 2) 
                                    MaterialTheme.colorScheme.onPrimaryContainer 
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { contentPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pager,
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(contentPadding)
            ) { page ->
                when (page) {
                    0 -> {
                        SettingProviderConfigPage(
                            provider = provider,
                            providerTags = settings.providerTags,
                            onEdit = {
                                onEdit(it)
                            },
                            onUpdateTags = { providerWithNewTags, updatedTags ->
                                // Update the provider first
                                val updatedProviders = settings.providers.map {
                                    if (it.id == providerWithNewTags.id) providerWithNewTags else it
                                }
                                
                                // Auto-cleanup: Filter out tags that are no longer used by any provider
                                val usedTagIds = updatedProviders.flatMap { it.tags }.toSet()
                                val cleanedTags = updatedTags.filter { tag -> tag.id in usedTagIds }
                                
                                val newSettings = settings.copy(
                                    providers = updatedProviders,
                                    providerTags = cleanedTags
                                )
                                vm.updateSettings(newSettings)
                            },
                            contentPadding = contentPadding
                        )
                    }

                    1 -> {
                        SettingProviderModelPage(
                            provider = provider,
                            onEdit = onEdit,
                            onGenerateModelName = { modelId ->
                                modelNameGenerationService.generateModelName(settings, modelId)
                            },
                            onUpdateModelNameIfUnchanged = onUpdateModelNameIfUnchanged,
                            contentPadding = contentPadding
                        )
                    }

                    2 -> {
                        SettingProviderProxyPage(
                            provider = provider,
                            onEdit = onEdit,
                            contentPadding = contentPadding
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuotaSectionCard(
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
        colors = CardDefaults.cardColors(
            containerColor = if (LocalDarkMode.current) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (title != null || subtitle != null) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (title != null) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            content()
        }
    }
}

@Composable
private fun QuotaUsageCard(
    usage: QuotaUsageResult?,
    quota: ModelQuota,
    numberFormat: NumberFormat,
) {
    val usedTokens = usage?.usedTokens ?: 0L
    val tokenLimit = usage?.tokenLimit ?: quota.tokenLimit
    val progress = if (tokenLimit > 0) {
        (usedTokens.toFloat() / tokenLimit).coerceIn(0f, 1f)
    } else {
        0f
    }
    val color = when {
        usage?.isOverLimit == true -> MaterialTheme.colorScheme.error
        usage?.isAtReminder == true -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
        color = color.copy(alpha = 0.10f),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.quota_settings_current_usage),
                        style = MaterialTheme.typography.labelLarge,
                        color = color,
                    )
                    Text(
                        text = stringResource(
                            R.string.quota_usage_display,
                            numberFormat.format(usedTokens),
                            numberFormat.format(tokenLimit),
                        ),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Text(
                    text = "${((usage?.usagePercentage ?: 0f).coerceAtLeast(0f)).toInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = color,
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 6.dp),
                color = color,
                trackColor = color.copy(alpha = 0.22f),
            )
        }
    }
}

@Composable
private fun QuotaLimitField(
    tokenLimit: Long,
    onTokenLimitChange: (Long) -> Unit,
) {
    var text by remember(tokenLimit) {
        mutableStateOf(if (tokenLimit > 0L) tokenLimit.toString() else "")
    }

    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val cleaned = input.filter { it.isDigit() }.take(18)
            text = cleaned
            onTokenLimitChange(cleaned.toLongOrNull() ?: 0L)
        },
        label = { Text(stringResource(R.string.quota_settings_token_limit)) },
        supportingText = {
            Text(stringResource(R.string.quota_settings_token_limit_desc))
        },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        shape = me.rerere.rikkahub.ui.theme.AppShapes.CardMedium,
    )
}

@Composable
private fun QuotaReminderSlider(
    percentage: Float,
    onPercentageChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    R.string.quota_settings_reminder_percentage,
                    percentage.toInt(),
                ),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "${percentage.toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = percentage,
            onValueChange = onPercentageChange,
            valueRange = 0f..100f,
            steps = 9,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun QuotaResetPeriodPicker(
    period: QuotaResetPeriod,
    onPeriodChange: (QuotaResetPeriod) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.quota_settings_reset_period),
            style = MaterialTheme.typography.titleSmall,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            QuotaResetPeriod.entries.forEachIndexed { index, resetPeriod ->
                SegmentedButton(
                    selected = period == resetPeriod,
                    onClick = { onPeriodChange(resetPeriod) },
                    shape = SegmentedButtonDefaults.itemShape(index, QuotaResetPeriod.entries.size),
                    label = {
                        Text(text = stringResource(resetPeriod.stringRes()))
                    },
                )
            }
        }
    }
}

@Composable
private fun QuotaResetTimeFields(
    quota: ModelQuota,
    onQuotaChange: (ModelQuota) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            QuotaNumberField(
                value = quota.resetHour,
                label = stringResource(R.string.quota_settings_reset_hour),
                range = 0..23,
                modifier = Modifier.weight(1f),
                onValueChange = { onQuotaChange(quota.copy(resetHour = it)) },
            )
            QuotaNumberField(
                value = quota.resetMinute,
                label = stringResource(R.string.quota_settings_reset_minute),
                range = 0..59,
                modifier = Modifier.weight(1f),
                onValueChange = { onQuotaChange(quota.copy(resetMinute = it)) },
            )
        }

        when (quota.resetPeriod) {
            QuotaResetPeriod.DAILY -> {
                Text(
                    text = stringResource(
                        R.string.quota_settings_reset_time_desc,
                        formatQuotaTime(quota.resetHour, quota.resetMinute),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            QuotaResetPeriod.WEEKLY -> {
                Text(
                    text = stringResource(R.string.quota_settings_reset_weekday),
                    style = MaterialTheme.typography.titleSmall,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    (1..7).forEach { day ->
                        val selected = quota.resetDayOfWeek.coerceIn(1, 7) == day
                        Surface(
                            onClick = { onQuotaChange(quota.copy(resetDayOfWeek = day)) },
                            shape = RoundedCornerShape(50),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                        ) {
                            Text(
                                text = stringResource(weekdayStringRes(day)),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }

            QuotaResetPeriod.MONTHLY -> {
                QuotaNumberField(
                    value = quota.resetDayOfMonth,
                    label = stringResource(R.string.quota_settings_reset_month_day),
                    range = 1..31,
                    modifier = Modifier.fillMaxWidth(),
                    onValueChange = { onQuotaChange(quota.copy(resetDayOfMonth = it)) },
                )
                Text(
                    text = stringResource(R.string.quota_settings_reset_month_day_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun QuotaNumberField(
    value: Int,
    label: String,
    range: IntRange,
    modifier: Modifier = Modifier,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }

    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val cleaned = input.filter { it.isDigit() }.take(2)
            text = cleaned
            cleaned.toIntOrNull()?.let { onValueChange(it.coerceIn(range)) }
        },
        label = { Text(label) },
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        shape = me.rerere.rikkahub.ui.theme.AppShapes.CardMedium,
    )
}

@Composable
private fun QuotaSharedModelRow(
    model: Model,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = me.rerere.rikkahub.ui.theme.AppShapes.CardMedium,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = null,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = model.displayName.ifBlank { model.modelId },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = model.modelId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun List<Model>.upsertModel(model: Model): List<Model> {
    return if (any { it.id == model.id }) {
        map { if (it.id == model.id) model else it }
    } else {
        this + model
    }
}

private fun findQuotaGroupIds(models: List<Model>, modelId: Uuid): Set<Uuid> {
    val knownIds = models.map { it.id }.toSet()
    val relatedIds = mutableSetOf(modelId)
    var changed: Boolean
    do {
        changed = false
        models.forEach { model ->
            val sharedIds = model.quota?.sharedModelIds.orEmpty().filter { it in knownIds }
            val connected = model.id in relatedIds || sharedIds.any { it in relatedIds }
            if (connected) {
                if (relatedIds.add(model.id)) {
                    changed = true
                }
                sharedIds.forEach { sharedId ->
                    if (relatedIds.add(sharedId)) {
                        changed = true
                    }
                }
            }
        }
    } while (changed)
    return relatedIds
}

private fun syncQuotaConfig(
    models: List<Model>,
    modelId: Uuid,
    quota: ModelQuota,
): List<Model> {
    val groupIds = findQuotaGroupIds(models, modelId)
    return models.map { model ->
        if (model.id in groupIds) {
            model.copy(quota = quota.copy(sharedModelIds = groupIds - model.id))
        } else {
            model
        }
    }
}

private fun syncQuotaSharing(
    models: List<Model>,
    modelId: Uuid,
    sharedModelIds: Set<Uuid>,
): List<Model> {
    val knownIds = models.map { it.id }.toSet()
    val newGroupIds = (sharedModelIds + modelId).filter { it in knownIds }.toSet()
    val affectedIds = buildSet {
        addAll(findQuotaGroupIds(models, modelId))
        newGroupIds.forEach { id -> addAll(findQuotaGroupIds(models, id)) }
    }
    val baseQuota = models.firstOrNull { it.id == modelId }?.quota ?: ModelQuota(enabled = true)

    return models.map { model ->
        when {
            model.id in newGroupIds -> {
                model.copy(
                    quota = baseQuota.copy(
                        sharedModelIds = newGroupIds - model.id,
                    )
                )
            }

            model.id in affectedIds -> {
                val existingQuota = model.quota ?: baseQuota.copy(enabled = false)
                model.copy(
                    quota = existingQuota.copy(
                        sharedModelIds = existingQuota.sharedModelIds - newGroupIds,
                    )
                )
            }

            else -> model
        }
    }
}

private fun QuotaResetPeriod.stringRes(): Int {
    return when (this) {
        QuotaResetPeriod.DAILY -> R.string.quota_settings_reset_daily
        QuotaResetPeriod.WEEKLY -> R.string.quota_settings_reset_weekly
        QuotaResetPeriod.MONTHLY -> R.string.quota_settings_reset_monthly
    }
}

private fun weekdayStringRes(day: Int): Int {
    return when (day.coerceIn(1, 7)) {
        1 -> R.string.quota_settings_weekday_monday
        2 -> R.string.quota_settings_weekday_tuesday
        3 -> R.string.quota_settings_weekday_wednesday
        4 -> R.string.quota_settings_weekday_thursday
        5 -> R.string.quota_settings_weekday_friday
        6 -> R.string.quota_settings_weekday_saturday
        else -> R.string.quota_settings_weekday_sunday
    }
}

private fun formatQuotaTime(hour: Int, minute: Int): String {
    return "%02d:%02d".format(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
}

@Composable
private fun SettingProviderConfigPage(
    provider: ProviderSetting,
    providerTags: List<DataTag>,
    onEdit: (ProviderSetting) -> Unit,
    onUpdateTags: (ProviderSetting, List<DataTag>) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    var internalProvider by remember(provider) { mutableStateOf(provider) }
    val scope = rememberCoroutineScope()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding)
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
                colors = CardDefaults.cardColors(
                    containerColor = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                ProviderConfigure(
                    provider = internalProvider,
                    modifier = Modifier.padding(16.dp),
                    onEdit = {
                        internalProvider = it
                        // Auto-save immediately
                        onEdit(it)
                    }
                )
            }
            
            // Tags section
            Card(
                shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
                colors = CardDefaults.cardColors(
                    containerColor = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FormItem(
                        label = {
                            Text(stringResource(R.string.assistant_page_tags))
                        },
                    ) {
                        TagsInput(
                            value = internalProvider.tags,
                            tags = providerTags,
                            onValueChange = { tagIds, updatedTags ->
                                // Update internal provider with new tag IDs
                                val updatedProvider = internalProvider.copyProvider(tags = tagIds)
                                internalProvider = updatedProvider
                                // Update both provider and global tags
                                onUpdateTags(updatedProvider, updatedTags)
                            },
                        )
                    }
                }
            }

            if (internalProvider is ProviderSetting.OpenAI) {
                SettingProviderBalanceOption(
                    provider = internalProvider,
                    balanceOption = internalProvider.balanceOption,
                    onEdit = { internalProvider = internalProvider.copyProvider(balanceOption = it) }
                )
                ProviderBalanceText(providerSetting = provider, style = MaterialTheme.typography.labelSmall)
            }

            // SiliconFlow icon
            if (provider is ProviderSetting.OpenAI && provider.baseUrl.contains("siliconflow.cn")) {
                SiliconFlowPowerByIcon(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 16.dp)
                )
            }
        }
        
        // Bottom fade gradient
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(120.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        )
    }
}

@Composable
private fun SettingProviderModelPage(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
    onGenerateModelName: suspend (String) -> String?,
    onUpdateModelNameIfUnchanged: (Uuid, String, String) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    ModelList(
        providerSetting = provider,
        onUpdateProvider = onEdit,
        onGenerateModelName = onGenerateModelName,
        onUpdateModelNameIfUnchanged = onUpdateModelNameIfUnchanged,
        contentPadding = contentPadding
    )
}

@Composable
private fun SettingProviderProxyPage(
    provider: ProviderSetting,
    onEdit: (ProviderSetting) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val toaster = LocalToaster.current
    val context = LocalContext.current
    var editingProxy by remember(provider.proxy) {
        mutableStateOf(provider.proxy)
    }
    val proxyType = when (editingProxy) {
        is ProviderProxy.Http -> "HTTP"
        is ProviderProxy.None -> "None"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SingleChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth()
        ) {
            val types = listOf("None", "HTTP")
            types.forEachIndexed { index, type ->
                SegmentedButton(
                    shape = SegmentedButtonDefaults.itemShape(index, types.size),
                    label = { Text(type) },
                    selected = proxyType == type,
                    onClick = {
                        editingProxy = when (type) {
                            "HTTP" -> ProviderProxy.Http(
                                address = "",
                                port = 8080
                            )

                            else -> ProviderProxy.None
                        }
                    }
                )
            }
        }

        when (editingProxy) {
            is ProviderProxy.None -> {}
            is ProviderProxy.Http -> {
                Card(
                    shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
                    colors = CardDefaults.cardColors(
                        containerColor = if (LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = (editingProxy as ProviderProxy.Http).address,
                            onValueChange = {
                                editingProxy = (editingProxy as ProviderProxy.Http).copy(address = it)
                            },
                            label = { Text(stringResource(id = R.string.setting_provider_page_proxy_host)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        var portStr by remember { mutableStateOf((editingProxy as ProviderProxy.Http).port.toString()) }
                        OutlinedTextField(
                            value = portStr,
                            onValueChange = {
                                portStr = it
                                it.toIntOrNull()?.let { port ->
                                    editingProxy = (editingProxy as ProviderProxy.Http).copy(port = port)
                                }
                            },
                            label = { Text(stringResource(id = R.string.setting_provider_page_proxy_port)) },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = (editingProxy as ProviderProxy.Http).username ?: "",
                            onValueChange = {
                                editingProxy = (editingProxy as ProviderProxy.Http).copy(username = it)
                            },
                            label = { Text(stringResource(id = R.string.setting_provider_page_proxy_username)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = (editingProxy as ProviderProxy.Http).password ?: "",
                            onValueChange = {
                                editingProxy = (editingProxy as ProviderProxy.Http).copy(password = it)
                            },
                            label = { Text(stringResource(id = R.string.setting_provider_page_proxy_password)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Button(
                onClick = {
                    onEdit(provider.copyProvider(proxy = editingProxy))
                    toaster.show(
                        context.getString(R.string.setting_provider_page_save_success),
                        type = ToastType.Success
                    )
                }
            ) {
                Text(stringResource(id = R.string.setting_provider_page_save))
            }
        }
    }
}

@Composable
private fun ConnectionTesterButton(
    provider: ProviderSetting,
    scope: CoroutineScope
) {
    var showTestDialog by remember { mutableStateOf(false) }
    val providerManager = koinInject<ProviderManager>()
    IconButton(
        onClick = {
            showTestDialog = true
        }
    ) {
        Icon(Icons.Rounded.NetworkCheck, null)
    }
    if (showTestDialog) {
        var model by remember(provider) {
            mutableStateOf(provider.models.firstOrNull { it.type == ModelType.CHAT })
        }
        var testState: UiState<String> by remember { mutableStateOf(UiState.Idle) }
        AlertDialog(
            onDismissRequest = { showTestDialog = false },
            title = {
                Text(stringResource(R.string.setting_provider_page_test_connection))
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ModelSelector(
                        modelId = model?.id,
                        providers = listOf(provider),
                        type = ModelType.CHAT,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        model = it
                    }
                    when (testState) {
                        is UiState.Loading -> {
                            LinearWavyProgressIndicator()
                        }

                        is UiState.Success -> {
                            Text(
                                text = stringResource(R.string.setting_provider_page_test_success),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.extendColors.green6
                            )
                        }

                        is UiState.Error -> {
                            Text(
                                text = (testState as UiState.Error).error.message ?: "Error",
                                color = MaterialTheme.extendColors.red6,
                                maxLines = 10
                            )
                        }

                        else -> {}
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showTestDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {

                TextButton(
                    onClick = {
                        if (model == null) return@TextButton
                        val providerInstance = providerManager.getProviderByType(provider)
                        scope.launch {
                            runCatching {
                                testState = UiState.Loading
                                providerInstance.generateText(
                                    providerSetting = provider,
                                    messages = listOf(
                                        UIMessage.user("hello")
                                    ),
                                    params = TextGenerationParams(
                                        model = model!!,
                                    )
                                )
                                testState = UiState.Success("Success")
                            }.onFailure {
                                testState = UiState.Error(it)
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.setting_provider_page_test))
                }
            }
        )
    }
}

@Composable
private fun ModelList(
    providerSetting: ProviderSetting,
    onUpdateProvider: (ProviderSetting) -> Unit,
    onGenerateModelName: suspend (String) -> String?,
    onUpdateModelNameIfUnchanged: (Uuid, String, String) -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val providerManager = koinInject<ProviderManager>()
    val modelList by produceState(emptyList(), providerSetting) {
        runCatching {
            println("loading models...")
            value = providerManager.getProviderByType(providerSetting)
                .listModels(providerSetting)
                .sortedBy { it.modelId }
                .toList()
        }.onFailure {
            it.printStackTrace()
        }
    }
    
    // Sync icon data from fresh API response to existing saved models
    LaunchedEffect(modelList) {
        if (modelList.isEmpty()) return@LaunchedEffect
        
        var needsUpdate = false
        val updatedModels = providerSetting.models.map { savedModel ->
            // Find matching model from fresh API data
            val freshModel = modelList.find { it.modelId == savedModel.modelId }
            if (freshModel != null) {
                // Update icon data if fresh model has data that saved model lacks
                val shouldUpdateIcon = savedModel.iconUrl.isNullOrBlank() && !freshModel.iconUrl.isNullOrBlank()
                val shouldUpdateSlug = savedModel.providerSlug.isNullOrBlank() && !freshModel.providerSlug.isNullOrBlank()
                
                if (shouldUpdateIcon || shouldUpdateSlug) {
                    needsUpdate = true
                    savedModel.copy(
                        iconUrl = if (shouldUpdateIcon) freshModel.iconUrl else savedModel.iconUrl,
                        providerSlug = if (shouldUpdateSlug) freshModel.providerSlug else savedModel.providerSlug
                    )
                } else {
                    savedModel
                }
            } else {
                savedModel
            }
        }
        
        if (needsUpdate) {
            onUpdateProvider(providerSetting.copyProvider(models = updatedModels))
        }
    }
    
    var expanded by rememberSaveable { mutableStateOf(true) }
    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        onUpdateProvider(providerSetting.moveMove(from.index, to.index))
    }
    val scope = rememberCoroutineScope()
    val haptics = me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics()
    
    val canDelete = providerSetting.models.size > 1

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .floatingToolbarVerticalNestedScroll(
                    expanded = expanded,
                    onExpand = { expanded = true },
                    onCollapse = { expanded = false },
                ),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp) + PaddingValues(bottom = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            state = lazyListState
        ) {
            // 模型列表
            itemsIndexed(providerSetting.models, key = { _, item -> item.id }) { index, item ->
                val position = when {
                    providerSetting.models.size == 1 -> ItemPosition.ONLY
                    index == 0 -> ItemPosition.FIRST
                    index == providerSetting.models.lastIndex -> ItemPosition.LAST
                    else -> ItemPosition.MIDDLE
                }
                
                ReorderableItem(
                    state = reorderableLazyListState,
                    key = item.id
                ) { isDragging ->

                    androidx.compose.runtime.key(canDelete) {
                        ModelCard(
                            model = item,
                            position = position,
                            canDelete = canDelete,
                            onDelete = {
                                onUpdateProvider(providerSetting.delModel(item))
                            },
                            onEdit = { editedModel ->
                                onUpdateProvider(providerSetting.editModel(editedModel))
                            },
                            onUpdateModels = { updatedModels ->
                                onUpdateProvider(providerSetting.copyProvider(models = updatedModels))
                            },
                            onGenerateModelName = onGenerateModelName,
                            parentProvider = providerSetting,
                            dragHandle = {
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier.longPressDraggableHandle(
                                        onDragStarted = {
                                            haptics.perform(me.rerere.rikkahub.ui.hooks.HapticPattern.Pop)
                                        },
                                        onDragStopped = {
                                            haptics.perform(me.rerere.rikkahub.ui.hooks.HapticPattern.Thud)
                                        }
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.DragIndicator,
                                        contentDescription = null
                                    )
                                }
                            },
                            modifier = Modifier
                                .graphicsLayer {
                                    if (isDragging) {
                                        scaleX = 0.95f
                                        scaleY = 0.95f
                                    } else {
                                        scaleX = 1f
                                        scaleY = 1f
                                    }
                                },
                        )
                    }
                }
            }
            
            // Empty state for saved models
            if (providerSetting.models.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillParentMaxHeight(0.8f)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.setting_provider_page_no_models),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.setting_provider_page_add_models_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                }
            }
        }
        // Bottom fade gradient
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(120.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.background
                        )
                    )
                )
        )
        
        // Stacked FABs for adding models
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .offset(y = -ScreenOffset),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Model picker FAB (gray, like lorebook toggle button)
            ModelPickerFab(
                models = modelList,
                selectedModels = providerSetting.models,
                onAddModel = {
                    onUpdateProvider(providerSetting.addModel(it))
                    scope.launch {
                        val generatedName = onGenerateModelName(it.modelId)
                        if (!generatedName.isNullOrBlank()) {
                            onUpdateModelNameIfUnchanged(it.id, it.displayName, generatedName)
                        }
                    }
                },
                onRemoveModel = {
                    onUpdateProvider(providerSetting.delModel(it))
                },
                onAddModels = { models ->
                    var updated = providerSetting
                    models.forEach { model ->
                        updated = updated.addModel(model)
                    }
                    onUpdateProvider(updated)
                    models.forEach { model ->
                        scope.launch {
                            val generatedName = onGenerateModelName(model.modelId)
                            if (!generatedName.isNullOrBlank()) {
                                onUpdateModelNameIfUnchanged(model.id, model.displayName, generatedName)
                            }
                        }
                    }
                },
                onRemoveModels = { models ->
                    var updated = providerSetting
                    models.forEach { model ->
                        updated = updated.delModel(model)
                    }
                    onUpdateProvider(updated)
                },
                parentProvider = providerSetting
            )
            
            // Main FAB for add new custom model
            AddNewModelFab(
                onAddModels = { updatedModels ->
                    onUpdateProvider(providerSetting.copyProvider(models = updatedModels))
                },
                onGenerateModelName = onGenerateModelName,
                parentProvider = providerSetting
            )
        }
    }
}

@Composable
private fun ModelSettingsForm(
    model: Model,
    onModelChange: (Model) -> Unit,
    onProviderModelsChange: ((List<Model>) -> Unit)? = null,
    onGenerateModelName: suspend (String) -> String?,
    isEdit: Boolean,
    parentProvider: ProviderSetting? = null
) {
    val pagerState = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val generateModelNameFailedMessage = stringResource(R.string.setting_provider_page_generate_model_name_failed)
    var generatingDisplayName by remember(model.id, model.modelId) { mutableStateOf(false) }

    fun setModelId(id: String) {
        val inputModality = ModelRegistry.MODEL_INPUT_MODALITIES.getData(id)
        val outputModality = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(id)
        val abilities = ModelRegistry.MODEL_ABILITIES.getData(id)
        // Extract providerSlug from model ID if it contains "/" (e.g., "anthropic/claude-3.5" -> "anthropic")
        val providerSlug = if (id.contains("/")) id.substringBefore("/") else null
        onModelChange(
            model.copy(
                modelId = id,
                displayName = id.uppercase(),
                inputModalities = inputModality,
                outputModalities = outputModality,
                abilities = abilities,
                providerSlug = providerSlug
            )
        )
    }

    Column {
        SecondaryTabRow(
            selectedTabIndex = pagerState.currentPage,
            containerColor = Color.Transparent,
        ) {
            Tab(
                selected = pagerState.currentPage == 0,
                onClick = {
                    scope.launch {
                        pagerState.animateScrollToPage(0)
                    }
                },
                text = { Text(stringResource(R.string.setting_provider_page_basic_settings)) }
            )
            Tab(
                selected = pagerState.currentPage == 1,
                onClick = {
                    scope.launch {
                        pagerState.animateScrollToPage(1)
                    }
                },
                text = { Text(stringResource(R.string.setting_provider_page_advanced_settings)) }
            )
            Tab(
                selected = pagerState.currentPage == 2,
                onClick = {
                    scope.launch {
                        pagerState.animateScrollToPage(2)
                    }
                },
                text = { Text(stringResource(R.string.setting_page_built_in_tools)) }
            )
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            when (page) {
                0 -> {
                    // 基本设置页面
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        OutlinedTextField(
                            value = model.modelId,
                            onValueChange = {
                                if (!isEdit) {
                                    setModelId(it.trim())
                                }
                            },
                            label = { Text(stringResource(R.string.setting_provider_page_model_id)) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                if (!isEdit) {
                                    Text(stringResource(R.string.setting_provider_page_model_id_placeholder))
                                }
                            },
                            enabled = !isEdit
                        )

                        // Display name with icon picker
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            me.rerere.rikkahub.ui.components.ui.ClickableIconPicker(
                                currentIconUri = model.customIconUri,
                                defaultContent = {
                                    ModelIcon(
                                        model = model,
                                        provider = parentProvider,
                                        modifier = Modifier.size(40.dp)
                                    )
                                },
                                onIconSelected = { uri ->
                                    onModelChange(model.copy(customIconUri = uri.toString()))
                                },
                                onIconCleared = {
                                    onModelChange(model.copy(customIconUri = null))
                                },
                                iconSize = 48.dp
                            )
                            OutlinedTextField(
                                value = model.displayName,
                                onValueChange = {
                                    onModelChange(model.copy(displayName = it))
                                },
                                label = { Text(stringResource(if (isEdit) R.string.setting_provider_page_model_name else R.string.setting_provider_page_model_display_name)) },
                                modifier = Modifier.weight(1f),
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            val modelId = model.modelId.trim()
                                            if (modelId.isBlank() || generatingDisplayName) {
                                                return@IconButton
                                            }
                                            scope.launch {
                                                generatingDisplayName = true
                                                val generatedName = runCatching {
                                                    onGenerateModelName(modelId)
                                                }.getOrNull()
                                                generatingDisplayName = false
                                                if (!generatedName.isNullOrBlank()) {
                                                    onModelChange(model.copy(displayName = generatedName))
                                                } else {
                                                    toaster.show(
                                                        message = generateModelNameFailedMessage,
                                                        type = ToastType.Error
                                                    )
                                                }
                                            }
                                        },
                                        enabled = model.modelId.isNotBlank() && !generatingDisplayName
                                    ) {
                                        if (generatingDisplayName) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(18.dp),
                                                strokeWidth = 2.dp
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Rounded.AutoAwesome,
                                                contentDescription = stringResource(R.string.setting_provider_page_generate_model_name)
                                            )
                                        }
                                    }
                                },
                                placeholder = {
                                    if (!isEdit) {
                                        Text(stringResource(R.string.setting_provider_page_model_display_name_placeholder))
                                    }
                                }
                            )
                        }

                        ModelTypeSelector(
                            selectedType = model.type,
                            onTypeSelected = {
                                onModelChange(model.copy(type = it))
                            }
                        )

                        // Image Generation Method selector (only for IMAGE type)
                        if (model.type == ModelType.IMAGE) {
                            ImageGenerationMethodSelector(
                                selectedMethod = model.imageGenerationMethod,
                                onMethodSelected = {
                                    onModelChange(model.copy(imageGenerationMethod = it))
                                },
                                supportsImageInput = model.inputModalities.contains(Modality.IMAGE),
                                onImageInputChanged = { supportsImage ->
                                    val newInputModalities = if (supportsImage) {
                                        model.inputModalities + Modality.IMAGE
                                    } else {
                                        model.inputModalities - Modality.IMAGE
                                    }
                                    onModelChange(model.copy(inputModalities = newInputModalities))
                                }
                            )
                        }

                        ModelModalitySelector(
                            model = model,
                            inputModalities = model.inputModalities,
                            onUpdateInputModalities = {
                                onModelChange(model.copy(inputModalities = it))
                            },
                            outputModalities = model.outputModalities,
                            onUpdateOutputModalities = {
                                onModelChange(model.copy(outputModalities = it))
                            }
                        )

                        if (model.type == ModelType.CHAT) {
                            ModalAbilitySelector(
                                abilities = model.abilities,
                                onUpdateAbilities = {
                                    onModelChange(model.copy(abilities = it))
                                }
                            )
                        }
                    }
                }

                1 -> {
                    // 高级设置页面
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ProviderOverrideSettings(
                            providerOverride = model.providerOverwrite,
                            onUpdateProviderOverride = { providerOverride ->
                                onModelChange(model.copy(providerOverwrite = providerOverride))
                            },
                            parentProvider = parentProvider
                        )

                        CustomHeaders(
                            headers = model.customHeaders,
                            onUpdate = { headers ->
                                onModelChange(model.copy(customHeaders = headers))
                            }
                        )

                        CustomBodies(
                            customBodies = model.customBodies,
                            onUpdate = { bodies ->
                                onModelChange(model.copy(customBodies = bodies))
                            }
                        )

                        if (model.type == ModelType.CHAT) {
                            TokenQuotaSettings(
                                model = model,
                                onModelChange = onModelChange,
                                onProviderModelsChange = onProviderModelsChange,
                                parentProvider = parentProvider,
                            )
                        }
                    }
                }

                2 -> {
                    // 内置工具页面
                    BuiltInToolsSettings(
                        model = model,
                        parentProvider = parentProvider,
                        tools = model.tools,
                        onUpdateTools = { tools ->
                            onModelChange(model.copy(tools = tools))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AddModelButton(
    models: List<Model>,
    selectedModels: List<Model>,
    expanded: Boolean,
    onAddModel: (Model) -> Unit,
    onRemoveModel: (Model) -> Unit,
    onAddModels: (List<Model>) -> Unit,
    onRemoveModels: (List<Model>) -> Unit,
    onGenerateModelName: suspend (String) -> String?,
    parentProvider: ProviderSetting
) {
    val dialogState = useEditState<Model> { onAddModel(it) }
    val scope = rememberCoroutineScope()

    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModelPicker(
            models = models,
            selectedModels = selectedModels,
            onModelSelected = { model ->
                val inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(model.modelId)
                val outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(model.modelId)
                val abilities = ModelRegistry.MODEL_ABILITIES.getData(model.modelId)
                onAddModel(
                    model.copy(
                        inputModalities = inputModalities,
                        outputModalities = outputModalities,
                        abilities = abilities
                    )
                )
            },
            onModelDeselected = { model ->
                onRemoveModel(model)
            },
            onModelsSelected = { modelList ->
                onAddModels(modelList)
            },
            onModelsDeselected = { modelList ->
                onRemoveModels(modelList)
            },
            parentProvider = parentProvider
        )

        Button(
            onClick = {
                dialogState.open(Model())
            }
        ) {
            Row(
                modifier = Modifier,
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.setting_provider_page_add_model)
                )
                AnimatedVisibility(expanded) {
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        stringResource(R.string.setting_provider_page_add_new_model),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }

    if (dialogState.isEditing) {
        dialogState.currentState?.let { modelState ->
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = {
                    dialogState.dismiss()
                },
                sheetState = sheetState,
                sheetGesturesEnabled = false,
                dragHandle = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                sheetState.hide()
                                dialogState.dismiss()
                            }
                        }
                    ) {
                        Icon(Icons.Rounded.KeyboardArrowDown, null)
                    }
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.95f)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_add_model),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        ModelSettingsForm(
                            model = modelState,
                            onModelChange = { dialogState.currentState = it },
                            onProviderModelsChange = { updatedModels ->
                                dialogState.currentState = updatedModels.firstOrNull { it.id == modelState.id }
                                    ?: dialogState.currentState
                            },
                            onGenerateModelName = onGenerateModelName,
                            isEdit = false,
                            parentProvider = parentProvider
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(
                            onClick = {
                                dialogState.dismiss()
                            },
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = {
                                if (modelState.modelId.isNotBlank() && modelState.displayName.isNotBlank()) {
                                    dialogState.confirm()
                                }
                            },
                        ) {
                            Text(stringResource(R.string.setting_provider_page_add))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPickerFab(
    models: List<Model>,
    selectedModels: List<Model>,
    onAddModel: (Model) -> Unit,
    onRemoveModel: (Model) -> Unit,
    onAddModels: (List<Model>) -> Unit,
    onRemoveModels: (List<Model>) -> Unit,
    parentProvider: ProviderSetting
) {
    var showPicker by remember { mutableStateOf(false) }
    val haptics = me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics()
    
    FloatingActionButton(
        onClick = { 
            showPicker = true
            haptics.perform(me.rerere.rikkahub.ui.hooks.HapticPattern.Tick)
        },
        shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Icon(
            Icons.Rounded.Widgets,
            contentDescription = stringResource(R.string.setting_provider_page_add_from_list)
        )
    }
    
    if (showPicker) {
        ModalBottomSheet(
            onDismissRequest = { showPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            var filterText by remember { mutableStateOf("") }
            val filterKeywords = filterText.split(" ").filter { it.isNotBlank() }
            val filteredModels = models.fastFilter {
                if (filterKeywords.isEmpty()) {
                    true
                } else {
                    filterKeywords.all { keyword ->
                        it.modelId.contains(keyword, ignoreCase = true) ||
                            it.displayName.contains(keyword, ignoreCase = true)
                    }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(500.dp)
                    .padding(8.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Select All / Deselect All - show only one based on selection state
                val allFilteredSelected = filteredModels.isNotEmpty() && filteredModels.all { model ->
                    selectedModels.any { it.modelId == model.modelId }
                }
                
                if (allFilteredSelected) {
                    // All filtered models are selected, show Deselect All
                    TextButton(onClick = {
                        val modelsToRemove = filteredModels.mapNotNull { model ->
                            selectedModels.firstOrNull { it.modelId == model.modelId }
                        }
                        if (modelsToRemove.isNotEmpty()) {
                            onRemoveModels(modelsToRemove)
                        }
                    }) {
                        Text(stringResource(R.string.deselect_all))
                    }
                } else {
                    // Not all selected, show Select All
                    TextButton(onClick = {
                        val modelsToAdd = filteredModels.filter { model ->
                            !selectedModels.any { it.modelId == model.modelId }
                        }.map { model ->
                            val inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(model.modelId)
                            val outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(model.modelId)
                            val abilities = ModelRegistry.MODEL_ABILITIES.getData(model.modelId)
                            model.copy(
                                inputModalities = inputModalities,
                                outputModalities = outputModalities,
                                abilities = abilities
                            )
                        }
                        if (modelsToAdd.isNotEmpty()) {
                            onAddModels(modelsToAdd)
                        }
                    }) {
                        Text(stringResource(R.string.select_all))
                    }
                }
                
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (filteredModels.isEmpty()) {
                        item {
                            val hasApiKey = when (parentProvider) {
                                is ProviderSetting.OpenAI -> parentProvider.apiKey.isNotBlank()
                                is ProviderSetting.Google -> parentProvider.apiKey.isNotBlank()
                                is ProviderSetting.Claude -> parentProvider.apiKey.isNotBlank()
                            }
                            
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = stringResource(
                                        if (hasApiKey) R.string.setting_provider_page_no_models_with_api_key
                                        else R.string.setting_provider_page_no_models_no_api_key
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }
                    items(filteredModels) { model ->
                        val isSelected = selectedModels.any { it.modelId == model.modelId }
                        Card(
                            shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                            ) {
                                ModelIcon(
                                    model = model,
                                    provider = parentProvider,
                                    modifier = Modifier.size(32.dp)
                                )
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        text = model.modelId,
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        val modelMeta = remember(model) {
                                            model.copy(
                                                inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(model.modelId),
                                                outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(model.modelId),
                                                abilities = ModelRegistry.MODEL_ABILITIES.getData(model.modelId),
                                            )
                                        }
                                        ModelModalityTag(model = modelMeta)
                                        ModelAbilityTag(model = modelMeta)
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        if (isSelected) {
                                            onRemoveModel(model)
                                        } else {
                                            val inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(model.modelId)
                                            val outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(model.modelId)
                                            val abilities = ModelRegistry.MODEL_ABILITIES.getData(model.modelId)
                                            onAddModel(
                                                model.copy(
                                                    inputModalities = inputModalities,
                                                    outputModalities = outputModalities,
                                                    abilities = abilities
                                                )
                                            )
                                        }
                                    }
                                ) {
                                    if (isSelected) {
                                        Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
                                    } else {
                                        Icon(Icons.Rounded.Add, null)
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = filterText,
                    onValueChange = { filterText = it },
                    label = { Text(stringResource(R.string.setting_provider_page_filter_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.setting_provider_page_filter_example)) },
                )
            }
        }
    }
}

@Composable
private fun AddNewModelFab(
    onAddModels: (List<Model>) -> Unit,
    onGenerateModelName: suspend (String) -> String?,
    parentProvider: ProviderSetting
) {
    val dialogState = useEditState<Model> {}
    val scope = rememberCoroutineScope()
    val haptics = me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics()
    var syncedProviderModels by remember(parentProvider.models) {
        mutableStateOf<List<Model>?>(null)
    }
    
    FloatingActionButton(
        onClick = { 
            dialogState.open(Model())
            haptics.perform(me.rerere.rikkahub.ui.hooks.HapticPattern.Pop)
        },
        shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge
    ) {
        Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.setting_provider_page_add_model))
    }
    
    if (dialogState.isEditing) {
        dialogState.currentState?.let { modelState ->
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = {
                    dialogState.dismiss()
                },
                sheetState = sheetState,
                sheetGesturesEnabled = false,
                dragHandle = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                sheetState.hide()
                                dialogState.dismiss()
                            }
                        }
                    ) {
                        Icon(Icons.Rounded.KeyboardArrowDown, null)
                    }
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.95f)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_add_model),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        ModelSettingsForm(
                            model = modelState,
                            onModelChange = { dialogState.currentState = it },
                            onProviderModelsChange = { updatedModels ->
                                syncedProviderModels = updatedModels
                                dialogState.currentState = updatedModels.firstOrNull { it.id == modelState.id }
                                    ?: dialogState.currentState
                            },
                            onGenerateModelName = onGenerateModelName,
                            isEdit = false,
                            parentProvider = parentProvider
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(
                            onClick = {
                                dialogState.dismiss()
                            },
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = {
                                if (modelState.modelId.isNotBlank() && modelState.displayName.isNotBlank()) {
                                    val finalModels = syncedProviderModels
                                        ?.map { if (it.id == modelState.id) modelState else it }
                                        ?: (parentProvider.models + modelState)
                                    onAddModels(finalModels)
                                    dialogState.dismiss()
                                }
                            },
                        ) {
                            Text(stringResource(R.string.setting_provider_page_add))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelPicker(
    models: List<Model>,
    selectedModels: List<Model>,
    onModelSelected: (Model) -> Unit,
    onModelDeselected: (Model) -> Unit,
    onModelsSelected: (List<Model>) -> Unit = {},
    onModelsDeselected: (List<Model>) -> Unit = {},
    parentProvider: ProviderSetting
) {
    var showModal by remember { mutableStateOf(false) }
    if (showModal) {
        ModalBottomSheet(
            onDismissRequest = { showModal = false },
            sheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true
            )
        ) {
            var filterText by remember { mutableStateOf("") }
            val filterKeywords = filterText.split(" ").filter { it.isNotBlank() }
            val filteredModels = models.fastFilter {
                if (filterKeywords.isEmpty()) {
                    true
                } else {
                    filterKeywords.all { keyword ->
                        it.modelId.contains(keyword, ignoreCase = true) ||
                            it.displayName.contains(keyword, ignoreCase = true)
                    }
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(500.dp)
                    .padding(8.dp)
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Select All / Deselect All - show only one based on selection state
                val allFilteredSelected = filteredModels.isNotEmpty() && filteredModels.all { model ->
                    selectedModels.any { it.modelId == model.modelId }
                }
                
                if (allFilteredSelected) {
                    // All filtered models are selected, show Deselect All
                    TextButton(onClick = {
                        val modelsToRemove = filteredModels.mapNotNull { model ->
                            selectedModels.firstOrNull { it.modelId == model.modelId }
                        }
                        if (modelsToRemove.isNotEmpty()) {
                            onModelsDeselected(modelsToRemove)
                        }
                    }) {
                        Text(stringResource(R.string.deselect_all))
                    }
                } else {
                    // Not all selected, show Select All
                    TextButton(onClick = {
                        val modelsToAdd = filteredModels.filter { model ->
                            !selectedModels.any { it.modelId == model.modelId }
                        }.map { model ->
                            val inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(model.modelId)
                            val outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(model.modelId)
                            val abilities = ModelRegistry.MODEL_ABILITIES.getData(model.modelId)
                            model.copy(
                                inputModalities = inputModalities,
                                outputModalities = outputModalities,
                                abilities = abilities
                            )
                        }
                        if (modelsToAdd.isNotEmpty()) {
                            onModelsSelected(modelsToAdd)
                        }
                    }) {
                        Text(stringResource(R.string.select_all))
                    }
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clipToBounds(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(8.dp),
                ) {
                    // Empty state for API model list
                    if (models.isEmpty()) {
                        item {
                            // Check if provider has an API key
                            val hasApiKey = when (parentProvider) {
                                is ProviderSetting.OpenAI -> parentProvider.apiKey.isNotBlank()
                                is ProviderSetting.Google -> parentProvider.apiKey.isNotBlank()
                                is ProviderSetting.Claude -> parentProvider.apiKey.isNotBlank()
                            }
                            
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 48.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = stringResource(
                                        if (hasApiKey) R.string.setting_provider_page_no_models_with_api_key
                                        else R.string.setting_provider_page_no_models_no_api_key
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    }
                    items(filteredModels) {
                        Card(
                            shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
                            colors = androidx.compose.material3.CardDefaults.cardColors(
                                containerColor = if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(
                                    8.dp
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                            ) {
                                ModelIcon(
                                    model = it,
                                    provider = parentProvider,
                                    modifier = Modifier.size(32.dp)
                                )
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(
                                        4.dp
                                    ),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(
                                        text = it.modelId,
                                        style = MaterialTheme.typography.titleSmall,
                                    )

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        val modelMeta = remember(it) {
                                            it.copy(
                                                inputModalities = ModelRegistry.MODEL_INPUT_MODALITIES.getData(it.modelId),
                                                outputModalities = ModelRegistry.MODEL_OUTPUT_MODALITIES.getData(it.modelId),
                                                abilities = ModelRegistry.MODEL_ABILITIES.getData(it.modelId),
                                            )
                                        }
                                        ModelModalityTag(
                                            model = modelMeta,
                                        )
                                        ModelAbilityTag(
                                            model = modelMeta,
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        if (selectedModels.any { model -> model.modelId == it.modelId }) {
                                            // 从selectedModels中计算出要删除的model，因为删除需要id匹配，而不是ModelId
                                            onModelDeselected(selectedModels.firstOrNull { model -> model.modelId == it.modelId }
                                                ?: it)
                                        } else {
                                            onModelSelected(it)
                                        }
                                    }
                                ) {
                                    if (selectedModels.any { model -> model.modelId == it.modelId }) {
                                        Icon(Icons.Rounded.Close, null)
                                    } else {
                                        Icon(Icons.Rounded.Add, null)
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = filterText,
                    onValueChange = {
                        filterText = it
                    },
                    label = { Text(stringResource(R.string.setting_provider_page_filter_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(stringResource(R.string.setting_provider_page_filter_example))
                    },
                )
            }
        }
    }
    BadgedBox(
        badge = {
            if (models.isNotEmpty()) {
                Badge {
                    Text(models.size.toString())
                }
            }
        }
    ) {
        IconButton(
            onClick = {
                showModal = true
            }
        ) {
            Icon(Icons.Rounded.Widgets, null)
        }
    }
}

@Composable
private fun ModelTypeSelector(
    selectedType: ModelType,
    onTypeSelected: (ModelType) -> Unit
) {
    Text(
        stringResource(R.string.setting_provider_page_model_type),
        style = MaterialTheme.typography.titleSmall
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth()
    ) {
        ModelType.entries.forEachIndexed { index, type ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index, ModelType.entries.size),
                label = {
                    Text(
                        text = stringResource(
                            when (type) {
                                ModelType.CHAT -> R.string.setting_provider_page_chat_model
                                ModelType.EMBEDDING -> R.string.setting_provider_page_embedding_model
                                ModelType.IMAGE -> R.string.setting_provider_page_image_model
                            }
                        )
                    )
                },
                selected = selectedType == type,
                onClick = { onTypeSelected(type) }
            )
        }
    }
}

@Composable
private fun ImageGenerationMethodSelector(
    selectedMethod: ImageGenerationMethod?,
    onMethodSelected: (ImageGenerationMethod) -> Unit,
    supportsImageInput: Boolean = false,
    onImageInputChanged: (Boolean) -> Unit = {}
) {
    Text(
        stringResource(R.string.setting_provider_page_image_method),
        style = MaterialTheme.typography.titleSmall
    )
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth()
    ) {
        ImageGenerationMethod.entries.forEachIndexed { index, method ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index, ImageGenerationMethod.entries.size),
                label = {
                    Text(
                        text = stringResource(
                            when (method) {
                                ImageGenerationMethod.DIFFUSION -> R.string.setting_provider_page_image_method_diffusion
                                ImageGenerationMethod.MULTIMODAL -> R.string.setting_provider_page_image_method_multimodal
                            }
                        )
                    )
                },
                selected = selectedMethod == method,
                onClick = { onMethodSelected(method) }
            )
        }
    }

    // Image input toggle (for image-to-image generation)
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.setting_provider_page_image_input),
            style = MaterialTheme.typography.bodyMedium
        )
        HapticSwitch(
            checked = supportsImageInput,
            onCheckedChange = onImageInputChanged
        )
    }
}

@Composable
private fun ModelModalitySelector(
    model: Model,
    inputModalities: List<Modality>,
    onUpdateInputModalities: (List<Modality>) -> Unit,
    outputModalities: List<Modality>,
    onUpdateOutputModalities: (List<Modality>) -> Unit
) {
    if (model.type == ModelType.CHAT) {
        Text(
            stringResource(R.string.setting_provider_page_input_modality),
            style = MaterialTheme.typography.titleSmall
        )
        MultiChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Modality.entries.forEachIndexed { index, modality ->
                SegmentedButton(
                    checked = modality in inputModalities,
                    shape = SegmentedButtonDefaults.itemShape(index, Modality.entries.size),
                    onCheckedChange = {
                        if (it) {
                            onUpdateInputModalities(inputModalities + modality)
                        } else {
                            onUpdateInputModalities(inputModalities - modality)
                        }
                    }
                ) {
                    Text(
                        text = stringResource(
                            when (modality) {
                                Modality.TEXT -> R.string.setting_provider_page_text
                                Modality.IMAGE -> R.string.setting_provider_page_image
                            }
                        )
                    )
                }
            }
        }

        Text(
            stringResource(R.string.setting_provider_page_output_modality),
            style = MaterialTheme.typography.titleSmall
        )
        MultiChoiceSegmentedButtonRow(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Modality.entries.forEachIndexed { index, modality ->
                SegmentedButton(
                    checked = modality in outputModalities,
                    shape = SegmentedButtonDefaults.itemShape(index, Modality.entries.size),
                    onCheckedChange = {
                        if (it) {
                            onUpdateOutputModalities(outputModalities + modality)
                        } else {
                            onUpdateOutputModalities(outputModalities - modality)
                        }
                    }
                ) {
                    Text(
                        text = stringResource(
                            when (modality) {
                                Modality.TEXT -> R.string.setting_provider_page_text
                                Modality.IMAGE -> R.string.setting_provider_page_image
                            }
                        )
                    )
                }
            }
        }
    }
}

@Composable
fun ModalAbilitySelector(
    abilities: List<ModelAbility>,
    onUpdateAbilities: (List<ModelAbility>) -> Unit
) {
    Text(
        stringResource(R.string.setting_provider_page_abilities),
        style = MaterialTheme.typography.titleSmall
    )
    MultiChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth(),
    ) {
        ModelAbility.entries.forEachIndexed { index, ability ->
            SegmentedButton(
                checked = ability in abilities,
                shape = SegmentedButtonDefaults.itemShape(index, ModelAbility.entries.size),
                onCheckedChange = {
                    if (it) {
                        onUpdateAbilities(abilities + ability)
                    } else {
                        onUpdateAbilities(abilities - ability)
                    }
                },
                label = {
                    Text(
                        text = stringResource(
                            when (ability) {
                                ModelAbility.TOOL -> R.string.setting_provider_page_tool
                                ModelAbility.REASONING -> R.string.setting_provider_page_reasoning
                            }
                        )
                    )
                }
            )
        }
    }
}

@Composable
private fun ModelCard(
    model: Model,
    position: ItemPosition,
    canDelete: Boolean,
    onDelete: () -> Unit,
    onEdit: (Model) -> Unit,
    onUpdateModels: (List<Model>) -> Unit,
    onGenerateModelName: suspend (String) -> String?,
    parentProvider: ProviderSetting,
    dragHandle: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val dialogState = useEditState<Model> {
        onEdit(it)
    }
    val scope = rememberCoroutineScope()
    var syncedProviderModels by remember(model.id, parentProvider.models) {
        mutableStateOf<List<Model>?>(null)
    }


    if (dialogState.isEditing) {
        dialogState.currentState?.let { editingModel ->
            val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = {
                    dialogState.dismiss()
                },
                sheetState = sheetState,
                sheetGesturesEnabled = false,
                dragHandle = null,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.95f)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    sheetState.hide()
                                    dialogState.dismiss()
                                }
                            },
                            modifier = Modifier.align(Alignment.CenterStart)
                        ) {
                            Icon(Icons.Rounded.Close, null)
                        }
                        Text(
                            text = stringResource(R.string.setting_provider_page_edit_model),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        ModelSettingsForm(
                            model = editingModel,
                            onModelChange = { dialogState.currentState = it },
                            onProviderModelsChange = { updatedModels ->
                                syncedProviderModels = updatedModels
                                dialogState.currentState = updatedModels.firstOrNull { it.id == editingModel.id }
                                    ?: dialogState.currentState
                            },
                            onGenerateModelName = onGenerateModelName,
                            isEdit = true,
                            parentProvider = parentProvider
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(
                            onClick = {
                                dialogState.dismiss()
                            },
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = {
                                if (editingModel.displayName.isNotBlank()) {
                                    if (syncedProviderModels != null) {
                                        onUpdateModels(
                                            syncedProviderModels!!.map { syncedModel ->
                                                if (syncedModel.id == editingModel.id) editingModel else syncedModel
                                            }
                                        )
                                        dialogState.dismiss()
                                    } else {
                                        dialogState.confirm()
                                    }
                                }
                            },
                        ) {
                            Text(stringResource(R.string.confirm))
                        }
                    }
                }
            }
        }
    }

    val cardContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(0.dp))
                .background(
                    color = if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) 
                        MaterialTheme.colorScheme.surfaceContainerLow 
                    else 
                        MaterialTheme.colorScheme.surfaceContainerHigh
                )
                .clickable {
                    dialogState.open(model.copy())
                }
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModelIcon(
                model = model,
                provider = parentProvider,
                modifier = Modifier.size(32.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = model.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (model.providerOverwrite != null) {
                        Tag(type = TagType.INFO) {
                            Text(
                                model.providerOverwrite?.javaClass?.simpleName ?: model.providerOverwrite?.name
                                ?: "ProviderOverwrite"
                            )
                        }
                    }
                    ModelTypeTag(model = model)
                    ModelModalityTag(model = model)
                    ModelAbilityTag(model = model)
                }
            }
            dragHandle()
        }
    }
    PhysicsSwipeToDelete(
        position = position,
        deleteEnabled = canDelete,
        onDelete = onDelete,
        modifier = modifier.fillMaxWidth()
    ) {
        cardContent()
    }
}

@Composable
private fun BuiltInToolsSettings(
    model: Model,
    parentProvider: ProviderSetting?,
    tools: Set<BuiltInTools>,
    onUpdateTools: (Set<BuiltInTools>) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.setting_page_built_in_tools),
            style = MaterialTheme.typography.titleMedium
        )

        Text(
            text = stringResource(R.string.setting_page_built_in_tools_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val showClaudeWebSearchOption =
            tools.contains(BuiltInTools.ClaudeWebSearch) ||
                tools.contains(BuiltInTools.ClaudeWebSearchDisabled) ||
                parentProvider is ProviderSetting.Claude ||
                ModelRegistry.CLAUDE_SERIES.match(model.modelId) ||
                model.modelId.contains("claude", ignoreCase = true)
        val isClaudeWebSearchChecked = model.isClaudeBuiltInSearchEnabled(parentProvider)

        val showGrokSearchOptions =
            tools.contains(BuiltInTools.GrokWebSearch) ||
                tools.contains(BuiltInTools.GrokXSearch) ||
                (parentProvider is ProviderSetting.OpenAI &&
                    parentProvider.baseUrl.contains("x.ai", ignoreCase = true)) ||
                ModelRegistry.GROK_4.match(model.modelId) ||
                model.modelId.contains("grok", ignoreCase = true)

        val availableTools = buildList {
            add(
                BuiltInTools.Search to Pair(
                    stringResource(R.string.setting_page_built_in_tools_search),
                    stringResource(R.string.setting_page_built_in_tools_search_desc)
                )
            )
            if (showClaudeWebSearchOption) {
                add(
                    BuiltInTools.ClaudeWebSearch to Pair(
                        stringResource(R.string.setting_page_built_in_tools_claude_search),
                        stringResource(R.string.setting_page_built_in_tools_claude_search_desc)
                    )
                )
            }
            add(
                BuiltInTools.UrlContext to Pair(
                    stringResource(R.string.setting_page_built_in_tools_url_context),
                    stringResource(R.string.setting_page_built_in_tools_url_context_desc)
                )
            )
            if (showGrokSearchOptions) {
                add(
                    BuiltInTools.GrokWebSearch to Pair(
                        stringResource(R.string.setting_page_built_in_tools_grok_web_search),
                        stringResource(R.string.setting_page_built_in_tools_grok_web_search_desc)
                    )
                )
                add(
                    BuiltInTools.GrokXSearch to Pair(
                        stringResource(R.string.setting_page_built_in_tools_grok_x_search),
                        stringResource(R.string.setting_page_built_in_tools_grok_x_search_desc)
                    )
                )
            }
        }

        availableTools.forEach { (tool, info) ->
            val (title, description) = info
            val checked = when (tool) {
                BuiltInTools.ClaudeWebSearch -> isClaudeWebSearchChecked
                else -> tool in tools
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = if (me.rerere.rikkahub.ui.theme.LocalDarkMode.current) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HapticSwitch(
                        checked = checked,
                        onCheckedChange = { checked ->
                            when (tool) {
                                BuiltInTools.ClaudeWebSearch -> {
                                    if (checked) {
                                        onUpdateTools(
                                            (tools - BuiltInTools.ClaudeWebSearchDisabled) + BuiltInTools.ClaudeWebSearch
                                        )
                                    } else {
                                        onUpdateTools(
                                            (tools - BuiltInTools.ClaudeWebSearch) + BuiltInTools.ClaudeWebSearchDisabled
                                        )
                                    }
                                }

                                else -> {
                                    if (checked) {
                                        onUpdateTools(tools + tool)
                                    } else {
                                        onUpdateTools(tools - tool)
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderOverrideSettings(
    providerOverride: ProviderSetting?,
    onUpdateProviderOverride: (ProviderSetting?) -> Unit,
    parentProvider: ProviderSetting?
) {
    var showProviderConfig by remember { mutableStateOf(false) }
    var editingProvider by remember { mutableStateOf<ProviderSetting?>(null) }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.setting_provider_page_provider_override),
            style = MaterialTheme.typography.titleSmall
        )

        Text(
            text = stringResource(R.string.setting_provider_page_provider_override_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (providerOverride != null) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProviderIcon(
                            provider = providerOverride,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = stringResource(
                                R.string.setting_provider_page_override_name_format,
                                providerOverride.name
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                editingProvider = providerOverride
                                showProviderConfig = true
                            }
                        ) {
                            Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.a11y_edit_override))
                        }
                        IconButton(
                            onClick = {
                                onUpdateProviderOverride(null)
                            }
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.a11y_remove_override))
                        }
                    }
                }
            }
        } else {
            Button(
                onClick = {
                    editingProvider = parentProvider?.copyProvider(
                        id = Uuid.random(),
                        builtIn = false,
                        models = emptyList(), // 这里必须设置为空，不然会导致循环依赖JSON
                        description = {},
                    )
                    showProviderConfig = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text(stringResource(R.string.setting_provider_page_add_provider_override))
            }
        }

        // Provider configuration modal
        if (showProviderConfig && editingProvider != null) {
            ModalBottomSheet(
                onDismissRequest = {
                    showProviderConfig = false
                    editingProvider = null
                },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                var internalProvider by remember(editingProvider) { mutableStateOf(editingProvider!!) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.9f)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.setting_provider_page_configure_provider_override),
                        style = MaterialTheme.typography.titleLarge,
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        ProviderConfigure(
                            provider = internalProvider,
                            onEdit = { internalProvider = it }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(
                            onClick = {
                                showProviderConfig = false
                                editingProvider = null
                            },
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(
                            onClick = {
                                onUpdateProviderOverride(internalProvider)
                                showProviderConfig = false
                                editingProvider = null
                            },
                        ) {
                            Text(stringResource(R.string.setting_provider_page_save))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TokenQuotaSettings(
    model: Model,
    onModelChange: (Model) -> Unit,
    onProviderModelsChange: ((List<Model>) -> Unit)?,
    parentProvider: ProviderSetting?,
) {
    val quota = model.quota ?: ModelQuota()
    val toaster = LocalToaster.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelQuotaRepo = koinInject<ModelQuotaRepository>()
    val numberFormat = remember { NumberFormat.getNumberInstance() }
    val haptics = rememberPremiumHaptics(enabled = true)
    var showConfigSheet by rememberSaveable(model.id) { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    var draftModels by remember(parentProvider?.id, model.id) {
        mutableStateOf(
            parentProvider?.models?.upsertModel(model) ?: listOf(model)
        )
    }

    LaunchedEffect(model) {
        draftModels = draftModels.upsertModel(model)
    }

    val currentModel = draftModels.firstOrNull { it.id == model.id } ?: model
    val currentQuota = currentModel.quota ?: ModelQuota()
    val enabled = currentQuota.enabled

    var quotaUsage by remember(enabled, model.id) {
        mutableStateOf<QuotaUsageResult?>(null)
    }
    LaunchedEffect(enabled, currentModel, draftModels) {
        if (enabled) {
            quotaUsage = modelQuotaRepo.getQuotaUsage(currentModel, draftModels)
        } else {
            quotaUsage = null
        }
    }

    fun commitModels(updatedModels: List<Model>) {
        draftModels = updatedModels
        val updatedCurrent = updatedModels.firstOrNull { it.id == currentModel.id } ?: currentModel
        if (onProviderModelsChange != null && parentProvider?.models?.any { it.id == currentModel.id } == true) {
            onProviderModelsChange(updatedModels)
        } else {
            onModelChange(updatedCurrent)
        }
    }

    fun updateQuota(updatedQuota: ModelQuota) {
        commitModels(
            syncQuotaConfig(
                models = draftModels.upsertModel(currentModel),
                modelId = currentModel.id,
                quota = updatedQuota,
            )
        )
    }

    val sharedIds = remember(draftModels, currentModel.id) {
        findQuotaGroupIds(draftModels, currentModel.id) - currentModel.id
    }
    val entrySubtitle = when {
        !enabled -> stringResource(R.string.quota_settings_disabled_desc)
        quotaUsage != null -> {
            val usage = quotaUsage!!
            stringResource(
                R.string.quota_settings_summary_with_usage,
                numberFormat.format(usage.usedTokens),
                numberFormat.format(usage.tokenLimit),
                sharedIds.size,
            )
        }
        else -> stringResource(
            R.string.quota_settings_summary,
            numberFormat.format(currentQuota.tokenLimit),
            sharedIds.size,
        )
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 400f),
        label = "quota_entry_scale",
    )
    val entryAlpha by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "quota_entry_alpha",
    )

    OutlinedCard(
        onClick = {
            haptics.perform(HapticPattern.Pop)
            showConfigSheet = true
        },
        interactionSource = interactionSource,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = entryAlpha
            },
        shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (LocalDarkMode.current) {
                MaterialTheme.colorScheme.surfaceContainerLow
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                modifier = Modifier.size(44.dp),
                shape = CircleShape,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.NetworkCheck,
                        contentDescription = null,
                        tint = if (enabled) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.quota_settings_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = entrySubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showConfigSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showConfigSheet = false },
            sheetState = sheetState,
            sheetGesturesEnabled = false,
            dragHandle = null,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.92f)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    IconButton(
                        onClick = {
                            scope.launch {
                                sheetState.hide()
                                showConfigSheet = false
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterStart),
                    ) {
                        Icon(Icons.Rounded.Close, null)
                    }
                    Text(
                        text = stringResource(R.string.quota_settings_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    QuotaUsageCard(
                        usage = quotaUsage,
                        quota = currentQuota,
                        numberFormat = numberFormat,
                    )

                    QuotaSectionCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.quota_settings_enable),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = stringResource(R.string.quota_settings_enable_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            HapticSwitch(
                                checked = enabled,
                                onCheckedChange = { checked ->
                                    haptics.perform(HapticPattern.Pop)
                                    updateQuota(currentQuota.copy(enabled = checked))
                                },
                            )
                        }
                    }

                    AnimatedVisibility(visible = enabled) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            QuotaSectionCard(
                                title = stringResource(R.string.quota_settings_usage_rules),
                            ) {
                                QuotaLimitField(
                                    tokenLimit = currentQuota.tokenLimit,
                                    onTokenLimitChange = {
                                        updateQuota(currentQuota.copy(tokenLimit = it))
                                    },
                                )

                                QuotaReminderSlider(
                                    percentage = currentQuota.reminderPercentage,
                                    onPercentageChange = {
                                        updateQuota(currentQuota.copy(reminderPercentage = it))
                                    },
                                )
                            }

                            QuotaSectionCard(
                                title = stringResource(R.string.quota_settings_reset_schedule),
                            ) {
                                QuotaResetPeriodPicker(
                                    period = currentQuota.resetPeriod,
                                    onPeriodChange = {
                                        updateQuota(currentQuota.copy(resetPeriod = it))
                                    },
                                )
                                QuotaResetTimeFields(
                                    quota = currentQuota,
                                    onQuotaChange = ::updateQuota,
                                )
                            }

                            QuotaSectionCard(
                                title = stringResource(R.string.quota_settings_shared_models),
                                subtitle = stringResource(R.string.quota_settings_shared_models_desc),
                            ) {
                                val otherModels = draftModels.filter { it.id != currentModel.id }
                                if (otherModels.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.quota_settings_shared_models_empty),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        otherModels.forEach { otherModel ->
                                            val selected = otherModel.id in sharedIds
                                            QuotaSharedModelRow(
                                                model = otherModel,
                                                selected = selected,
                                                onClick = {
                                                    haptics.perform(HapticPattern.Pop)
                                                    val nextSharedIds = if (selected) {
                                                        sharedIds - otherModel.id
                                                    } else {
                                                        sharedIds + otherModel.id
                                                    }
                                                    commitModels(
                                                        syncQuotaSharing(
                                                            models = draftModels,
                                                            modelId = currentModel.id,
                                                            sharedModelIds = nextSharedIds,
                                                        )
                                                    )
                                                },
                                            )
                                        }
                                    }
                                }
                            }

                            OutlinedCard(
                                onClick = {
                                    haptics.perform(HapticPattern.Pop)
                                    showResetConfirm = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = me.rerere.rikkahub.ui.theme.AppShapes.CardMedium,
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Refresh,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                    Text(
                                        text = stringResource(R.string.quota_settings_reset_usage),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(R.string.quota_settings_reset_usage)) },
            text = { Text(stringResource(R.string.quota_settings_reset_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val groupIds = findQuotaGroupIds(draftModels, currentModel.id) - currentModel.id
                            modelQuotaRepo.resetQuota(
                                ownerModelId = currentModel.id,
                                sharedModelIds = groupIds,
                            )
                            quotaUsage = modelQuotaRepo.getQuotaUsage(currentModel, draftModels)
                        }
                        showResetConfirm = false
                        toaster.show(
                            message = context.getString(R.string.quota_settings_reset_usage),
                            type = ToastType.Success,
                        )
                    },
                ) {
                    Text(stringResource(R.string.confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
