package me.rerere.rikkahub.ui.pages.extensions.workspace

import android.graphics.Typeface
import android.view.MotionEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.SandboxRootfsStatus
import me.rerere.rikkahub.data.db.entity.WorkspaceType
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.hooks.HapticPattern
import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics
import me.rerere.rikkahub.workspace.SandboxWorkspaceManager
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
fun WorkspaceTerminalPage(
    workspaceId: String,
    vm: WorkspaceDetailVM = koinViewModel { parametersOf(workspaceId) },
) {
    val workspace by vm.workspace.collectAsStateWithLifecycle()
    val manager = koinInject<SandboxWorkspaceManager>()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = workspace?.name?.let {
                            stringResource(R.string.workspace_terminal_title_with_name, it)
                        } ?: stringResource(R.string.workspace_terminal_title),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
            )
        },
    ) { innerPadding ->
        WorkspaceTerminalContent(
            workspaceId = workspaceId,
            workspaceType = workspace?.type,
            rootfsStatus = workspace?.sandboxStatus,
            manager = manager,
            vm = vm,
            contentPadding = innerPadding,
        )
    }
}

@Composable
private fun WorkspaceTerminalContent(
    workspaceId: String,
    workspaceType: WorkspaceType?,
    rootfsStatus: SandboxRootfsStatus?,
    manager: SandboxWorkspaceManager,
    vm: WorkspaceDetailVM,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val terminalTextSizePx = with(LocalDensity.current) { 12.sp.roundToPx() }
    val terminalTypeface = remember(context) {
        ResourcesCompat.getFont(context, R.font.google_sans_code) ?: Typeface.MONOSPACE
    }
    var finished by remember(workspaceId) { mutableStateOf(false) }
    var controlDown by remember(workspaceId) { mutableStateOf(false) }
    var altDown by remember(workspaceId) { mutableStateOf(false) }
    val sessionClient = remember(workspaceId) {
        WorkspaceTerminalSessionClient(context.applicationContext) { finished = true }
    }
    val viewClient = remember(workspaceId) { WorkspaceTerminalViewClient(context) }
    viewClient.controlDown = controlDown
    viewClient.altDown = altDown

    val sessionState by produceState<TerminalSessionUiState>(
        initialValue = TerminalSessionUiState.Loading,
        workspaceId,
        workspaceType,
        rootfsStatus,
        sessionClient,
        manager,
    ) {
        value = try {
            if (workspaceType == null) {
                TerminalSessionUiState.Loading
            } else if (workspaceType != WorkspaceType.SANDBOX) {
                TerminalSessionUiState.NotAvailable
            } else if (rootfsStatus == SandboxRootfsStatus.INSTALLING) {
                TerminalSessionUiState.Installing
            } else if (rootfsStatus != SandboxRootfsStatus.READY) {
                TerminalSessionUiState.NotInstalled
            } else {
                val prepared = withContext(Dispatchers.IO) {
                    when {
                        !workspaceRootfsReady(workspaceId, manager) -> TerminalPreparation.ROOTFS_MISSING
                        !workspaceTerminalRuntimeReady(context) -> TerminalPreparation.RUNTIME_MISSING
                        else -> {
                            prepareWorkspaceTerminalSession(workspaceId, manager)
                            TerminalPreparation.Ready(vm.terminalBindMounts())
                        }
                    }
                }
                when (prepared) {
                    TerminalPreparation.ROOTFS_MISSING -> TerminalSessionUiState.NotInstalled
                    TerminalPreparation.RUNTIME_MISSING -> TerminalSessionUiState.RuntimeUnavailable
                    is TerminalPreparation.Ready -> {
                        if (!isActive) return@produceState
                        finished = false
                        val created = createWorkspaceTerminalSession(
                            context = context,
                            workspaceId = workspaceId,
                            manager = manager,
                            client = sessionClient,
                            workspaceBindMounts = prepared.bindMounts,
                        )
                        if (!isActive) {
                            created.finishIfRunning()
                            return@produceState
                        }
                        TerminalSessionUiState.Ready(created)
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            TerminalSessionUiState.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    val currentState = sessionState
    if (currentState !is TerminalSessionUiState.Ready) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when (currentState) {
                    TerminalSessionUiState.Loading -> stringResource(R.string.workspace_terminal_loading)
                    TerminalSessionUiState.NotInstalled -> stringResource(R.string.workspace_terminal_not_installed)
                    TerminalSessionUiState.Installing -> stringResource(R.string.workspace_terminal_installing)
                    TerminalSessionUiState.RuntimeUnavailable -> stringResource(R.string.workspace_terminal_runtime_unavailable)
                    TerminalSessionUiState.NotAvailable -> stringResource(R.string.workspace_terminal_not_available)
                    is TerminalSessionUiState.Failed -> stringResource(
                        R.string.workspace_terminal_failed,
                        currentState.message,
                    )
                    is TerminalSessionUiState.Ready -> ""
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val session = currentState.session

    DisposableEffect(session) {
        onDispose {
            sessionClient.terminalView = null
            viewClient.terminalView = null
            session.finishIfRunning()
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .imePadding(),
        color = Color.Black,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        TerminalView(viewContext, null).apply {
                            isFocusable = true
                            isFocusableInTouchMode = true
                            setTextSize(terminalTextSizePx)
                            setTypeface(terminalTypeface)
                            setTerminalViewClient(viewClient)
                            attachSession(session)
                            sessionClient.terminalView = this
                            viewClient.terminalView = this
                            setOnTouchListener { _, event ->
                                if (event.action == MotionEvent.ACTION_UP) {
                                    viewClient.focusAndShowKeyboard()
                                }
                                false
                            }
                            post { viewClient.focusAndShowKeyboard() }
                        }
                    },
                    update = { terminalView ->
                        terminalView.isFocusable = true
                        terminalView.isFocusableInTouchMode = true
                        terminalView.setTextSize(terminalTextSizePx)
                        terminalView.setTypeface(terminalTypeface)
                        terminalView.setTerminalViewClient(viewClient)
                        sessionClient.terminalView = terminalView
                        viewClient.terminalView = terminalView
                        terminalView.attachSession(session)
                        terminalView.onScreenUpdated()
                    },
                )
                if (finished) {
                    Text(
                        text = stringResource(R.string.workspace_terminal_exited),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
            TerminalExtraKeysBar(
                controlDown = controlDown,
                altDown = altDown,
                onControlToggle = { controlDown = !controlDown },
                onAltToggle = { altDown = !altDown },
                onSendText = { session.writeText(it) },
            )
        }
    }
}

@Composable
private fun TerminalExtraKeysBar(
    controlDown: Boolean,
    altDown: Boolean,
    onControlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onSendText: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalExtraKey("ESC") { onSendText("\u001B") }
        TerminalExtraKey("TAB") { onSendText("\t") }
        TerminalExtraKey("CTRL", selected = controlDown, onClick = onControlToggle)
        TerminalExtraKey("ALT", selected = altDown, onClick = onAltToggle)
        TerminalExtraKey("-") { onSendText("-") }
        TerminalExtraKey("/") { onSendText("/") }
        TerminalExtraKey("|") { onSendText("|") }
        TerminalExtraKey("←") { onSendText("\u001B[D") }
        TerminalExtraKey("↓") { onSendText("\u001B[B") }
        TerminalExtraKey("↑") { onSendText("\u001B[A") }
        TerminalExtraKey("→") { onSendText("\u001B[C") }
        TerminalExtraKey("HOME") { onSendText("\u001B[H") }
        TerminalExtraKey("END") { onSendText("\u001B[F") }
    }
}

@Composable
private fun TerminalExtraKey(
    label: String,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val haptics = rememberPremiumHaptics()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.85f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 300f),
        label = "terminalExtraKeyScale",
    )
    Text(
        text = label,
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                },
                shape = RoundedCornerShape(6.dp),
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    haptics.perform(HapticPattern.Pop)
                    onClick()
                },
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
        },
    )
}

private fun TerminalSession.writeText(text: String) {
    val bytes = text.toByteArray()
    write(bytes, 0, bytes.size)
}

private sealed interface TerminalPreparation {
    data class Ready(val bindMounts: List<me.rerere.rikkahub.workspace.SandboxBindMount>) : TerminalPreparation
    data object ROOTFS_MISSING : TerminalPreparation
    data object RUNTIME_MISSING : TerminalPreparation
}

private sealed interface TerminalSessionUiState {
    data object Loading : TerminalSessionUiState
    data object NotInstalled : TerminalSessionUiState
    data object Installing : TerminalSessionUiState
    data object RuntimeUnavailable : TerminalSessionUiState
    data object NotAvailable : TerminalSessionUiState
    data class Failed(val message: String) : TerminalSessionUiState
    data class Ready(val session: TerminalSession) : TerminalSessionUiState
}
