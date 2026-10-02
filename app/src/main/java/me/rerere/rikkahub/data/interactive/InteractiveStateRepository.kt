package me.rerere.rikkahub.data.interactive

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.dao.InteractiveComponentStateDao
import me.rerere.rikkahub.data.db.entity.InteractiveComponentStateEntity
import java.util.concurrent.ConcurrentHashMap

class InteractiveStateRepository(private val dao: InteractiveComponentStateDao, private val scope: AppScope) {
    private val states = ConcurrentHashMap<String, MutableStateFlow<InteractiveComponentStateEntity?>>()
    private val writes = ConcurrentHashMap<String, Job>()
    private val writeLock = Mutex()
    private val persistentKeys = ConcurrentHashMap.newKeySet<String>()

    private fun key(state: InteractiveComponentStateEntity) = "${state.conversationId}/${state.messageId}/${state.partIndex}/${state.blockOffset}"

    fun observe(initial: InteractiveComponentStateEntity): StateFlow<InteractiveComponentStateEntity?> =
        states.getOrPut(key(initial)) { MutableStateFlow(null) }

    suspend fun load(initial: InteractiveComponentStateEntity, temporary: Boolean): InteractiveComponentStateEntity {
        val flow = states.getOrPut(key(initial)) { MutableStateFlow(null) }
        if (!temporary) persistentKeys.add(key(initial))
        return writeLock.withLock {
            val cached = flow.value
            val stored = cached ?: if (temporary) null else withContext(Dispatchers.IO) {
                dao.get(initial.conversationId, initial.messageId, initial.partIndex, initial.blockOffset)
            }
            val value = stored?.takeIf { it.fingerprint == initial.fingerprint } ?: initial
            flow.value = value
            value
        }
    }

    fun update(state: InteractiveComponentStateEntity, temporary: Boolean, immediate: Boolean = false) {
        val stateKey = key(state)
        val flow = states.getOrPut(stateKey) { MutableStateFlow(null) }
        synchronized(flow) {
            val current = flow.value
            // 延迟草稿不能覆盖已提交的快照。
            if (current?.fingerprint == state.fingerprint && current.submitted && !state.submitted) return
            flow.value = state
        }
        writes.remove(stateKey)?.cancel()
        if (temporary) return
        persistentKeys.add(stateKey)
        writes[stateKey] = scope.launch(Dispatchers.IO) {
            if (!immediate) delay(400)
            try {
                writeLock.withLock { flow.value?.let { dao.put(it) } }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.w("InteractiveState", "Could not save component state", e) }

        }
    }

    fun flush(initial: InteractiveComponentStateEntity, temporary: Boolean) {
        states[key(initial)]?.value?.let { update(it, temporary, immediate = true) }
    }

    suspend fun flushAll() = withContext(Dispatchers.IO) {
        writeLock.withLock { persistentKeys.forEach { states[it]?.value?.let { value -> dao.put(value) } } }
    }

    suspend fun copyToBranch(from: String, to: String, messages: List<me.rerere.ai.ui.UIMessage>, temporarySource: Boolean) = withContext(Dispatchers.IO) {
        writeLock.withLock {
            val persisted = if (temporarySource) emptyList() else dao.list(from)
            val cached = states.values.mapNotNull { it.value }.filter { it.conversationId == from }
            val latest = (persisted + cached).associateBy { "${it.messageId}/${it.partIndex}/${it.blockOffset}" }
            val ids = messages.map { it.id.toString() }.toSet()
            latest.values.filter { it.messageId in ids }.forEach { state ->
                val submitted = interactiveSubmissionExists(messages, state.messageId, state.partIndex, state.blockOffset, state.fingerprint)
                val copy = state.copy(conversationId = to, submitted = submitted, submissionId = state.submissionId.takeIf { submitted })
                dao.put(copy)
            }
        }
    }

    fun invalidatePersistentState() {
        persistentKeys.toList().forEach { key ->
            writes.remove(key)?.cancel()
            states.remove(key)?.value = null
        }
        persistentKeys.clear()
    }

    fun forgetConversation(conversationId: String) {
        val prefix = "$conversationId/"
        states.keys.filter { it.startsWith(prefix) }.forEach { states.remove(it); writes.remove(it)?.cancel(); persistentKeys.remove(it) }
    }
}
