package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalAbsoluteTonalElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Settings
import me.rerere.rikkahub.ui.components.ui.ToastType
import me.rerere.rikkahub.ui.context.LocalToaster
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.supportsBuiltInSearch
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.datastore.findProvider
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.ui.AutoAIIcon
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.SearchAbilityTagLine
import me.rerere.search.SearchService
import me.rerere.search.SearchServiceOptions
import me.rerere.search.displayName
import me.rerere.rikkahub.ui.hooks.rememberAmoledDarkMode
import org.koin.compose.koinInject

import androidx.compose.ui.graphics.Shape

private const val SearchPickerMotionDamping = 0.78f
private const val SearchPickerMotionStiffness = 260f

@Composable
fun SearchPickerButton(
    enableSearch: Boolean,
    settings: Settings,
    modifier: Modifier = Modifier,
    shape: Shape = me.rerere.rikkahub.ui.theme.AppShapes.CardMedium,
    onToggleSearch: (Boolean) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    model: Model?,
    selectedProviderIndex: Int = -1, // -1 means use global setting, otherwise use this index
    isBuiltInMode: Boolean = false, // true when assistant's searchMode is BuiltIn
    preferBuiltInSearch: Boolean = false, // true when prefer built-in search toggle is ON
    onTogglePreferBuiltInSearch: (Boolean) -> Unit = {}, // callback to update assistant.preferBuiltInSearch
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onlyIcon: Boolean = false,
    selectedProviderIndices: List<Int> = emptyList(),
    onUpdateSearchProviders: ((List<Int>) -> Unit)? = null,
    enableSearchAgent: Boolean = false,
    onToggleSearchAgent: ((Boolean) -> Unit)? = null,
) {
    val toaster = LocalToaster.current
    var showSearchPicker by remember { mutableStateOf(false) }
    
    // Track the last valid provider index locally to persist across on/off toggles
    // This prevents "jumping" when toggling search off and back on
    var lastValidProviderIndex by remember { mutableStateOf(
        if (selectedProviderIndex >= 0) selectedProviderIndex 
        else settings.searchServiceSelected.coerceIn(0, (settings.searchServices.size - 1).coerceAtLeast(0))
    ) }
    
    // Update lastValidProviderIndex when a valid external index is provided
    LaunchedEffect(selectedProviderIndex) {
        if (selectedProviderIndex >= 0 && selectedProviderIndex < settings.searchServices.size) {
            lastValidProviderIndex = selectedProviderIndex
        }
    }
    
    // Use the external index if valid, otherwise use our tracked last valid index
    val effectiveProviderIndex = if (selectedProviderIndex >= 0 && selectedProviderIndex < settings.searchServices.size) {
        selectedProviderIndex
    } else {
        lastValidProviderIndex.coerceIn(0, (settings.searchServices.size - 1).coerceAtLeast(0))
    }
    val currentService = settings.searchServices.getOrNull(effectiveProviderIndex)
    val sanitizedSelectedProviderIndices = remember(selectedProviderIndices, settings.searchServices.size) {
        selectedProviderIndices
            .asSequence()
            .filter { index -> index >= 0 && index < settings.searchServices.size }
            .distinct()
            .sorted()
            .toList()
    }
    val modelProvider = remember(model, settings.providers) {
        model?.findProvider(settings.providers)
    }
    val modelSupportsBuiltIn = model?.supportsBuiltInSearch(modelProvider) == true
    val isUsingBuiltIn = enableSearch && !enableSearchAgent && modelSupportsBuiltIn && (isBuiltInMode || preferBuiltInSearch)

    ToggleSurface(
        modifier = modifier,
        checked = enableSearch,
        checkedColor = Color.Transparent,
        uncheckedColor = Color.Transparent,
        contentColor = contentColor,
        onClick = {
            showSearchPicker = true
        }
    ) {
        Row(
            modifier = Modifier
                .padding(if (onlyIcon) 8.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (enableSearch && !isUsingBuiltIn && currentService != null) {
                    AutoAIIcon(
                        name = SearchServiceOptions.TYPES[currentService::class] ?: "Search",
                        color = Color.Transparent
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Public,
                        contentDescription = stringResource(R.string.use_web_search),
                    )
                }
            }
        }
    }

    if (showSearchPicker) {
        ModalBottomSheet(
            onDismissRequest = { showSearchPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .padding(bottom = 16.dp) // Extra padding at bottom for navigation bar
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.search_picker_title),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )

                SearchPicker(
                    enableSearch = enableSearch,
                    settings = settings,
                    onToggleSearch = onToggleSearch,
                    onUpdateSearchService = { index ->
                        lastValidProviderIndex = index
                        onUpdateSearchService(index)
                    },
                    modifier = Modifier
                        .fillMaxWidth(),
                    model = model,
                    selectedProviderIndex = effectiveProviderIndex,
                    selectedProviderIndices = sanitizedSelectedProviderIndices,
                    onUpdateSearchProviders = onUpdateSearchProviders?.let { callback ->
                        { indices ->
                            val next = indices
                                .asSequence()
                                .filter { index -> index >= 0 && index < settings.searchServices.size }
                                .distinct()
                                .sorted()
                                .toList()
                            next.firstOrNull()?.let { lastValidProviderIndex = it }
                            callback(next)
                        }
                    },
                    preferBuiltInSearch = preferBuiltInSearch,
                    onTogglePreferBuiltInSearch = onTogglePreferBuiltInSearch,
                    enableSearchAgent = enableSearchAgent,
                    onToggleSearchAgent = onToggleSearchAgent,
                    onDismiss = {
                        showSearchPicker = false
                    }
                )
            }
        }
    }
}

