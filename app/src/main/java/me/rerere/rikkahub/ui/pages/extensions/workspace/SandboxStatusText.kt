package me.rerere.rikkahub.ui.pages.extensions.workspace

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.db.entity.SandboxRootfsStatus

@Composable
fun sandboxStatusText(status: SandboxRootfsStatus?): String = stringResource(
    when (status) {
        SandboxRootfsStatus.INSTALLING -> R.string.workspace_sandbox_status_installing
        SandboxRootfsStatus.READY -> R.string.workspace_sandbox_status_ready
        SandboxRootfsStatus.BROKEN -> R.string.workspace_sandbox_status_broken
        SandboxRootfsStatus.DISABLED, null -> R.string.workspace_sandbox_status_not_installed
    }
)
