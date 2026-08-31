package me.rerere.rikkahub.ui.pages.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.AIRequestLogEntity
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.nav.OneUITopAppBar
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.utils.JsonInstant
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

private enum class RequestLogTab {
    BASIC,
    PARAMS,
    REQUEST,
    RESPONSE,
}

@Composable
fun RequestLogDetailPage(
    id: Long,
    vm: RequestLogDetailVM = koinViewModel(parameters = { parametersOf(id) }),
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val haptics = rememberPremiumHaptics()
    val log by vm.log.collectAsStateWithLifecycle(initialValue = null)

    var tabIndex by rememberSaveable { mutableStateOf(RequestLogTab.BASIC.ordinal) }
    LaunchedEffect(id) {
        tabIndex = RequestLogTab.BASIC.ordinal
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            OneUITopAppBar(
                title = stringResource(R.string.request_log_detail_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = { BackButton() },
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            PrimaryTabRow(selectedTabIndex = tabIndex) {
                RequestLogTab.values().forEach { tab ->
                    Tab(
                        selected = tabIndex == tab.ordinal,
                        onClick = {
                            haptics.perform(HapticPattern.Pop)
                            tabIndex = tab.ordinal
                        },
                        text = {
                            Text(
                                text = when (tab) {
                                    RequestLogTab.BASIC -> stringResource(R.string.request_log_section_basic)
                                    RequestLogTab.PARAMS -> stringResource(R.string.request_log_section_params)
                                    RequestLogTab.REQUEST -> stringResource(R.string.request_log_section_request_messages)
                                    RequestLogTab.RESPONSE -> stringResource(R.string.request_log_section_response)
                                }
                            )
                        }
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                when (tabIndex) {
                    RequestLogTab.BASIC.ordinal -> RequestLogBasicTab(log = log)
                    RequestLogTab.PARAMS.ordinal -> RequestLogJsonTab(
                        log = log,
                        title = stringResource(R.string.request_log_section_params),
                        raw = log?.paramsJson.orEmpty(),
                    )

                    RequestLogTab.REQUEST.ordinal -> RequestLogJsonTab(
                        log = log,
                        title = stringResource(R.string.request_log_section_request_messages),
                        raw = log?.requestMessagesJson.orEmpty(),
                    )

                    RequestLogTab.RESPONSE.ordinal -> RequestLogResponseTab(log = log)
                }
            }
        }
    }
}

@Composable
private fun RequestLogBasicTab(log: AIRequestLogEntity?) {
    if (log == null) {
        RequestLogEmptyState(
            icon = Icons.Rounded.History,
            title = stringResource(R.string.request_log_not_found),
        )
        return
    }

    val time = formatLogTime(log.createdAt, "yyyy-MM-dd HH:mm:ss.SSS")
    val sourceLabel = resolveSourceLabel(log.source)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "basic") {
            KeyValueCard(
                title = stringResource(R.string.request_log_section_basic),
                items = listOf(
                    stringResource(R.string.request_log_field_time) to time,
                    stringResource(R.string.request_log_field_source) to sourceLabel,
                    stringResource(R.string.request_log_field_provider) to log.providerName,
                    stringResource(R.string.request_log_field_model) to log.modelDisplayName.ifBlank { log.modelId },
                    stringResource(R.string.request_log_field_latency) to (log.latencyMs?.let { "${it}ms" } ?: "-"),
                    stringResource(R.string.request_log_field_duration) to (log.durationMs?.let { "${it}ms" } ?: "-"),
                    stringResource(R.string.request_log_field_stream) to log.stream.toString(),
                    stringResource(R.string.request_log_field_url) to log.requestUrl.ifBlank { "-" },
                    stringResource(R.string.request_log_field_metadata) to log.metadataJson.orEmpty().ifBlank { "-" },
                )
            )
        }

        if (log.error != null) {
            item(key = "error") {
                KeyValueCard(
                    title = stringResource(R.string.request_log_section_error),
                    items = listOf(stringResource(R.string.request_log_field_error) to log.error),
                    emphasize = true,
                )
            }
        }
    }
}

@Composable
private fun RequestLogJsonTab(
    log: AIRequestLogEntity?,
    title: String,
    raw: String,
) {
    if (log == null) {
        RequestLogEmptyState(
            icon = Icons.Rounded.History,
            title = stringResource(R.string.request_log_not_found),
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "json") {
            RequestLogCodeCard(
                title = title,
                code = raw,
                language = "json",
            )
        }
    }
}

@Composable
private fun RequestLogResponseTab(log: AIRequestLogEntity?) {
    if (log == null) {
        RequestLogEmptyState(
            icon = Icons.Rounded.History,
            title = stringResource(R.string.request_log_not_found),
        )
        return
    }

    val rawResponse = log.responseRawText
    val filteredResponse = log.responseText
    val rawLanguage = detectLogCodeLanguage(rawResponse)
    val filteredLanguage = detectLogCodeLanguage(filteredResponse)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(key = "response_raw") {
            RequestLogCodeCard(
                title = stringResource(R.string.request_log_section_response_raw),
                code = rawResponse,
                language = rawLanguage,
            )
        }

        item(key = "response_filtered") {
            RequestLogCodeCard(
                title = stringResource(R.string.request_log_section_response_filtered),
                code = filteredResponse,
                language = filteredLanguage,
            )
        }
    }
}

private fun detectLogCodeLanguage(content: String): String {
    val trimmed = content.trim()
    if (trimmed.isBlank()) return "txt"
    return runCatching {
        JsonInstant.parseToJsonElement(trimmed)
        "json"
    }.getOrElse { "txt" }
}
