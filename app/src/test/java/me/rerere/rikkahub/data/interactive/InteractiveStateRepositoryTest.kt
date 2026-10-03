package me.rerere.rikkahub.data.interactive

import kotlinx.coroutines.*
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.dao.InteractiveComponentStateDao
import me.rerere.rikkahub.data.db.entity.InteractiveComponentStateEntity
import org.junit.Assert.*
import org.junit.Test
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.Uuid

class InteractiveStateRepositoryTest {
    private class MemoryDao : InteractiveComponentStateDao {
        var state: InteractiveComponentStateEntity? = null
        var writes = 0
        override suspend fun get(conversationId: String, messageId: String, partIndex: Int, blockOffset: Int) = state
        override suspend fun list(conversationId: String) = listOfNotNull(state).filter { it.conversationId == conversationId }
        override suspend fun put(state: InteractiveComponentStateEntity) { this.state = state; writes++ }
    }
    private fun initial() = InteractiveComponentStateEntity("conversation", "message", 0, 18, "hash", "card", "{}")

    @Test fun `drafts restore and changed descriptions start from fresh data`() = runBlocking {
        val scope = AppScope()
        try {
            val dao = MemoryDao()
            val repo = InteractiveStateRepository(dao, scope)
            val initial = initial()
            repo.load(initial, false)
            repo.update(initial.copy(dataModel = "{\"name\":\"Alice\"}"), false)
            repo.flushAll()
            val reloaded = InteractiveStateRepository(dao, scope)
            assertEquals("{\"name\":\"Alice\"}", reloaded.load(initial, false).dataModel)
            assertEquals("{}", reloaded.load(initial.copy(fingerprint = "new"), false).dataModel)
        } finally { scope.cancel() }
    }

    @Test fun `delayed drafts cannot overwrite accepted submission`() = runBlocking {
        val scope = AppScope()
        try {
            val dao = MemoryDao()
            val repo = InteractiveStateRepository(dao, scope)
            val initial = initial()
            repo.load(initial, false)
            repo.update(initial.copy(dataModel = "{\"value\":1}"), false)
            repo.update(initial.copy(dataModel = "{\"value\":2}", submitted = true, submissionId = "one"), false, true)
            repo.update(initial.copy(dataModel = "{\"value\":3}"), false)
            repo.flushAll()
            assertEquals("one", dao.state?.submissionId)
            assertEquals("{\"value\":2}", dao.state?.dataModel)
            assertTrue(dao.state?.submitted == true)
        } finally { scope.cancel() }
    }

    @Test fun `temporary components never reach database even during backup flush`() = runBlocking {
        val scope = AppScope()
        try {
            val dao = MemoryDao()
            val repo = InteractiveStateRepository(dao, scope)
            val initial = initial()
            repo.load(initial, true)
            repo.update(initial.copy(dataModel = "{\"draft\":true}"), true)
            repo.flush(initial, true)
            repo.flushAll()
            assertEquals(0, dao.writes)
            assertEquals("{\"draft\":true}", repo.load(initial, true).dataModel)
            repo.forgetConversation(initial.conversationId)
            assertEquals("{}", repo.load(initial, true).dataModel)
        } finally { scope.cancel() }
    }

    @Test fun `script edits retain immutable submission snapshot and submission identity`() = runBlocking {
        val scope = AppScope()
        try {
            val dao = MemoryDao()
            val repo = InteractiveStateRepository(dao, scope)
            val initial = initial()
            repo.load(initial, false)
            repo.update(initial.copy(dataModel = "{\"value\":2}", submitted = true, submissionId = "one"), false, true)
            repo.update(initial.copy(dataModel = "{\"value\":3}"), false, allowSubmittedEditing = true)
            repo.flushAll()
            assertEquals("{\"value\":3}", dao.state?.dataModel)
            assertEquals("{\"value\":2}", dao.state?.submittedDataModel)
            assertEquals("one", dao.state?.submissionId)
            assertTrue(dao.state?.submitted == true)
            val restored = InteractiveStateRepository(dao, scope).load(initial, false)
            assertEquals(dao.state, restored)
        } finally { scope.cancel() }
    }

    @Test fun `branches retain local data and submission status follows included events`() = runBlocking {
        val scope = AppScope()
        try {
            val dao = MemoryDao()
            val repo = InteractiveStateRepository(dao, scope)
            val message = UIMessage(role = MessageRole.ASSISTANT, parts = emptyList())
            val state = initial().copy(messageId = message.id.toString(), dataModel = "{\"value\":3}",
                submitted = true, submissionId = "one", submittedDataModel = "{\"value\":2}")
            repo.load(state, true)
            repo.copyToBranch(state.conversationId, "without-event", listOf(message), true)
            assertEquals(state.dataModel, dao.state?.dataModel)
            assertFalse(dao.state?.submitted == true)
            assertNull(dao.state?.submittedDataModel)
            val event = buildJsonObject {
                put("sourceMessageId", message.id.toString()); put("partIndex", 0)
                put("blockOffset", 18); put("fingerprint", "hash")
            }
            val submission = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("Submitted",
                metadata = buildJsonObject { put(INTERACTIVE_ACTION_METADATA, event) })))
            repo.copyToBranch(state.conversationId, "with-event", listOf(message, submission), true)
            assertTrue(dao.state?.submitted == true)
            assertEquals(state.submittedDataModel, dao.state?.submittedDataModel)
            assertEquals("one", dao.state?.submissionId)
        } finally { scope.cancel() }
    }
}
