package me.rerere.rikkahub.ui.components.ai

import me.rerere.rikkahub.ui.theme.LocalDarkMode
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFilter
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.SpeakerNotesOff
import androidx.compose.material.icons.rounded.Terminal
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpStatus
import me.rerere.rikkahub.data.ai.mcp.isVisibleForWorkspace
import me.rerere.rikkahub.data.ai.mcp.isMcpServerEffective
import kotlin.uuid.Uuid
import me.rerere.rikkahub.ui.components.ui.Tag
import me.rerere.rikkahub.ui.components.ui.TagType
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import org.koin.compose.koinInject

@Composable
fun McpPickerButton(
    selectedServerIds: Set<Uuid>,
    visibleWorkspaceId: String?,
    servers: List<McpServerConfig>,
    mcpManager: McpManager,
    modifier: Modifier = Modifier,
    onSelectionChange: (Set<Uuid>) -> Unit,
) {
    var showMcpPicker by remember { mutableStateOf(false) }
    val workspaceRepository = koinInject<me.rerere.rikkahub.data.repository.WorkspaceRepository>()
    val workspaces by remember(workspaceRepository) { workspaceRepository.listFlow() }
        .collectAsStateWithLifecycle(emptyList())
    val status by mcpManager.syncingStatus.collectAsStateWithLifecycle()
    val loading = status.values.any { it == McpStatus.Connecting }
    val visibleServers = servers.fastFilter {
        it.commonOptions.enable && it.isVisibleForWorkspace(visibleWorkspaceId)
    }
    val enabledServers = visibleServers.fastFilter {
        isMcpServerEffective(it, selectedServerIds, visibleWorkspaceId) &&
            (it !is McpServerConfig.StdioServer ||
                workspaces.firstOrNull { workspace -> workspace.id == it.workspaceId }?.sandboxStatus ==
                me.rerere.rikkahub.data.db.entity.SandboxRootfsStatus.READY)
    }
    ToggleSurface(
        modifier = modifier,
        checked = enabledServers.isNotEmpty(),
        onClick = {
            showMcpPicker = true
        }
    ) {
        Row(
            modifier = Modifier
                .padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    BadgedBox(
                        badge = {
                            if (enabledServers.isNotEmpty()) {
                                Badge(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                                ) {
                                    Text(text = enabledServers.size.toString())
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Terminal,
                            contentDescription = stringResource(R.string.mcp_picker_title),
                        )
                    }

                }
            }
        }
    }
    if (showMcpPicker) {
        ModalBottomSheet(
            onDismissRequest = { showMcpPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                modifier = Modifier.Companion
                    .fillMaxWidth()
                    .fillMaxHeight(0.7f)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(id = R.string.mcp_picker_title),
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                AnimatedVisibility(loading) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        LinearWavyProgressIndicator()
                        Text(
                            text = stringResource(id = R.string.mcp_picker_syncing),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                McpPicker(
                    selectedServerIds = selectedServerIds,
                    visibleWorkspaceId = visibleWorkspaceId,
                    servers = servers,
                    onSelectionChange = onSelectionChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }
}

@Composable
fun McpPicker(
    selectedServerIds: Set<Uuid>,
    visibleWorkspaceId: String?,
    servers: List<McpServerConfig>,
    modifier: Modifier = Modifier,
    onSelectionChange: (Set<Uuid>) -> Unit,
) {
    val mcpManager = koinInject<McpManager>()
    val workspaceRepository = koinInject<me.rerere.rikkahub.data.repository.WorkspaceRepository>()
    val workspaces by remember(workspaceRepository) { workspaceRepository.listFlow() }
        .collectAsStateWithLifecycle(emptyList())
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(servers.fastFilter {
            it.commonOptions.enable && it.isVisibleForWorkspace(visibleWorkspaceId)
        }) { server ->
            val status by mcpManager.getStatus(server).collectAsStateWithLifecycle(McpStatus.Idle)
            val stdioRuntimeReady = server !is McpServerConfig.StdioServer ||
                workspaces.firstOrNull { it.id == server.workspaceId }?.sandboxStatus ==
                    me.rerere.rikkahub.data.db.entity.SandboxRootfsStatus.READY
            Card(
                shape = me.rerere.rikkahub.ui.theme.AppShapes.CardLarge,
                colors = CardDefaults.cardColors(
                    containerColor = if (LocalDarkMode.current) Color.Black else MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (status) {
                        McpStatus.Idle -> Icon(Icons.Rounded.SpeakerNotesOff, null)
                        McpStatus.Connecting -> CircularProgressIndicator(
                            modifier = Modifier.size(
                                24.dp
                            )
                        )

                        McpStatus.Connected -> Icon(Icons.Rounded.Terminal, null)
                        McpStatus.Ready -> Icon(Icons.Rounded.Terminal, null)
                        is McpStatus.Reconnecting -> CircularProgressIndicator(
                            modifier = Modifier.size(24.dp)
                        )
                        McpStatus.NeedsAuthorization -> Icon(Icons.Rounded.Error, null)
                        McpStatus.Authorizing -> CircularProgressIndicator(
                            modifier = Modifier.size(24.dp)
                        )
                        is McpStatus.Error -> Icon(Icons.Rounded.Error, null)
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = server.commonOptions.name,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = if (!stdioRuntimeReady) {
                                stringResource(R.string.mcp_status_rootfs_not_ready)
                            } else when (status) {
                                is McpStatus.Idle -> stringResource(R.string.mcp_status_idle)
                                is McpStatus.Connecting -> stringResource(R.string.mcp_status_connecting)
                                is McpStatus.Connected -> stringResource(R.string.mcp_status_connected)
                                is McpStatus.Ready -> stringResource(R.string.mcp_status_ready)
                                is McpStatus.Reconnecting -> stringResource(
                                    R.string.mcp_status_reconnecting_format,
                                    (status as McpStatus.Reconnecting).attempt,
                                    (status as McpStatus.Reconnecting).maxAttempts,
                                )
                                McpStatus.NeedsAuthorization -> stringResource(R.string.mcp_status_needs_authorization)
                                McpStatus.Authorizing -> stringResource(R.string.mcp_status_authorizing)
                                is McpStatus.Error -> {
                                    val err = status as McpStatus.Error
                                    val msg = err.messageResId?.let { stringResource(it) } ?: err.message
                                    stringResource(R.string.mcp_status_error_format, msg)
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = LocalContentColor.current.copy(alpha = 0.8f),
                            maxLines = 5
                        )
                        if (status == McpStatus.Connected || status == McpStatus.Ready) {
                            val tools = server.commonOptions.tools
                            val enabledTools = tools.fastFilter { it.enable }
                            Tag(
                                type = TagType.INFO
                            ) {
                                Text(stringResource(R.string.mcp_tools_count, enabledTools.size, tools.size))
                            }
                        }
                    }
                    HapticSwitch(
                        checked = server.id in selectedServerIds,
                        onCheckedChange = {
                            onSelectionChange(
                                if (it) selectedServerIds + server.id else selectedServerIds - server.id
                            )
                        }
                    )
                }
            }
        }
    }
}
