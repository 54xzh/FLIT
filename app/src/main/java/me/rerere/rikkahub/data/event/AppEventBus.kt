package me.rerere.rikkahub.data.event

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 进程内事件总线。基于 SharedFlow，replay=0，无订阅者时事件直接丢弃。
 *
 * 关键约束：OAuth 回调场景下，订阅者必须在事件 emit 之前完成订阅，
 * 否则快速回调会丢失。McpOAuthCoordinator 在打开浏览器前先用
 * `onSubscription { }` + CompletableDeferred 确认订阅就绪。
 */
class AppEventBus {
    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<AppEvent> = _events.asSharedFlow()

    suspend fun emit(event: AppEvent) {
        _events.emit(event)
    }

    /**
     * 非挂起发送，缓冲满时丢弃事件并返回 false。
     * 用于高频且允许丢失的事件，避免反压发送方。
     */
    fun tryEmit(event: AppEvent): Boolean = _events.tryEmit(event)
}