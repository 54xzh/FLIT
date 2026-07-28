package me.rerere.rikkahub.workspace

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 沙盒维护操作和长驻进程之间的中立协调点。
 * 工作区仓库不需要知道 MCP，进程所有者也不需要反向依赖工作区仓库。
 */
class SandboxProcessCoordinator {
    private val owners = ConcurrentHashMap<String, SandboxProcessOwner>()
    private val blockedWorkspaces = ConcurrentHashMap.newKeySet<String>()
    private val globalMaintenanceCount = AtomicInteger(0)

    fun register(ownerId: String, owner: SandboxProcessOwner) {
        owners[ownerId] = owner
    }

    fun unregister(ownerId: String) {
        owners.remove(ownerId)
    }

    suspend fun stopWorkspace(workspaceId: String) {
        owners.values.toList().forEach { it.stopWorkspace(workspaceId) }
    }

    suspend fun stopAll() {
        owners.values.toList().forEach { it.stopAll() }
    }

    fun isStartAllowed(workspaceId: String): Boolean =
        globalMaintenanceCount.get() == 0 && workspaceId !in blockedWorkspaces

    suspend fun <T> withWorkspaceMaintenance(workspaceId: String, block: suspend () -> T): T {
        check(blockedWorkspaces.add(workspaceId)) { "Workspace maintenance is already in progress" }
        try {
            stopWorkspace(workspaceId)
            return block()
        } finally {
            blockedWorkspaces.remove(workspaceId)
        }
    }

    suspend fun <T> withGlobalMaintenance(block: suspend () -> T): T {
        globalMaintenanceCount.incrementAndGet()
        try {
            stopAll()
            return block()
        } finally {
            globalMaintenanceCount.decrementAndGet()
        }
    }
}

interface SandboxProcessOwner {
    suspend fun stopWorkspace(workspaceId: String)
    suspend fun stopAll()
}