@Composable
internal fun SearchPicker(
    enableSearch: Boolean,
    settings: Settings,
    model: Model?,
    modifier: Modifier = Modifier,
    onToggleSearch: (Boolean) -> Unit,
    onUpdateSearchService: (Int) -> Unit,
    selectedProviderIndex: Int = -1,
    selectedProviderIndices: List<Int> = emptyList(),
    onUpdateSearchProviders: ((List<Int>) -> Unit)? = null,
    preferBuiltInSearch: Boolean = false,
    onTogglePreferBuiltInSearch: (Boolean) -> Unit = {},
    enableSearchAgent: Boolean = false,
    onToggleSearchAgent: ((Boolean) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val navBackStack = LocalNavController.current
    val modelProvider = remember(model, settings.providers) {
        model?.findProvider(settings.providers)
    }
    val modelSupportsBuiltIn = model?.supportsBuiltInSearch(modelProvider) == true

    // 显示搜索服务选择 (always show, but selection only applies when not using built-in)
    AppSearchSettings(
        enableSearch = enableSearch,
        onDismiss = onDismiss,
        navBackStack = navBackStack,
        onToggleSearch = onToggleSearch,
        modifier = modifier,
        settings = settings,
        selectedProviderIndex = selectedProviderIndex,
        selectedProviderIndices = selectedProviderIndices,
        onUpdateSearchService = onUpdateSearchService,
        onUpdateSearchProviders = onUpdateSearchProviders,
        modelSupportsBuiltIn = modelSupportsBuiltIn,
        preferBuiltInSearch = preferBuiltInSearch,
        onTogglePreferBuiltInSearch = onTogglePreferBuiltInSearch,
        enableSearchAgent = enableSearchAgent,
        onToggleSearchAgent = onToggleSearchAgent,
    )
}

@Composable
private fun AppSearchSettings(
    enableSearch: Boolean,
    onDismiss: () -> Unit,
    navBackStack: NavHostController,
    onToggleSearch: (Boolean) -> Unit,
    modifier: Modifier,
    settings: Settings,
    selectedProviderIndex: Int = -1,
    selectedProviderIndices: List<Int> = emptyList(),
    onUpdateSearchService: (Int) -> Unit,
    onUpdateSearchProviders: ((List<Int>) -> Unit)? = null,
    modelSupportsBuiltIn: Boolean,
    preferBuiltInSearch: Boolean,
    onTogglePreferBuiltInSearch: (Boolean) -> Unit,
    enableSearchAgent: Boolean,
    onToggleSearchAgent: ((Boolean) -> Unit)?,
) {
    val amoledMode by rememberAmoledDarkMode()
    val isDarkMode = LocalDarkMode.current
    val isAmoled = amoledMode && isDarkMode
    
    val numProviders = settings.searchServices.size
    val showProviderItems = numProviders > 1
    val showSearchAgent = enableSearch && settings.searchServices.isNotEmpty() && onToggleSearchAgent != null
    val searchGroupItems = 1 +
        (if (modelSupportsBuiltIn) 1 else 0) +
        (if (showSearchAgent) 1 else 0)
    val builtInSearchIndex = 1
    val searchAgentIndex = 1 + if (modelSupportsBuiltIn) 1 else 0
    
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Column {
            SearchToggleItem(
                enableSearch = enableSearch,
                onToggleSearch = onToggleSearch,
                onDismiss = onDismiss,
                navBackStack = navBackStack,
                index = 0,
                totalCount = searchGroupItems,
                isAmoled = isAmoled,
                isDarkMode = isDarkMode
            )

            if (modelSupportsBuiltIn) {
                SearchOptionToggleItem(
                    modifier = Modifier.padding(top = 4.dp),
                    icon = Icons.Rounded.Search,
                    title = stringResource(R.string.built_in_search_title),
                    subtitle = stringResource(R.string.built_in_search_description),
                    checked = preferBuiltInSearch,
                    onCheckedChange = onTogglePreferBuiltInSearch,
                    index = builtInSearchIndex,
                    totalCount = searchGroupItems,
                    isDarkMode = isDarkMode,
                )
            }

            AnimatedVisibility(
                visible = showSearchAgent,
                enter = fadeIn(
                    animationSpec = spring(
                        dampingRatio = SearchPickerMotionDamping,
                        stiffness = SearchPickerMotionStiffness
                    )
                ) + expandVertically(
                    expandFrom = Alignment.Top,
                    animationSpec = spring(
                        dampingRatio = SearchPickerMotionDamping,
                        stiffness = SearchPickerMotionStiffness
                    )
                ),
                exit = fadeOut(
                    animationSpec = spring(
                        dampingRatio = SearchPickerMotionDamping,
                        stiffness = SearchPickerMotionStiffness
                    )
                ) + shrinkVertically(
                    shrinkTowards = Alignment.Top,
                    animationSpec = spring(
                        dampingRatio = SearchPickerMotionDamping,
                        stiffness = SearchPickerMotionStiffness
                    )
                ),
            ) {
                SearchOptionToggleItem(
                    modifier = Modifier.padding(top = 4.dp),
                    icon = Icons.Rounded.SmartToy,
                    title = stringResource(R.string.setting_search_page_enable_search_agent),
                    subtitle = null,
                    checked = enableSearchAgent,
                    onCheckedChange = onToggleSearchAgent ?: {},
                    index = searchAgentIndex,
                    totalCount = searchAgentIndex + 1,
                    isDarkMode = isDarkMode,
                )
            }
        }

        if (showProviderItems) {
            Spacer(modifier = Modifier.size(8.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                settings.searchServices.forEachIndexed { index, service ->
                    val allowMultiSelect = onUpdateSearchProviders != null
                    if (allowMultiSelect) {
                        val isChecked = selectedProviderIndices.contains(index)
                        SearchProviderToggleItem(
                            service = service,
                            checked = isChecked,
                            onCheckedChange = { enabled ->
                                val next = buildList {
                                    addAll(selectedProviderIndices)
                                    if (enabled) add(index) else removeAll(listOf(index))
                                }.asSequence()
                                    .filter { it >= 0 && it < settings.searchServices.size }
                                    .distinct()
                                    .sorted()
                                    .toList()
                                onUpdateSearchProviders?.invoke(next)
                            },
                            isAmoled = isAmoled,
                            isDarkMode = isDarkMode,
                            index = index,
                            totalCount = numProviders
                        )
                    } else {
                        val isSelected = selectedProviderIndex == index
                        SearchProviderItem(
                            service = service,
                            isSelected = isSelected,
                            onClick = { onUpdateSearchService(index) },
                            isAmoled = isAmoled,
                            isDarkMode = isDarkMode,
                            index = index,
                            totalCount = numProviders
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberSearchItemShape(
    index: Int,
    totalCount: Int,
    isSelected: Boolean,
    label: String,
): RoundedCornerShape {
    val topCornerTarget = if (isSelected) 50.dp else when {
        totalCount == 1 -> 24.dp
        index == 0 -> 24.dp
        else -> 10.dp
    }
    val bottomCornerTarget = if (isSelected) 50.dp else when {
        totalCount == 1 -> 24.dp
        index == totalCount - 1 -> 24.dp
        else -> 10.dp
    }
    val topCorner by animateDpAsState(
        targetValue = topCornerTarget,
        animationSpec = spring(
            dampingRatio = SearchPickerMotionDamping,
            stiffness = SearchPickerMotionStiffness
        ),
        label = "${label}TopCorner"
    )
    val bottomCorner by animateDpAsState(
        targetValue = bottomCornerTarget,
        animationSpec = spring(
            dampingRatio = SearchPickerMotionDamping,
            stiffness = SearchPickerMotionStiffness
        ),
        label = "${label}BottomCorner"
    )
    return RoundedCornerShape(
        topStart = topCorner,
        topEnd = topCorner,
        bottomStart = bottomCorner,
        bottomEnd = bottomCorner
    )
}

@Composable
private fun SearchOptionToggleItem(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    index: Int,
    totalCount: Int,
    isDarkMode: Boolean,
) {
    val haptics = me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics()
    val animatedShape = rememberSearchItemShape(
        index = index,
        totalCount = totalCount,
        isSelected = checked,
        label = "option"
    )
    val targetContainerColor = if (checked) {
        MaterialTheme.colorScheme.primaryContainer
    } else if (isDarkMode) {
        Color.Black
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val targetContentColor = if (checked) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val containerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "optionContainerColor"
    )
    val contentColor by animateColorAsState(
        targetValue = targetContentColor,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "optionContentColor"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(animatedShape)
            .background(containerColor)
            .clickable {
                haptics.perform(me.rerere.rikkahub.ui.hooks.HapticPattern.Pop)
                onCheckedChange(!checked)
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = contentColor)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = contentColor,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = 0.8f),
                )
            }
        }
        HapticSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SearchToggleItem(
    enableSearch: Boolean,
    onToggleSearch: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    navBackStack: NavHostController,
    index: Int,
    totalCount: Int,
    isAmoled: Boolean,
    isDarkMode: Boolean
) {
    // Use surfaceContainerHigh for Light Mode consistency
    val containerColor = if (isDarkMode) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor = MaterialTheme.colorScheme.onSurface
    val animatedShape = rememberSearchItemShape(
        index = index,
        totalCount = totalCount,
        isSelected = false,
        label = "searchToggle"
    )
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(animatedShape)
            .background(containerColor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Public, null, tint = contentColor)
        Text(
            text = stringResource(R.string.use_web_search),
            style = MaterialTheme.typography.titleMedium,
            color = contentColor,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = {
                onDismiss()
                navBackStack.navigate(Screen.SettingSearch)
            }
        ) {
            Icon(Icons.Rounded.Settings, null, tint = contentColor)
        }
        HapticSwitch(
            checked = enableSearch,
            onCheckedChange = onToggleSearch
        )
    }
}

@Composable
private fun SearchProviderItem(
    service: SearchServiceOptions,
    isSelected: Boolean,
    onClick: () -> Unit,
    isAmoled: Boolean,
    isDarkMode: Boolean,
    index: Int = 0,
    totalCount: Int = 1
) {
    val haptics = me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics()
    val animatedShape = rememberSearchItemShape(
        index = index,
        totalCount = totalCount,
        isSelected = isSelected,
        label = "provider"
    )
    
    // Animated colors for smooth selection transition
    val targetContainerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else if (isDarkMode) {
        Color.Black
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val targetContentColor = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    
    val containerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "containerColor"
    )
    val contentColor by animateColorAsState(
        targetValue = targetContentColor,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "contentColor"
    )
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(animatedShape)
            .background(containerColor)
            .clickable {
                haptics.perform(me.rerere.rikkahub.ui.hooks.HapticPattern.Pop)
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AutoAIIcon(
            name = SearchServiceOptions.TYPES[service::class] ?: "Search",
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = service.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = contentColor,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SearchProviderToggleItem(
    service: SearchServiceOptions,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isAmoled: Boolean,
    isDarkMode: Boolean,
    index: Int = 0,
    totalCount: Int = 1
) {
    val haptics = me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics()
    val animatedShape = rememberSearchItemShape(
        index = index,
        totalCount = totalCount,
        isSelected = checked,
        label = "providerToggle"
    )

    val targetContainerColor = if (checked) {
        MaterialTheme.colorScheme.primaryContainer
    } else if (isDarkMode) {
        Color.Black
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val targetContentColor = if (checked) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val containerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "containerColor"
    )
    val contentColor by animateColorAsState(
        targetValue = targetContentColor,
        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 400f),
        label = "contentColor"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(animatedShape)
            .background(containerColor)
            .clickable {
                haptics.perform(me.rerere.rikkahub.ui.hooks.HapticPattern.Pop)
                onCheckedChange(!checked)
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AutoAIIcon(
            name = SearchServiceOptions.TYPES[service::class] ?: "Search",
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = service.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = contentColor,
            modifier = Modifier.weight(1f)
        )
        HapticSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun BuiltInSearchSetting(
    preferBuiltInSearch: Boolean,
    onTogglePreferBuiltInSearch: (Boolean) -> Unit
) {
    val amoledMode by rememberAmoledDarkMode()
    val isDarkMode = LocalDarkMode.current
    val isAmoled = amoledMode && isDarkMode
    
    val containerColor = if (isDarkMode) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
    val contentColor = if (isAmoled) Color.White else MaterialTheme.colorScheme.onSurface

    CompositionLocalProvider(LocalAbsoluteTonalElevation provides 0.dp) {
        val cardElevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        val cardColors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
        Card(
            shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
            elevation = cardElevation,
            colors = cardColors
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.built_in_search_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = stringResource(R.string.built_in_search_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.8f)
                    )
                }

                HapticSwitch(
                    checked = preferBuiltInSearch,
                    onCheckedChange = { checked ->
                        onTogglePreferBuiltInSearch(checked)
                    }
                )
            }
        }
    }
}
