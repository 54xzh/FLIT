package me.rerere.rikkahub.utils

import android.os.Build

/**
 * 设备 SoC 信息读取工具。
 *
 * Android 没有暴露 SoC 的正式型号名（如 "骁龙 8 Gen 2"），
 * 通常只能拿到平台代号（如 "kalama"、"taro"）或部件号（如 "sm8550"、"mt6983"）。
 * 这里汇总多个来源，供开发者页面展示，并为模糊开关默认值提供性能判定依据。
 */
object SoCInfo {

    /**
     * SoC 厂商，例如 "Qualcomm" / "MediaTek" / "Hisilicon"。
     * API 31+ 提供；更早或部分 OEM 未设置时为空/"unknown"。
     * 进程内恒定, 用 lazy 缓存避免重复读取。
     */
    val manufacturer: String by lazy { Build.SOC_MANUFACTURER.ifBlank { "unknown" } }

    /**
     * SoC 型号（实际是平台代号或部件号），例如 "kalama"、"lahaina"、"mt6895"、"sm8550"。
     * API 31+ 提供；部分 OEM 未设置时为空/"unknown"。
     * 进程内恒定, 用 lazy 缓存避免重复读取。
     */
    val model: String by lazy { Build.SOC_MODEL.ifBlank { "unknown" } }

    /**
     * 硬件层字符串，例如 "qcom"、"mt6895"。
     * 比 [model] 更早就可用，但粒度较粗。
     * 进程内恒定, 用 lazy 缓存避免重复读取。
     */
    val hardware: String by lazy { Build.HARDWARE.ifBlank { "unknown" } }

    /**
     * 通过 ro.board.platform 系统属性读取的平台代号。
     * 国产 ROM 上有时比 [model] 更全。
     * 进程内恒定, 用 lazy 缓存避免重复反射。
     */
    val boardPlatform: String by lazy { getSystemProperty("ro.board.platform") ?: "unknown" }

    /**
     * 通过 ro.hardware.chipname 等系统属性读取的芯片名，可能容错过率更高。
     * 进程内恒定, 用 lazy 缓存避免重复反射。
     */
    val chipName: String by lazy {
        getSystemProperty("ro.hardware.chipname")
            ?: getSystemProperty("ro.soc.model")
            ?: "unknown"
    }

    /**
     * 是否成功读到任何可用的 SoC 代号信息。
     * 若所有来源都是 unknown/空，说明本机无法用于白名单判定，需走兜底逻辑。
     * 进程内恒定, 用 lazy 缓存。
     */
    val hasAnyInfo: Boolean by lazy {
        listOf(manufacturer, model, boardPlatform, chipName, hardware)
            .any { !it.equals("unknown", ignoreCase = true) && it.isNotBlank() }
    }

    /**
     * 性能档位判定结果，用于描述本机 SoC 是否足够支撑重度图形效果（如模糊）。
     *
     * 判定规则（命中任一即视为 [Tier.SUFFICIENT]，否则按是否读到信息分档）：
     * - 高通：部件号以 SM8 / SM7 开头（骁龙 8 系、7 系）→ 够格。
     * - 联发科：平台代号以 mt69 开头（天玑 9000 系旗舰）→ 全够格；
     *   mt68 开头且后两位 >= 95（mt6895+，天玑 8200/8300/8400 高端）→ 够格。
     * - Google Tensor：gs101 / gs201 / gs301 或含 zuma / zumapro → 够格。
     * - 三星 Exynos 旗舰：s5e99 开头（Exynos 2200/2400 等）→ 够格。
     * - XRING（玄戒）：关键词 xring / o1_asic / o3_asic（不区分大小写）命中任一 → 够格。
     *
     * 未读到任何可用信息时返回 [Tier.UNKNOWN]，调用方可据此走兜底（如系统低内存判定）。
     */
    val tier: Tier by lazy {
        val candidates = listOf(
            model,
            boardPlatform,
            chipName,
            hardware,
        ).map { it.lowercase() }

        // 任意一个候选命中「够格」特征即返回。
        for (code in candidates) {
            if (code.isBlank() || code == "unknown") continue

            // —— 高通 ——
            // 部件号形式：SM8550 / SM8650 / SM8750 / SM7475 ...
            if (code.startsWith("sm8") || code.startsWith("sm7")) return@lazy Tier.SUFFICIENT

            // —— 联发科 ——
            // 规则：mt69 开头全取（天玑 9000 系旗舰）；
            //       mt68 开头需后两位 >= 95（即 mt6895+，覆盖天玑 8200/8300/8400 高端）。
            if (code.startsWith("mt69")) return@lazy Tier.SUFFICIENT
            if (code.startsWith("mt68") && code.length >= 6) {
                val tail = code.substring(4, 6)
                if (tail.toIntOrNull()?.let { it >= 95 } == true) return@lazy Tier.SUFFICIENT
            }

            // —— Google Tensor ——
            // Pixel 6/7/8 使用 gs101/gs201/gs301；Pixel 9 系列使用 zuma / zumapro。
            if (code.startsWith("gs") || code.contains("zuma")) return@lazy Tier.SUFFICIENT

            // —— 三星 Exynos 旗舰 ——
            // s5e9925 = Exynos 2200, s5e9945 = Exynos 2400 等。
            if (code.startsWith("s5e99")) return@lazy Tier.SUFFICIENT

            // —— XRING（玄戒）全系 ——
            // 关键词不区分大小写，命中任一即可：xring / o1_asic / o3_asic
            if (XRING_KEYWORDS.any { code.contains(it) }) return@lazy Tier.SUFFICIENT
        }

        // 读到了信息但都不在白名单 → 视为不足够，建议默认关。
        if (hasAnyInfo) Tier.INSUFFICIENT_DEFAULT_OFF else Tier.UNKNOWN
    }

    /**
     * 是否建议默认开启重度图形效果（如模糊）。
     * 仅当明确判定为 [Tier.SUFFICIENT] 时为 true；[Tier.UNKNOWN] 时保守返回 false。
     */
    val suggestsHighPerformance: Boolean
        get() = tier == Tier.SUFFICIENT

    enum class Tier {
        /** 性能足够，可默认开启重度图形效果。 */
        SUFFICIENT,

        /** 读到了 SoC 信息但不在白名单，建议默认关闭。 */
        INSUFFICIENT_DEFAULT_OFF,

        /** 未读到任何可用 SoC 信息，调用方应走兜底判定（如系统低内存标志）。 */
        UNKNOWN,
    }

    /**
     * XRING（玄戒）芯片识别关键词，不区分大小写，命中任一即视为够格。
     */
    private val XRING_KEYWORDS = setOf(
        "xring",
        "o1_asic",
        "o3_asic",
    )

    /**
     * 反射读取系统属性，避免直接依赖隐藏 API。
     * 仅用于只读展示，不写入任何属性。
     */
    private fun getSystemProperty(name: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getMethod("get", String::class.java)
        (method.invoke(null, name) as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()
}