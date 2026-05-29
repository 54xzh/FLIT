package me.rerere.rikkahub.di

import me.rerere.rikkahub.data.ai.rag.EmbeddingService
import me.rerere.rikkahub.data.repository.ConversationRepository
import me.rerere.rikkahub.data.repository.CachedOpenRouterModelCapabilityProvider
import me.rerere.rikkahub.data.repository.GenMediaRepository
import me.rerere.rikkahub.data.repository.LorebookEntryRevisionRepository
import me.rerere.rikkahub.data.repository.MemoryRepository
import me.rerere.rikkahub.data.repository.ModelCapabilityRepository
import me.rerere.rikkahub.data.repository.ModelQuotaRepository
import me.rerere.rikkahub.data.repository.StorageManagerRepository
import me.rerere.rikkahub.data.repository.ToolResultArchiveRepository
import me.rerere.ai.provider.providers.openai.OpenRouterModelCapabilityProvider
import org.koin.dsl.module

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
