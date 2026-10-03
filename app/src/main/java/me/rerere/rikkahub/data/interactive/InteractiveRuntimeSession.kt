package me.rerere.rikkahub.data.interactive

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject

data class InteractiveRuntimeStatus(val busy: Boolean = false, val error: String? = null)

/** 所有调度在界面协程中串行进行；自动动作只保留最新待执行项。 */
class InteractiveRuntimeSession(
    private val runtime: InteractiveRuntime,
    private val scope: CoroutineScope,
    private val execute: suspend (String, () -> InteractiveScriptInput) -> InteractiveScriptOutput,
    private val model: () -> JsonObject,
    private val apply: (JsonObject) -> Unit,
    private val active: () -> Boolean,
    private val validate: (JsonObject) -> Unit,
) {
    private data class Request(val handler: String, val args: JsonObject, val watchIndex: Int? = null)
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val waiting = linkedSetOf<Int>()
    private val delays = mutableMapOf<Int, Job>()
    private val settledInputs = mutableMapOf<Int, List<kotlinx.serialization.json.JsonElement?>>()
    private var button: Request? = null
    private var running = false
    private var failed: Request? = null
    private val _status = MutableStateFlow(InteractiveRuntimeStatus())
    val status = _status.asStateFlow()

    init {
        scope.launch {
            for (signal in signals) {
                while (button != null || waiting.isNotEmpty()) {
                    if (!active() || _status.value.error != null) break
                    val request = button?.also { button = null } ?: waiting.first().let { index ->
                        waiting.remove(index)
                        Request(runtime.watch[index].handler, JsonObject(emptyMap()), index)
                    }
                    running = true
                    refresh()
                    try {
                        val output = execute(request.handler) {
                            if (!active()) throw CancellationException("Card no longer active")
                            val snapshot = model()
                            request.watchIndex?.let { index -> runtime.watch[index].paths.forEach {
                                require(interactivePathValue(snapshot, it) != null) { "Missing watched input" }
                            } }
                            InteractiveScriptInput(snapshot, request.args)
                        }
                        if (!active()) continue
                        if (model() != output.input.model) {
                            if (request.watchIndex != null) {
                                val index = request.watchIndex
                                if (index !in waiting && index !in delays) scheduleWatch(index)
                            }
                            else {
                                failed = request
                                waiting.clear()
                                delays.values.forEach { it.cancel() }
                                delays.clear()
                                _status.value = _status.value.copy(error = "STALE_INPUT")
                            }
                            continue
                        }
                        val updated = applyInteractiveScriptResult(output.input.model, output.result, runtime, request.watchIndex != null)
                        validate(updated)
                        if (updated != output.input.model) apply(updated)
                        request.watchIndex?.let { index ->
                            settledInputs[index] = runtime.watch[index].paths.map { interactivePathValue(updated, it) }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        failed = request
                        _status.value = _status.value.copy(error = (e as? InteractiveScriptException)?.reason ?: "SCRIPT_ERROR")
                        waiting.clear()
                        delays.values.forEach { it.cancel() }
                        delays.clear()
                    } finally {
                        running = false
                        refresh()
                    }
                }
            }
        }
    }

    private fun refresh() {
        _status.value = _status.value.copy(busy = running || button != null || waiting.isNotEmpty() || delays.isNotEmpty())
    }

    fun scheduleWatch(index: Int, immediate: Boolean = false) {
        if (!active() || _status.value.error != null) return
        delays.remove(index)?.cancel()
        waiting.remove(index)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (!immediate) delay(150)
            delays.remove(index)
            if (active()) {
                waiting.add(index)
                signals.trySend(Unit)
            }
            refresh()
        }
        delays[index] = job
        job.start()
        refresh()
    }

    fun runButton(handler: String, args: JsonObject): Boolean {
        if (!active() || _status.value.busy || _status.value.error != null) return false
        button = Request(handler, args)
        refresh()
        signals.trySend(Unit)
        return true
    }

    /** 直接核对输入，覆盖输入变化后观察协程尚未获得调度的短暂窗口。 */
    fun canSubmit(): Boolean {
        if (_status.value.busy || _status.value.error != null) return false
        val current = runCatching { model() }.getOrNull() ?: return false
        return runtime.watch.indices.all { index ->
            settledInputs[index] == runtime.watch[index].paths.map { interactivePathValue(current, it) }
        }
    }

    fun retry() {
        if (!active() || running) return
        _status.value = InteractiveRuntimeStatus()
        val request = failed
        failed = null
        waiting.clear()
        if (request != null && request.watchIndex == null) {
            button = request
            signals.trySend(Unit)
        }
        runtime.watch.indices.forEach { scheduleWatch(it, immediate = true) }
        refresh()
    }
}
