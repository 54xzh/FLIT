package me.rerere.rikkahub.data.ai.tools

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.LinkedHashMap

/**
 * 搜索子代理实时进度仓库。
 *
 * 按 [toolCallId] 索引，每个条目持有一个 [StateFlow]<[SearchAgentProgress]?>。
 * 执行中的 runner 往里推 step；UI（步骤卡 / 主页时间线）按 toolCallId 订阅，
 * Compose 随 StateFlow 自动刷新。
 *
 * LRU 保留最近 [CAPACITY] 个条目，超出淘汰最旧，既稳住执行中观察、又防内存泄漏。
 * 条目是临时态：执行完成/被淘汰后，UI 回落读 metadata（[parseSearchAgentStepsFromMetadata]）。
 */
class SearchAgentProgressStore {
    private data class Entry(
        val state: MutableStateFlow<SearchAgentProgress?>,
    )

    private val lock = Any()
    // accessOrder=true：每次 get/put 都把命中条目移到末尾，最旧在前
    private val map = object : LinkedHashMap<String, Entry>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>): Boolean {
            return size > CAPACITY
        }
    }

    /** 取（或按需创建）某个 toolCallId 的进度 StateFlow。 */
    fun stateOf(toolCallId: String): StateFlow<SearchAgentProgress?> {
        synchronized(lock) {
            val entry = map.getOrPut(toolCallId) { Entry(MutableStateFlow(null)) }
            return entry.state.asStateFlow()
        }
    }

    /** runner 推送一条步骤（或初始化 task）。 */
    fun update(toolCallId: String, transform: (SearchAgentProgress?) -> SearchAgentProgress?) {
        synchronized(lock) {
            val entry = map.getOrPut(toolCallId) { Entry(MutableStateFlow(null)) }
            val current = entry.state.value
            val next = transform(current)
            entry.state.value = next
        }
    }

    /** 标记完成。 */
    fun finish(toolCallId: String) {
        update(toolCallId) { it?.copy(finished = true) }
    }

    companion object {
        private const val CAPACITY = 16
    }
}

/**
 * 搜索子代理执行进度（临时态，仅执行期/近期存于 store）。
 *
 * @param task 主模型派发的任务文本（第 0 步），execute 尚未启动时为 null。
 * @param steps 已产生的步骤列表（含 task / reasoning / 工具调用 / error / final）。
 * @param finished 是否已完成（含成功 / 超时 / 异常）。
 */
data class SearchAgentProgress(
    val task: String? = null,
    val steps: List<SearchAgentStep> = emptyList(),
    val finished: Boolean = false,
)
