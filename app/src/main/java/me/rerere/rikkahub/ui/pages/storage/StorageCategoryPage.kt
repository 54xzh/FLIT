package me.rerere.rikkahub.ui.pages.storage

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.repository.StorageCategoryKey
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.utils.UiState
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun StorageCategoryPage(
    category: String,
    vm: StorageCategoryVM = koinViewModel(parameters = { parametersOf(category) }),
) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val haptics = rememberPremiumHaptics()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    val categoryKey = vm.category
    val assistants by vm.assistants.collectAsStateWithLifecycle()
    val selectedAssistantId by vm.selectedAssistantId.collectAsStateWithLifecycle()

    val usageState by vm.categoryUsage.collectAsStateWithLifecycle()
    val attachmentStatsState by vm.assistantAttachmentStats.collectAsStateWithLifecycle()
    val conversationCountState by vm.assistantConversationCount.collectAsStateWithLifecycle()
    val chatRecordMonthsState by vm.chatRecordMonths.collectAsStateWithLifecycle()
    val assistantImagesState by vm.assistantImages.collectAsStateWithLifecycle()
    val assistantFilesState by vm.assistantFiles.collectAsStateWithLifecycle()
    val orphanScanState by vm.orphanScan.collectAsStateWithLifecycle()
    val cacheTopLevelUsageState by vm.cacheTopLevelUsage.collectAsStateWithLifecycle()
    val actionState by vm.action.collectAsStateWithLifecycle()

    LaunchedEffect(actionState) {
        when (actionState) {
            is UiState.Success -> {
                haptics.perform(HapticPattern.Success)
                toaster.show(message = context.getString(storageCategorySuccessToastRes(categoryKey)))
            }

            is UiState.Error -> {
                haptics.perform(HapticPattern.Error)
                toaster.show(message = (actionState as UiState.Error).error.message ?: "Error")
            }

            else -> Unit
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            OneUITopAppBar(
                title = stringResource(storageCategoryTitleRes(categoryKey)),
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
        StorageCategoryScaffoldContent(
            category = categoryKey,
            innerPadding = innerPadding,
            usageState = usageState,
            assistants = assistants,
            selectedAssistantId = selectedAssistantId,
            onSelectAssistant = { id ->
                haptics.perform(HapticPattern.Pop)
                vm.selectAssistant(id)
            },
            attachmentStatsState = attachmentStatsState,
            conversationCountState = conversationCountState,
            assistantImagesState = assistantImagesState,
            assistantFilesState = assistantFilesState,
            chatRecordMonthsState = chatRecordMonthsState,
            cacheTopLevelUsageState = cacheTopLevelUsageState,
            onDeleteImages = { assistantId, absolutePaths -> vm.deleteImages(assistantId, absolutePaths) },
            onDeleteFiles = { assistantId, absolutePaths -> vm.deleteFiles(assistantId, absolutePaths) },
            onClearAssistantFiles = { assistantId -> vm.clearAssistantFiles(assistantId) },
            onLoadChatRecordConversationsByYearMonth = { assistantId, yearMonth ->
                vm.getChatRecordConversationsByYearMonth(
                    assistantId = assistantId,
                    yearMonth = yearMonth,
                )
            },
            onClearChatRecordSelection = { assistantId, yearMonths, conversationIds ->
                vm.clearChatRecordsSelection(
                    assistantId = assistantId,
                    yearMonths = yearMonths,
                    conversationIds = conversationIds,
                )
            },
            orphanScanState = orphanScanState,
            onScanOrphans = { vm.scanOrphans() },
            onClearAllOrphans = { vm.clearAllOrphans() },
            onClearCache = { vm.clearCache() },
            onOpenLogs = { navController.navigate(Screen.RequestLogs) },
            onLoadMoreImages = { vm.loadMoreImages() },
            onLoadMoreFiles = { vm.loadMoreFiles() },
        )
    }
}

private fun storageCategoryTitleRes(category: StorageCategoryKey): Int = when (category) {
    StorageCategoryKey.IMAGES -> R.string.storage_category_images
    StorageCategoryKey.FILES -> R.string.storage_category_files
    StorageCategoryKey.CHAT_RECORDS -> R.string.storage_category_chat_records
    StorageCategoryKey.CACHE -> R.string.storage_category_cache
    StorageCategoryKey.HISTORY_FILES -> R.string.storage_category_history_files
    StorageCategoryKey.LOGS -> R.string.storage_category_logs
}

private fun storageCategorySuccessToastRes(category: StorageCategoryKey): Int = when (category) {
    StorageCategoryKey.IMAGES -> R.string.storage_toast_images_cleared
    StorageCategoryKey.FILES -> R.string.storage_toast_files_cleared
    StorageCategoryKey.CHAT_RECORDS -> R.string.storage_toast_chat_records_cleared
    StorageCategoryKey.CACHE -> R.string.storage_toast_cache_cleared
    StorageCategoryKey.HISTORY_FILES -> R.string.storage_toast_history_cleared
    StorageCategoryKey.LOGS -> R.string.storage_toast_done
}
