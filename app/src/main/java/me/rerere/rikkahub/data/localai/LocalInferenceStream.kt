package me.rerere.rikkahub.data.localai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** Adapts a non-suspending native callback without losing chunks or freeing a live handle. */
internal fun localInferenceStream(
    start: (onEvent: (LocalInferenceEvent) -> Unit, onComplete: (Throwable?) -> Unit) -> Unit,
    cancel: () -> Unit,
    close: () -> Unit,
): Flow<LocalInferenceEvent> = flow {
    val events = Channel<LocalInferenceEvent>(Channel.UNLIMITED)
    val completed = CompletableDeferred<Unit>()
    var started = false
    try {
        currentCoroutineContext().ensureActive()
        start(
            { event -> events.trySend(event) },
            { error ->
                events.close(error)
                completed.complete(Unit)
            },
        )
        started = true
        for (event in events) emit(event)
        emit(LocalInferenceEvent.Finished("stop"))
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                if (started && !completed.isCompleted) {
                    cancel()
                    completed.await()
                }
            } finally {
                close()
                events.cancel()
            }
        }
    }
}
