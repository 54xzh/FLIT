package me.rerere.rikkahub.data.interactive

import kotlinx.coroutines.*
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.dao.InteractiveComponentStateDao
import me.rerere.rikkahub.data.db.entity.InteractiveComponentStateEntity
import org.junit.Assert.*
import org.junit.Test

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
}
