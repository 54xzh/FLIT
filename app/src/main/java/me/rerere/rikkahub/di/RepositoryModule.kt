package me.rerere.rikkahub.di

import me.rerere.rikkahub.data.ai.rag.EmbeddingService
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.CachedOpenRouterModelCapabilityProvider
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.LorebookEntryRevisionRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.ModelCapabilityRepository
import me.rerere.rikkahub.data.repository.ModelQuotaRepository
import me.rerere.rikkahub.data.repository.SafRepository
import me.rerere.rikkahub.data.repository.StorageManagerRepository
import me.rerere.rikkahub.data.repository.ToolResultArchiveRepository
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.workspace.ProotSandboxShellRunner
import me.rerere.rikkahub.workspace.SandboxBindMount
import me.rerere.rikkahub.workspace.SandboxRootfsInstaller
import me.rerere.rikkahub.workspace.SandboxWorkspaceManager
import me.rerere.ai.provider.providers.openai.OpenRouterModelCapabilityProvider
import org.koin.dsl.module
import java.io.File

val repositoryModule = module {
    single {
        ConversationRepository(get(), get(), get(), get(), get(), get(), get(), get())
    }

    single {
        EmbeddingService(get(), get(), get())
    }

    single {
        MemoryRepository(get(), get(), get(), get())
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
            genMediaDAO = get(),
            aiRequestLogDao = get(),
        )
    }

    single {
        ModelQuotaRepository(get())
    }

    single {
        val context: android.content.Context = get()
        SandboxWorkspaceManager(
            baseDir = File(context.filesDir, "sandbox_workspaces"),
            shellRunner = ProotSandboxShellRunner(
                nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
                extraBindMounts = listOf(
                    SandboxBindMount(File(context.filesDir, "skills").apply { mkdirs() }, "/skills"),
                    SandboxBindMount(File(context.filesDir, "upload").apply { mkdirs() }, "/upload"),
                    SandboxBindMount(File(context.filesDir, "tool_outputs").apply { mkdirs() }, "/tool_outputs"),
                ),
            ),
        )
    }

    single { SandboxRootfsInstaller(get()) }

    single {
        WorkspaceRepository(
            db = get(),
            dao = get(),
            settingsStore = get(),
            context = get(),
            sandboxManager = get(),
            rootfsInstaller = get(),
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
