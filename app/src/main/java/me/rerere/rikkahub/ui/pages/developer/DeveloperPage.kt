package me.rerere.rikkahub.ui.pages.developer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.rikkahub.R
import me.rerere.rikkahub.Screen
import me.rerere.rikkahub.data.ai.AILogging
import me.rerere.rikkahub.ui.components.ui.HapticSwitch
import me.rerere.rikkahub.ui.context.LocalNavController
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupInputItem
import me.rerere.rikkahub.ui.pages.setting.components.SettingGroupItem
import me.rerere.rikkahub.ui.theme.AppShapes
import org.koin.androidx.compose.koinViewModel

@Composable
fun DeveloperPage(vm: DeveloperVM = koinViewModel()) {
    val pager = rememberPagerState { 2 }
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.developer_page_title),
                        maxLines = 1,
                    )
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                NavigationBarItem(
                    selected = pager.currentPage == 0,
                    onClick = { scope.launch { pager.animateScrollToPage(0) } },
                    label = {
                        Text(text = stringResource(R.string.developer_page_tab_debug_tools))
                    },
                    icon = {
                        Icon(Icons.Rounded.BugReport, null)
                    }
                )
                NavigationBarItem(
                    selected = pager.currentPage == 1,
                    onClick = { scope.launch { pager.animateScrollToPage(1) } },
                    label = {
                        Text(text = stringResource(R.string.developer_page_tab_request_logs))
                    },
                    icon = {
                        Icon(Icons.Rounded.History, null)
                    }
                )
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pager,
            contentPadding = innerPadding
        ) { page ->
            when (page) {
                0 -> {
                    DeveloperToolsPage(vm = vm)
                }

                1 -> {
                    LoggingPaging(vm = vm)
                }
            }
        }
    }
}

@Composable
private fun DeveloperToolsPage(vm: DeveloperVM) {
    val navController = LocalNavController.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val ipv6DebugState by vm.ipv6DebugState.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SettingGroupItem(
                title = stringResource(R.string.developer_option_markdown_font_debug_title),
                subtitle = stringResource(R.string.developer_option_markdown_font_debug_desc),
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.FontDownload,
                        contentDescription = null,
                    )
                },
                trailing = {
                    HapticSwitch(
                        checked = settings.showMarkdownFontDebugInfo,
                        onCheckedChange = { enabled ->
                            vm.updateSettings { current ->
                                current.copy(showMarkdownFontDebugInfo = enabled)
                            }
                        }
                    )
                },
                onClick = null
            )
        }

        item {
            SettingGroupItem(
                title = stringResource(R.string.developer_option_auto_continue_on_truncation_title),
                subtitle = stringResource(R.string.developer_option_auto_continue_on_truncation_desc),
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.History,
                        contentDescription = null,
                    )
                },
                trailing = {
                    HapticSwitch(
                        checked = settings.autoContinueOnTruncation,
                        onCheckedChange = { enabled ->
                            vm.updateSettings { current ->
                                current.copy(autoContinueOnTruncation = enabled)
                            }
                        }
                    )
                },
                onClick = null
            )
        }

        item {
            SettingGroupInputItem(
                title = stringResource(R.string.developer_option_ipv6_probe_title),
                subtitle = stringResource(R.string.developer_option_ipv6_probe_desc),
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Public,
                        contentDescription = null,
                    )
                },
            ) {
                FilledTonalButton(
                    onClick = { vm.detectIpv6() },
                    enabled = !ipv6DebugState.isLoading,
                ) {
                    Text(
                        text = stringResource(
                            if (ipv6DebugState.isLoading) {
                                R.string.developer_option_ipv6_probe_loading
                            } else {
                                R.string.developer_option_ipv6_probe_action
                            }
                        )
                    )
                }

                if (ipv6DebugState.hasChecked) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SettingGroupItem(
                            title = stringResource(R.string.developer_option_ipv6_probe_http_title),
                            subtitle = ipv6DebugState.httpIpv6
                                ?: stringResource(R.string.developer_option_ipv6_probe_not_found),
                            onClick = null,
                        )
                        SettingGroupItem(
                            title = stringResource(R.string.developer_option_ipv6_probe_system_title),
                            subtitle = ipv6DebugState.systemIpv6
                                ?: stringResource(R.string.developer_option_ipv6_probe_not_found),
                            onClick = null,
                        )
                    }
                }
            }
        }

        item {
            SettingGroupItem(
                title = stringResource(R.string.developer_option_request_logs_title),
                subtitle = stringResource(R.string.setting_request_logs_desc),
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Description,
                        contentDescription = null,
                    )
                },
                onClick = {
                    navController.navigate(Screen.RequestLogs)
                }
            )
        }

        item {
            Card(shape = AppShapes.CardMedium) {
                Text(
                    text = stringResource(R.string.developer_option_markdown_font_debug_tip),
                    modifier = Modifier.padding(14.dp),
                )
            }
        }
    }
}

@Composable
fun LoggingPaging(vm: DeveloperVM) {
    val logs by vm.logs.collectAsStateWithLifecycle()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(logs) { log ->
            when (log) {
                is AILogging.Generation -> {
                    Card {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {

                        }
                    }
                }
            }
        }
    }
}
