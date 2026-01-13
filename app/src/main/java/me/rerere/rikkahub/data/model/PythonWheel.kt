/*
 * Python Wheel（.whl）依赖的本地清单模型，用于应用内导入与运行时加载。
 */

package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable

@Serializable
data class PythonWheelManifest(
    val version: Int = 1,
    val wheels: List<PythonWheel> = emptyList(),
)

@Serializable
data class PythonWheel(
    val id: String,
    val displayName: String,
    val packageName: String? = null,
    val packageVersion: String? = null,
    val sha256: String,
    val fileSizeBytes: Long? = null,
    val installedAt: Long,
    val enabled: Boolean = true,
    val sysPaths: List<String> = emptyList(),
    val hasNativeCode: Boolean = false,
)

