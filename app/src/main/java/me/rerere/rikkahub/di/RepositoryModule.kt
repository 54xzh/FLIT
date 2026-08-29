package me.rerere.rikkahub.di

import me.rerere.rikkahub.data.ai.rag.EmbeddingService
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.CachedOpenRouterModelCapabilityProvider
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.LorebookEntryRevisionRepository
import me.rerere.rikkahub.data.repository.KeywordMemoryTokenizer
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.MemoryKeywordTokenizer
import me.rerere.rikkahub.data.repository.MemoryRetrievalService
import me.rerere.rikkahub.data.repository.MemorySummaryRepository
import me.rerere.rikkahub.data.repository.MemorySummaryScheduler
import me.rerere.rikkahub.data.repository.ModelCapabilityRepository
import me.rerere.rikkahub.data.repository.ModelQuotaRepository
import me.rerere.rikkahub.data.repository.SafRepository
import me.rerere.rikkahub.data.repository.StorageManagerRepository
import me.rerere.rikkahub.data.repository.ToolResultArchiveRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.service.MemoryEmbeddingBackfillScheduler
import me.rerere.rikkahub.workspace.ProotSandboxShellRunner
import me.rerere.rikkahub.workspace.ProotSandboxProcessLauncher
import me.rerere.rikkahub.workspace.SandboxProcessLauncher
import me.rerere.rikkahub.workspace.SandboxProcessCoordinator
import me.rerere.rikkahub.workspace.SandboxRootfsInstaller
import me.rerere.rikkahub.workspace.SandboxWorkspaceManager
import me.rerere.rikkahub.workspace.SandboxMountPathResolver
import me.rerere.rikkahub.workspace.WorkspaceTransferArchive
import me.rerere.rikkahub.workspace.sandboxBindMounts
import me.rerere.ai.provider.providers.openai.OpenRouterModelCapabilityProvider
import org.koin.dsl.module
import java.io.File

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get(), get(), get(), get())
    }

    single {
        EmbeddingService(get(), get(), get(), get())
    }

    single {
        MemoryEmbeddingBackfillScheduler(get())
    }

    single {
        MemorySummaryScheduler(get())
    }

    single {
        MemorySummaryRepository(get(), get(), get(), get(), get())
    }

    single {
        MemoryRepository(get(), get(), get(), get(), get(), get(), get(), get(), get())
    }

    single<MemoryKeywordTokenizer> {
        KeywordMemoryTokenizer()
    }

    single {
        MemoryRetrievalService(get(), get())
    }

    single {
        GenMediaRepository(get())
    }

    single {
        ToolResultArchiveRepository(get(), get(), get(), get(), get())
    }

    single {
        LorebookEntryRevisionRepository(get(), get())
    }

    single {
        StorageManagerRepository(
            context = get(),
            settingsStore = get(),
            conversationDAO = get(),
            conversationRepository = get(),
            conversationDeletionCoordinator = get(),
            genMediaDAO = get(),
            aiRequestLogDao = get(),
            workspaceRepository = get(),
        )
    }

    single {
        ModelQuotaRepository(get())
    }

    single<SandboxProcessLauncher> {
        val context: android.content.Context = get()
        ProotSandboxProcessLauncher(
            nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
            extraBindMounts = sandboxBindMounts(context),
        )
    }

    single { SandboxProcessCoordinator() }

    single {
        val context: android.content.Context = get()
        SandboxWorkspaceManager(
            baseDir = File(context.filesDir, "sandbox_workspaces"),
            shellRunner = ProotSandboxShellRunner(get()),
        )
    }

    single { SandboxRootfsInstaller(get()) }

    single { SandboxMountPathResolver(get()) }

    single { WorkspaceTransferArchive(get()) }

    single {
        WorkspaceRepository(
            db = get(),
            dao = get(),
            settingsStore = get(),
            context = get(),
            sandboxManager = get(),
            rootfsInstaller = get(),
            workspaceTransferArchive = get(),
            conversationRepository = get(),
            mountPathResolver = get(),
            sandboxProcessCoordinator = get(),
            safRepository = get(),
        )
    }

    single {
        SafRepository(context = get())
    }

    single {
        ModelCapabilityRepository(
            client = get(),
            store = get(),
            json = get(),
        )
    }

    single<OpenRouterModelCapabilityProvider> {
        CachedOpenRouterModelCapabilityProvider(repository = get())
    }
}
