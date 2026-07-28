package me.rerere.rikkahub.data.ai.mcp

import me.rerere.rikkahub.data.datastore.Settings
import kotlin.uuid.Uuid

internal fun Settings.withMcpSelectionRemoved(serverId: Uuid): Settings = copy(
    assistants = assistants.map { it.copy(mcpServers = it.mcpServers - serverId) },
    groupChatTemplates = groupChatTemplates.map { template ->
        template.copy(
            seats = template.seats.map { seat ->
                seat.copy(
                    overrides = seat.overrides.copy(
                        mcpServerIds = seat.overrides.mcpServerIds - serverId,
                    )
                )
            }
        )
    },
)
