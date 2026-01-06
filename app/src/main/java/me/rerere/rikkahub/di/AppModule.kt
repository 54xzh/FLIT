package me.rerere.rikkahub.di

import com.google.firebase.Firebase
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import com.google.firebase.remoteconfig.remoteConfig
import kotlinx.serialization.json.Json
import me.rerere.highlight.Highlighter
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.AILoggingManager
import me.rerere.rikkahub.data.ai.AIRequestLogManager
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.MemoryReflectionService
import me.rerere.rikkahub.service.WelcomePhrasesService
import me.rerere.rikkahub.service.scheduledtask.ScheduledTaskScheduler
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.utils.EmojiUtils
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.UpdateChecker
import me.rerere.tts.provider.TTSManager
import org.koin.dsl.module

val appModule = module {
    single<Json> { JsonInstant }

    single {
        Highlighter(get())
    }

    single {
        LocalTools(get())
    }

    single {
        UpdateChecker(get())
    }

    single {
        AppScope()
    }

    single<EmojiData> {
        EmojiUtils.loadEmoji(get())
    }

    single {
        TTSManager(get())
    }

    single {
        Firebase.crashlytics
    }

    single {
        Firebase.remoteConfig
    }

    single {
        Firebase.analytics
    }

    single {
        AILoggingManager()
    }

    single {
        AIRequestLogManager(dao = get())
    }

    single {
        WelcomePhrasesService(
            settingsStore = get(),
            providerManager = get(),
            memoryRepository = get(),
            requestLogManager = get(),
        )
    }

    single {
        MemoryReflectionService(
            json = get(),
            settingsStore = get(),
            memoryRepository = get(),
            providerManager = get(),
            requestLogManager = get(),
        )
    }

    single {
        ChatService(
            context = get(),
            appScope = get(),
            settingsStore = get(),
            conversationRepo = get(),
            toolResultArchiveRepository = get(),
            memoryRepository = get(),
            generationHandler = get(),
            requestLogManager = get(),
            templateTransformer = get(),
            providerManager = get(),
            localTools = get(),
            okHttpClient = get(),
            mcpManager = get()
        )
    }

    single {
        ScheduledTaskScheduler(
            context = get(),
            taskDao = get()
        )
    }
}
