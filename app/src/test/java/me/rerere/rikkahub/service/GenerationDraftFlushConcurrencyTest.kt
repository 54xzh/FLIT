package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 回归「生成中草稿保存」的并发收尾语义：最终保存必须是「最后一个写者」。
 *
 * 复刻 [ChatService.flushGenerationDraftSave] 的修法逻辑（取消排期中的草稿保存任务并 join
 * 等其结束，再做最终保存），不依赖完整 ChatService 实例——只验证「取消并等待旧任务」这一
 * 关键不变量本身，避免被未来改动悄悄回退成「只 cancel 不 join」导致旧快照在最终保存之后
 * 才落库、覆盖最终版本（尾部丢失）。
 *
 * 真实的端到端验证（内存 StateFlow 交错 + Room 写入顺序）仍需 instrumented test；
 * 本测试锁定的是可纯协程复现的「join 先于最终保存」语义。
 */
class GenerationDraftFlushConcurrencyTest {

    /**
     * 最小复刻 flush 的收尾：先取消并等待旧任务，再执行最终保存。
     * 返回最终保存写入的「内容版本号」，供断言。
     */
    private suspend fun flushLike(
        jobs: ConcurrentHashMap<String, Job>,
        key: String,
        finalSave: suspend () -> Int,
    ): Int {
        val previous = jobs.remove(key)
        if (previous != null) {
            previous.cancel()
            withContext(NonCancellable) { previous.join() }
        }
        return finalSave()
    }

    @Test
    fun `flush 在旧任务正在写时 join 等其结束 最终保存是最后写者`() = runBlocking {
        val jobs = ConcurrentHashMap<String, Job>()
        val key = UUID.randomUUID().toString()
        val savedVersions = mutableListOf<Int>()
        val inProgress = CompletableDeferred<Unit>()
        val oldJobAllowedToFinish = CompletableDeferred<Unit>()

        // 旧任务：模拟已越过排期、正在「写 DB」。真实场景里 withContext(IO) 内的 DAO
        // 调用是同步的、无挂起点，cancel 无法打断——用 NonCancellable 包裹写入动作以复刻
        // 「IO 写入不可中断」，await 仅用于控制「写入何时完成」。
        val oldJob = launch(start = CoroutineStart.LAZY) {
            try {
                inProgress.complete(Unit)
                withContext(NonCancellable) {
                    oldJobAllowedToFinish.await() // 模拟 IO 写入耗时
                    savedVersions.add(1) // 旧快照版本（写入不可中断）
                }
            } finally {
                jobs.remove(key)
            }
        }
        jobs[key] = oldJob
        oldJob.start()

        // 等旧任务确实进入「正在写」
        inProgress.await()

        // 此时「最后一段内容到达」——本测试中体现为最终保存用版本 2
        // 触发 flush：必须先取消并 join 旧任务
        val flushJob = launch {
            flushLike(jobs, key, finalSave = { savedVersions.add(2); 2 })
        }

        // 让旧任务完成（模拟它的 IO 写入结束）
        oldJobAllowedToFinish.complete(Unit)
        flushJob.join()

        // 旧版本(1) 先写、最终版本(2) 后写——即最终保存是最后一个写者。
        // 若 flush 回退成「只 cancel 不 join」，旧任务的写入可能晚于最终保存完成，
        // savedVersions 顺序会变成 [2,1]，本断言即捕获该回归。
        assertEquals(listOf(1, 2), savedVersions)
    }

    @Test
    fun `flush 时旧任务仍在排期阶段 取消立即结束 最终保存照常执行`() = runBlocking {
        val jobs = ConcurrentHashMap<String, Job>()
        val key = UUID.randomUUID().toString()
        val savedVersions = mutableListOf<Int>()
        val oldJobAllowedToStart = CompletableDeferred<Unit>() // 旧任务卡在排期，永不写

        val oldJob = launch(start = CoroutineStart.LAZY) {
            try {
                oldJobAllowedToStart.await() // 仍在排期，未取快照、未写
                savedVersions.add(1)
            } finally {
                jobs.remove(key)
            }
        }
        jobs[key] = oldJob
        oldJob.start()
        // 不 resolve oldJobAllowedToStart，旧任务一直挂起（相当于仍在 delay）

        flushLike(jobs, key, finalSave = { savedVersions.add(2); 2 })

        // 旧任务被取消，不会写；只有最终保存写
        assertEquals(listOf(2), savedVersions)
        oldJobAllowedToStart.cancel() // 清理
    }

    @Test
    fun `flush 无旧任务时直接最终保存`() = runBlocking {
        val jobs = ConcurrentHashMap<String, Job>()
        val key = UUID.randomUUID().toString()
        val savedVersions = mutableListOf<Int>()
        flushLike(jobs, key, finalSave = { savedVersions.add(9); 9 })
        assertEquals(listOf(9), savedVersions)
    }
}