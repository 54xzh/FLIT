package me.rerere.rikkahub.data.interactive

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import me.rerere.rikkahub.service.interactive.IInteractiveScriptCallback
import me.rerere.rikkahub.service.interactive.IInteractiveScriptService
import me.rerere.rikkahub.service.interactive.InteractiveScriptService
import java.util.concurrent.atomic.AtomicLong

data class InteractiveScriptInput(val model: JsonObject, val args: JsonObject)
data class InteractiveScriptOutput(val input: InteractiveScriptInput, val result: String)
class InteractiveScriptException(val reason: String) : Exception(reason)

/** 全局互斥；取得执行位置之后才从卡片读取数据，避免排队期间的旧快照。 */
class InteractiveScriptRunner(context: Context) {
    private val context = context.applicationContext
    companion object {
        private val queue = Mutex()
        private val ids = AtomicLong(0)
    }

    suspend fun execute(runtime: InteractiveRuntime, handler: String, input: () -> InteractiveScriptInput): InteractiveScriptOutput = queue.withLock {
        val requestId = ids.incrementAndGet()
        val bound = CompletableDeferred<IInteractiveScriptService>()
        val response = CompletableDeferred<String>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                bound.complete(IInteractiveScriptService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) {
                response.completeExceptionally(InteractiveScriptException("PROCESS_EXITED"))
            }
            override fun onBindingDied(name: ComponentName) {
                bound.completeExceptionally(InteractiveScriptException("PROCESS_EXITED"))
                response.completeExceptionally(InteractiveScriptException("PROCESS_EXITED"))
            }
            override fun onNullBinding(name: ComponentName) { bound.completeExceptionally(InteractiveScriptException("START_FAILED")) }
        }
        var service: IInteractiveScriptService? = null
        var registered = false
        try {
            registered = context.bindService(Intent(context, InteractiveScriptService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!registered) throw InteractiveScriptException("START_FAILED")
            val connected = withTimeout(5_000) { bound.await() }
            service = connected
            val snapshot = input()
            validateInteractiveRuntimeData(snapshot.model)
            require(snapshot.args.toString().toByteArray().size <= InteractiveRuntime.MAX_DATA_BYTES) { "Arguments exceed the size limit" }
            val callback = object : IInteractiveScriptCallback.Stub() {
                override fun complete(id: Long, result: String, error: String) {
                    if (id != requestId) return
                    if (error.isEmpty()) response.complete(result)
                    else response.completeExceptionally(InteractiveScriptException(error))
                }
            }
            withContext(Dispatchers.IO) {
                connected.execute(requestId, runtime.code, handler, snapshot.model.toString(), snapshot.args.toString(), callback)
            }
            InteractiveScriptOutput(snapshot, withTimeout(InteractiveRuntime.TIMEOUT_MS + 1_000) { response.await() })
        } catch (e: TimeoutCancellationException) {
            throw InteractiveScriptException("TIMEOUT")
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                if (!response.isCompleted && service != null) {
                    runCatching { service?.abort(requestId) }
                    // 等旧执行结束后才释放全局位置，下一张卡片不会绑定到仍在退出的服务。
                    withTimeoutOrNull(1_000) { runCatching { response.await() } }
                }
                if (registered) runCatching { context.unbindService(connection) }
            }
        }
    }
}
