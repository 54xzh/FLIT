package me.rerere.rikkahub.service.interactive

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.QuickJSContext
import me.rerere.rikkahub.data.interactive.InteractiveRuntime
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** 没有应用权限的进程。看门狗与控制接口都不依赖可能陷入死循环的脚本线程。 */
class InteractiveScriptService : Service() {
    private val worker = HandlerThread("InteractiveQuickJS")
    private lateinit var handler: Handler
    private val watchdog = Executors.newSingleThreadScheduledExecutor()
    private val active = AtomicLong(0)
    private val binder = object : IInteractiveScriptService.Stub() {
        override fun execute(requestId: Long, code: String, function: String, model: String, args: String, callback: IInteractiveScriptCallback) {
            if (requestId == 0L || !active.compareAndSet(0, requestId)) {
                runCatching { callback.complete(requestId, "", "BUSY") }
                return
            }
            if (code.toByteArray().size > InteractiveRuntime.MAX_CODE_BYTES ||
                model.toByteArray().size > InteractiveRuntime.MAX_DATA_BYTES ||
                args.toByteArray().size > InteractiveRuntime.MAX_DATA_BYTES) {
                active.compareAndSet(requestId, 0)
                runCatching { callback.complete(requestId, "", "SIZE_LIMIT") }
                return
            }
            handler.post {
                val timeout = watchdog.schedule({
                    if (active.get() == requestId) Process.killProcess(Process.myPid())
                }, InteractiveRuntime.TIMEOUT_MS, TimeUnit.MILLISECONDS)
                var result = ""
                var failure = ""
                try {
                    QuickJSLoader.init()
                    val js = QuickJSContext.create()
                    try {
                        js.setMemoryLimit(16 * 1024 * 1024)
                        js.setMaxStackSize(512 * 1024)
                        // JSON 和代码分开传入；用户输入永远不会拼接成可执行源码。
                        js.globalObject.setProperty("__inputModel", model)
                        js.globalObject.setProperty("__inputArgs", args)
                        js.globalObject.setProperty("__handlerName", function)
                        result = js.evaluate("""
                            (() => {
                              const stringify = JSON.stringify.bind(JSON);
                              const parse = JSON.parse.bind(JSON);
                              const own = Object.prototype.hasOwnProperty.call.bind(Object.prototype.hasOwnProperty);
                              const input = {model: parse(__inputModel), args: parse(__inputArgs)};
                              const name = __handlerName;
                              const handlers = ($code);
                              if (!handlers || !own(handlers, name) || typeof handlers[name] !== 'function') throw Error('Missing handler');
                              const result = handlers[name](input);
                              if (!Array.isArray(result) || typeof result.then === 'function') throw Error('Expected synchronous modification list');
                              return stringify(result, (_, value) => {
                                if (typeof value === 'number' && !Number.isFinite(value)) throw Error('Invalid number');
                                if (typeof value === 'function' || typeof value === 'undefined' || typeof value === 'symbol') throw Error('Invalid result');
                                return value;
                              });
                            })()
                        """.trimIndent()) as? String ?: error("Invalid result")
                        require(result.toByteArray().size <= InteractiveRuntime.MAX_DATA_BYTES) { "Result exceeds limit" }
                    } finally { js.destroy() }
                } catch (e: Throwable) {
                    failure = "SCRIPT_ERROR"
                } finally {
                    active.compareAndSet(requestId, 0)
                    timeout.cancel(false)
                }
                runCatching { callback.complete(requestId, result.takeIf { failure.isEmpty() }.orEmpty(), failure) }
            }
        }

        override fun abort(requestId: Long) {
            if (active.get() == requestId) Process.killProcess(Process.myPid())
        }
    }

    override fun onCreate() {
        super.onCreate()
        worker.start()
        handler = Handler(worker.looper)
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onDestroy() {
        if (active.get() != 0L) Process.killProcess(Process.myPid())
        worker.quitSafely()
        watchdog.shutdownNow()
        super.onDestroy()
    }
}
