package me.rerere.rikkahub.data.ai.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.GroupChatSeat
import me.rerere.rikkahub.data.model.GroupChatSeatOverrides
import me.rerere.rikkahub.data.model.GroupChatTemplate

class McpAvailabilityTest {
    private val remote = McpServerConfig.SseTransportServer(
        commonOptions = McpCommonOptions(
            name = "Remote",
            tools = listOf(McpTool(name = "search")),
        ),
        url = "https://example.com/sse",
    )
    private val stdioA = McpServerConfig.StdioServer(
        commonOptions = McpCommonOptions(
            name = "Files A",
            tools = listOf(McpTool(name = "search"), McpTool(name = "write")),
        ),
        workspaceId = "a",
        command = "server-a",
    )
    private val stdioB = McpServerConfig.StdioServer(
        commonOptions = McpCommonOptions(
            name = "Files B",
            tools = listOf(McpTool(name = "read")),
        ),
        workspaceId = "b",
        command = "server-b",
    )

    @Test
    fun `remote and stdio visibility follows complete workspace matrix`() {
        val selected = setOf(remote.id, stdioA.id, stdioB.id)

        assertTrue(isMcpServerAvailable(remote, selected, null))
        assertTrue(isMcpServerAvailable(remote, selected, "b"))
        assertTrue(isMcpServerAvailable(stdioA, selected, "a"))
        assertFalse(isMcpServerAvailable(stdioA, selected, null))
        assertFalse(isMcpServerAvailable(stdioA, selected, "b"))
        assertFalse(isMcpServerAvailable(stdioB, selected - stdioB.id, "b"))
    }

    @Test
    fun `assistant keeps explicit selections while switching workspaces`() {
        val selected = setOf(stdioA.id, stdioB.id)

        assertEquals(listOf(stdioA.id), visibleSelectedServers(selected, "a"))
        assertEquals(listOf(stdioB.id), visibleSelectedServers(selected, "b"))
        assertEquals(setOf(stdioA.id, stdioB.id), selected)
    }

    @Test
    fun `unmatched stdio is excluded and duplicate tools route by server id`() {
        val selected = setOf(remote.id, stdioA.id)

        assertTrue(resolveMcpTools(listOf(remote, stdioA), selected, "b").all { it.serverId == remote.id })

        val tools = resolveMcpTools(listOf(remote, stdioA), selected, "a")
        val searches = tools.filter { it.originalName == "search" }
        assertEquals(2, searches.size)
        assertEquals(setOf(remote.id, stdioA.id), searches.mapTo(mutableSetOf()) { it.serverId })
        assertEquals(2, searches.map { it.exposedName }.distinct().size)
        assertTrue(searches.all { it.exposedName.endsWith("_search") })

        val stdioSearch = stdioA.commonOptions.tools.first { it.name == "search" }
        assertFalse(
            isMcpInvocationAvailable(
                server = stdioA,
                tool = stdioSearch,
                selectedServerIds = selected,
                effectiveWorkspaceId = "b",
                expectedRuntimeScope = stdioA.runtimeScope(),
            )
        )
        assertFalse(
            isMcpInvocationAvailable(
                server = remote,
                tool = remote.commonOptions.tools.single(),
                selectedServerIds = selected,
                effectiveWorkspaceId = "a",
                expectedRuntimeScope = stdioA.runtimeScope(),
            )
        )
    }

    @Test
    fun `transport or bound workspace change requires fresh selection`() {
        assertTrue(hasMcpRuntimeScopeChanged(stdioA, stdioA.copy(workspaceId = "b")))
        assertTrue(
            hasMcpRuntimeScopeChanged(
                stdioA,
                McpServerConfig.SseTransportServer(
                    id = stdioA.id,
                    commonOptions = stdioA.commonOptions,
                ),
            )
        )
        assertFalse(hasMcpRuntimeScopeChanged(stdioA, stdioA.copy(command = "new-command")))
        assertTrue(hasStdioLaunchChanged(stdioA, stdioA.copy(command = "new-command")))
        assertFalse(hasStdioLaunchChanged(stdioA, stdioA.copy(startupTimeoutSeconds = 90)))
        assertEquals(stdioA.runtimeScope(), stdioA.copy(command = "new-command").runtimeScope())
        assertFalse(stdioA.runtimeScope() == remote.runtimeScope())
    }

    @Test
    fun `badge effectiveness requires at least one enabled tool`() {
        val noTools = stdioA.copy(commonOptions = stdioA.commonOptions.copy(tools = emptyList()))
        val disabledTools = stdioA.copy(
            commonOptions = stdioA.commonOptions.copy(
                tools = stdioA.commonOptions.tools.map { it.copy(enable = false) },
            )
        )

        assertFalse(isMcpServerEffective(noTools, setOf(noTools.id), "a"))
        assertFalse(isMcpServerEffective(disabledTools, setOf(disabledTools.id), "a"))
        assertTrue(isMcpServerEffective(stdioA, setOf(stdioA.id), "a"))
    }

    @Test
    fun `selection delta merges concurrent changes into latest assistant`() {
        val first = Uuid.random()
        val second = Uuid.random()
        val concurrent = Uuid.random()

        val afterFirst = applyMcpSelectionDelta(
            latestSelection = setOf(concurrent),
            displayedSelection = emptySet(),
            requestedSelection = setOf(first),
        )
        val afterSecond = applyMcpSelectionDelta(
            latestSelection = afterFirst,
            displayedSelection = emptySet(),
            requestedSelection = setOf(second),
        )

        assertEquals(setOf(concurrent, first, second), afterSecond)
        assertEquals(
            setOf(concurrent, second),
            applyMcpSelectionDelta(afterSecond, setOf(first, second), setOf(second)),
        )
    }

    @Test
    fun `scope change cleanup removes assistant and group seat selections`() {
        val assistant = Assistant(mcpServers = setOf(stdioA.id, remote.id))
        val settings = Settings(
            assistants = listOf(assistant),
            groupChatTemplates = listOf(
                GroupChatTemplate(
                    seats = listOf(
                        GroupChatSeat(
                            assistantId = assistant.id,
                            overrides = GroupChatSeatOverrides(
                                mcpServerIds = setOf(stdioA.id, remote.id),
                            ),
                        )
                    )
                )
            ),
        )

        val cleaned = settings.withMcpSelectionRemoved(stdioA.id)

        assertEquals(setOf(remote.id), cleaned.assistants.single().mcpServers)
        assertEquals(
            setOf(remote.id),
            cleaned.groupChatTemplates.single().seats.single().overrides.mcpServerIds,
        )
    }

    private fun visibleSelectedServers(selected: Set<Uuid>, workspaceId: String): List<Uuid> =
        listOf(stdioA, stdioB)
            .filter { isMcpServerAvailable(it, selected, workspaceId) }
            .map { it.id }
}
