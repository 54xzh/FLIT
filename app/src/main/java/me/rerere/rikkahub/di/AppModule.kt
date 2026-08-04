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
import me.rerere.rikkahub.data.backup.BackupCoordinator
import me.rerere.rikkahub.data.backup.BackupLogManager
import me.rerere.rikkahub.data.backup.BackupTaskMutex
import me.rerere.rikkahub.data.backup.CompatExporter
import me.rerere.rikkahub.data.ai.tools.LocalTools
import me.rerere.rikkahub.data.ai.tools.SearchAgentProgressStore
import me.rerere.rikkahub.data.ai.tools.WorkspaceToolFactory
import me.rerere.rikkahub.data.migration.WorkspaceMigration
import me.rerere.rikkahub.data.migration.SkillUuidMigration
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.service.AutoBackupScheduler
import me.rerere.rikkahub.service.ModelNameGenerationService
import me.rerere.rikkahub.service.WelcomePhrasesService
import me.rerere.rikkahub.service.scheduledtask.ScheduledTaskScheduler
import me.rerere.rikkahub.utils.EmojiData
import me.rerere.rikkahub.web.WebServerManager
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
        LocalTools(get(), get(), get(), get(), get(), get())
    }

    single {
        WorkspaceToolFactory(
            context = get(),
            workspaceRepository = get(),
            safRepository = get(),
            sandboxWorkspaceManager = get(),
        )
    }

    single {
        SearchAgentProgressStore()
    }

    single {
        WorkspaceMigration(
            settingsStore = get(),
            workspaceRepository = get(),
            conversationRepository = get(),
        )
    }

    single {
        SkillUuidMigration(
            context = get(),
            conversationDAO = get(),
        )
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
        BackupLogManager(dao = get())
    }

    single {
        BackupTaskMutex()
    }

    single {
        CompatExporter(
            context = get(),
            settingsStore = get(),
            conversationDAO = get(),
            memoryDAO = get(),
            genMediaDAO = get(),
        )
    }

    single {
        BackupCoordinator(
            context = get(),
            settingsStore = get(),
            webdavSync = get(),
            objectStorageSync = get(),
            backupLogManager = get(),
            backupTaskMutex = get(),
            compatExporter = get(),
        )
    }

    single {
        AutoBackupScheduler(
            context = get(),
            appScope = get(),
            backupCoordinator = get(),
            settingsStore = get(),
        )
    }

    single {
        ModelNameGenerationService(
            providerManager = get(),
            requestLogManager = get(),
        )
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
        ChatService(
            context = get(),
            appScope = get(),
            settingsStore = get(),
            readPositionStore = get(),
            conversationRepo = get(),
            toolResultArchiveRepository = get(),
            memoryRepository = get(),
            generationHandler = get(),
            requestLogManager = get(),
            templateTransformer = get(),
            providerManager = get(),
            embeddingService = get(),
            lorebookEntryRevisionRepository = get(),
            localTools = get(),
            okHttpClient = get(),
            mcpManager = get(),
            modelQuotaRepo = get(),
            searchAgentProgressStore = get(),
            workspaceRepository = get(),
            workspaceToolFactory = get(),
        )
    }

    // 把 ChatService 绑定为 ConversationDeletionCoordinator, 供 data 层 (StorageManagerRepository)
    // 依赖接口而非具体类, 避免data层反向依赖 app 层的 ChatService (循环依赖)。
    single<me.rerere.rikkahub.data.repository.ConversationDeletionCoordinator> { get<ChatService>() }

    single {
        ScheduledTaskScheduler(
            context = get(),
            taskDao = get()
        )
    }

    single {
        WebServerManager(
            context = get(),
            appScope = get(),
            chatService = get(),
            conversationRepo = get(),
            settingsStore = get(),
        )
    }
}
