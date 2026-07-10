package me.rerere.rikkahub.ui.pages.storage

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.Cached
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.repository.StorageCategoryKey
import me.rerere.rikkahub.data.repository.StorageOverview
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingsGroup
import me.rerere.rikkahub.utils.UiState
import org.koin.androidx.compose.koinViewModel

@Composable
fun StorageManagerPage(
    vm: StorageManagerVM = koinViewModel(),
) {
    val navController = LocalNavController.current
    val haptics = rememberPremiumHaptics()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val overviewState by vm.overview.collectAsStateWithLifecycle()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.storage_manager_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        onClick = {
                            haptics.perform(HapticPattern.Pop)
                            vm.refresh(force = true)
                        }
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = innerPadding,
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item(key = "overview") {
                StorageOverviewGroup(overviewState = overviewState)
            }

            item(key = "categories") {
                StorageCategoriesGroup(
                    overviewState = overviewState,
                    onOpenCategory = { category ->
                        when (category) {
                            StorageCategoryKey.LOGS -> navController.navigate(Screen.RequestLogs)
                            else -> navController.navigate(Screen.StorageCategory(category.key))
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun StorageOverviewGroup(
    overviewState: UiState<StorageOverview>,
) {
    val context = LocalContext.current

    val subtitleText = when (overviewState) {
        UiState.Idle,
        UiState.Loading,
        -> null

        is UiState.Error -> overviewState.error.message ?: "Error"

        is UiState.Success -> null
    }

    val trailing: (@Composable () -> Unit)? = when (overviewState) {
        is UiState.Success -> {
            val totalText = runCatching { Formatter.formatShortFileSize(context, overviewState.data.totalBytes) }
                .getOrNull()
                ?: "${overviewState.data.totalBytes} B"

            {
                Text(
                    text = totalText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        UiState.Idle,
        UiState.Loading,
        -> {
            {
                Text(
                    text = stringResource(R.string.storage_manager_loading_placeholder),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        else -> null
    }

    SettingsGroup(title = stringResource(R.string.storage_manager_overview)) {
        SettingGroupItem(
            title = stringResource(R.string.storage_manager_total),
            subtitle = subtitleText,
            icon = {
                Icon(
                    imageVector = Icons.Rounded.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailing = trailing,
        )
    }
}

@Composable
private fun StorageCategoriesGroup(
    overviewState: UiState<StorageOverview>,
    onOpenCategory: (StorageCategoryKey) -> Unit,
) {
    val context = LocalContext.current

    SettingsGroup(title = stringResource(R.string.storage_manager_categories)) {
        val byKey = (overviewState as? UiState.Success<StorageOverview>)
            ?.data
            ?.categories
            ?.associateBy { it.category }
            .orEmpty()

        val placeholderText = when (overviewState) {
            UiState.Idle,
            UiState.Loading,
            -> stringResource(R.string.storage_manager_loading_placeholder)

            is UiState.Error -> overviewState.error.message ?: "Error"

            is UiState.Success -> null
        }

        StorageCategoryKey.entries.forEach { category ->
            val usage = byKey[category]
            val subtitleText = placeholderText ?: run {
                val bytes = usage?.bytes ?: 0L
                val count = usage?.fileCount ?: 0
                val bytesText = runCatching { Formatter.formatShortFileSize(context, bytes) }
                    .getOrNull()
                    ?: "${bytes} B"

                when (category) {
                    StorageCategoryKey.LOGS -> stringResource(R.string.storage_category_logs_subtitle, count)
                    else -> stringResource(
                        R.string.storage_category_subtitle,
                        bytesText,
                        count,
                    )
                }
            }

            SettingGroupItem(
                title = stringResource(categoryTitleRes(category)),
                subtitle = subtitleText,
                icon = {
                    Icon(
                        imageVector = categoryIcon(category),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = { onOpenCategory(category) },
            )
        }
    }
}

private fun categoryTitleRes(category: StorageCategoryKey): Int = when (category) {
    StorageCategoryKey.IMAGES -> R.string.storage_category_images
    StorageCategoryKey.FILES -> R.string.storage_category_files
    StorageCategoryKey.CHAT_RECORDS -> R.string.storage_category_chat_records
    StorageCategoryKey.CACHE -> R.string.storage_category_cache
    StorageCategoryKey.HISTORY_FILES -> R.string.storage_category_history_files
    StorageCategoryKey.SANDBOX_WORKSPACES -> R.string.storage_category_sandbox_workspaces
    StorageCategoryKey.LOGS -> R.string.storage_category_logs
}

private fun categoryIcon(category: StorageCategoryKey) = when (category) {
    StorageCategoryKey.IMAGES -> Icons.Rounded.Image
    StorageCategoryKey.FILES -> Icons.Rounded.InsertDriveFile
    StorageCategoryKey.CHAT_RECORDS -> Icons.Rounded.Storage
    StorageCategoryKey.CACHE -> Icons.Rounded.Cached
    StorageCategoryKey.HISTORY_FILES -> Icons.Rounded.AutoDelete
    StorageCategoryKey.SANDBOX_WORKSPACES -> Icons.Rounded.Dns
    StorageCategoryKey.LOGS -> Icons.Rounded.History
}
