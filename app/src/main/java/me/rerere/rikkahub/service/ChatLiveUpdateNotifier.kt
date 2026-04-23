package me.rerere.rikkahub.service

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity

private const val EXTRA_CONVERSATION_ID = "conversationId"
private const val EXTRA_SESSION_ID = "sessionId"

private const val ANDROID_EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
private const val FLAG_PROMOTED_ONGOING = 0x40000

enum class ChatLiveUpdateState {
    WAITING,
    INFERENCE,
    TOOL_CALL,
    WAITING_FOR_ANSWER,
    OUTPUT,
    DONE,
    ERROR,
}

internal fun ChatLiveUpdateState.isOngoing(): Boolean {
    return when (this) {
        ChatLiveUpdateState.WAITING,
        ChatLiveUpdateState.INFERENCE,
        ChatLiveUpdateState.TOOL_CALL,
        ChatLiveUpdateState.WAITING_FOR_ANSWER,
        ChatLiveUpdateState.OUTPUT,
        -> true

        ChatLiveUpdateState.DONE,
        ChatLiveUpdateState.ERROR,
        -> false
    }
}

private fun ChatLiveUpdateState.isAutoCancel(): Boolean {
    return this == ChatLiveUpdateState.DONE || this == ChatLiveUpdateState.ERROR
}

class ChatLiveUpdateNotifier(
    private val context: Context,
) {
    fun notify(
        conversationId: Uuid,
        sessionId: Long,
        state: ChatLiveUpdateState,
        title: String,
        contentText: String?,
        bigText: String?,
        smallIcon: Icon?,
        largeIcon: Icon?,
    ) {
        if (ChatLiveUpdateDismissalTracker.isDismissed(conversationId, sessionId)) return
        if (!hasPostNotificationsPermission()) return

        val stateTitle = stateTitle(state)
        val finalLargeIcon = largeIcon ?: smallIcon
        val builder = Notification.Builder(context, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_statusbar)
            .setContentTitle(title)
            .setContentText(contentText.orEmpty())
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(state.isOngoing())
            .setAutoCancel(state.isAutoCancel())
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(conversationPendingIntent(conversationId))
            .setDeleteIntent(deleteIntent(conversationId, sessionId))

        builder.setShortCriticalTextCompat(stateTitle)

        builder.setPublicVersion(
            Notification.Builder(context, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_statusbar)
                .setContentTitle(title)
                .setContentText(stateTitle)
                .setOnlyAlertOnce(true)
                .setOngoing(state.isOngoing())
                .setAutoCancel(state.isAutoCancel())
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(conversationPendingIntent(conversationId))
                .also { it.setShortCriticalTextCompat(stateTitle) }
                .build()
        )

        if (finalLargeIcon != null) {
            builder.setLargeIcon(finalLargeIcon)
        }

        if (state.isOngoing()) {
            builder.setProgress(0, 0, true)
            builder.requestPromotedOngoingCompat(true)
        } else {
            builder.requestPromotedOngoingCompat(false)
        }

        if (!bigText.isNullOrBlank()) {
            builder.setStyle(Notification.BigTextStyle().bigText(bigText))
        }

        NotificationManagerCompat.from(context).notify(notificationId(conversationId), builder.build())
    }

    fun cancel(conversationId: Uuid) {
        NotificationManagerCompat.from(context).cancel(notificationId(conversationId))
    }

    private fun hasPostNotificationsPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun stateTitle(state: ChatLiveUpdateState): String {
        return when (state) {
            ChatLiveUpdateState.WAITING -> context.getString(R.string.notification_live_update_waiting)
            ChatLiveUpdateState.INFERENCE -> context.getString(R.string.notification_live_update_inference)
            ChatLiveUpdateState.TOOL_CALL -> context.getString(R.string.notification_live_update_tool_call)
            ChatLiveUpdateState.WAITING_FOR_ANSWER -> context.getString(R.string.notification_live_update_waiting_for_answer)
            ChatLiveUpdateState.OUTPUT -> context.getString(R.string.notification_live_update_output)
            ChatLiveUpdateState.DONE -> context.getString(R.string.notification_live_update_done)
            ChatLiveUpdateState.ERROR -> context.getString(R.string.notification_live_update_error)
        }
    }

    private fun conversationPendingIntent(conversationId: Uuid): PendingIntent {
        val intent = Intent(context, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("conversationId", conversationId.toString())
        }
        return PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun deleteIntent(conversationId: Uuid, sessionId: Long): PendingIntent {
        val intent = Intent(context, ChatLiveUpdateDismissReceiver::class.java).apply {
            putExtra(EXTRA_CONVERSATION_ID, conversationId.toString())
            putExtra(EXTRA_SESSION_ID, sessionId)
        }
        return PendingIntent.getBroadcast(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    companion object {
        fun notificationId(conversationId: Uuid): Int = conversationId.hashCode()
    }
}

private fun Notification.Builder.requestPromotedOngoingCompat(request: Boolean) {
    setFlag(FLAG_PROMOTED_ONGOING, request)
    runCatching {
        javaClass
            .getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
            .invoke(this, request)
    }.getOrElse {
        addExtras(
            Bundle().apply {
                putBoolean(ANDROID_EXTRA_REQUEST_PROMOTED_ONGOING, request)
            }
        )
    }
}

private fun Notification.Builder.setShortCriticalTextCompat(text: CharSequence?) {
    if (text.isNullOrBlank()) return
    runCatching {
        javaClass
            .getMethod("setShortCriticalText", String::class.java)
            .invoke(this, text.toString())
    }.recoverCatching {
        javaClass
            .getMethod("setShortCriticalText", CharSequence::class.java)
            .invoke(this, text)
    }
}

internal object ChatLiveUpdateDismissalTracker {
    private val dismissedSessionByConversationId = ConcurrentHashMap<Uuid, Long>()

    fun isDismissed(conversationId: Uuid, sessionId: Long): Boolean {
        return dismissedSessionByConversationId[conversationId] == sessionId
    }

    fun clear(conversationId: Uuid) {
        dismissedSessionByConversationId.remove(conversationId)
    }

    fun markDismissed(conversationId: Uuid, sessionId: Long) {
        dismissedSessionByConversationId[conversationId] = sessionId
    }
}

class ChatLiveUpdateDismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val conversationIdStr = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: return
        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        if (sessionId <= 0L) return
        val conversationId = runCatching { Uuid.parse(conversationIdStr) }.getOrNull() ?: return

        ChatLiveUpdateDismissalTracker.markDismissed(conversationId, sessionId)
        NotificationManagerCompat.from(context).cancel(ChatLiveUpdateNotifier.notificationId(conversationId))
    }
}
