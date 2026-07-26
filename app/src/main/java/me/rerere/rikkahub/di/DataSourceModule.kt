package me.rerere.rikkahub.di

import androidx.room.Room
import io.ktor.http.HttpHeaders
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.serialization.json.Json
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.providers.openai.OpenRouterModelCapabilityProvider
import me.rerere.common.http.AcceptLanguageBuilder
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.ai.AIRequestInterceptor
import me.rerere.rikkahub.data.ai.transformers.AssistantTemplateLoader
import me.rerere.rikkahub.data.ai.GenerationHandler
import me.rerere.rikkahub.data.ai.transformers.TemplateTransformer
import me.rerere.rikkahub.data.api.LastChatAPI
import me.rerere.rikkahub.data.api.SponsorAPI
import me.rerere.rikkahub.data.datastore.ChatReadPositionStore
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.ModelCapabilityStore
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.Migration_6_7
import me.rerere.rikkahub.data.ai.mcp.McpManager
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.sync.ObjectStorageSync
import me.rerere.rikkahub.data.sync.WebdavSync
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.Locale
import java.util.concurrent.TimeUnit

val dataSourceModule = module {
    single {
        SettingsStore(context = get(), scope = get())
    }

    // createdAtStart: 启动时就加载阅读位置到内存，保证进聊天页时可同步读取
    single(createdAtStart = true) {
        ChatReadPositionStore(context = get(), settingsStore = get(), scope = get())
    }

    single {
        ModelCapabilityStore(context = get())
    }

    single {
        Room.databaseBuilder(get(), AppDatabase::class.java, "rikka_hub")
            .addMigrations(
                Migration_6_7,
                AppDatabase.MIGRATION_11_12,
                AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_14_16,
                AppDatabase.MIGRATION_34_35,
                AppDatabase.MIGRATION_35_36,
                AppDatabase.MIGRATION_36_37,
                AppDatabase.MIGRATION_37_38,
                AppDatabase.MIGRATION_38_39,
                AppDatabase.MIGRATION_39_40,
                AppDatabase.MIGRATION_40_41,
                AppDatabase.MIGRATION_41_42,
                AppDatabase.MIGRATION_42_43,
                AppDatabase.MIGRATION_43_44,
            )
            .build()
    }

    single {
        AssistantTemplateLoader(settingsStore = get())
    }

    single {
        PebbleEngine.Builder()
            .loader(get<AssistantTemplateLoader>())
            .defaultLocale(Locale.getDefault())
            .autoEscaping(false)
            .build()
    }

    single { TemplateTransformer(engine = get(), settingsStore = get()) }

    single {
        get<AppDatabase>().conversationDao()
    }

    single {
        get<AppDatabase>().memoryDao()
    }

    single {
        get<AppDatabase>().genMediaDao()
    }

    single {
        get<AppDatabase>().chatEpisodeDao()
    }

    single {
        get<AppDatabase>().embeddingCacheDao()
    }

    single {
        get<AppDatabase>().toolResultArchiveDao()
    }

    single {
        get<AppDatabase>().toolResultArchiveChunkDao()
    }

    single {
        get<AppDatabase>().aiRequestLogDao()
    }

    single {
        get<AppDatabase>().backupLogDao()
    }

    single {
        get<AppDatabase>().scheduledTaskDao()
    }

    single {
        get<AppDatabase>().scheduledTaskRunDao()
    }

    single {
        get<AppDatabase>().dailyActivityDao()
    }

    single {
        get<AppDatabase>().lorebookEntryRevisionDao()
    }

    single {
        get<AppDatabase>().usageStatsDao()
    }

    single {
        get<AppDatabase>().modelQuotaUsageDao()
    }

    single {
        get<AppDatabase>().workspaceDao()
    }

    single { AppEventBus() }

    single { McpManager(settingsStore = get(), appScope = get(), appEventBus = get()) }

    single {
        GenerationHandler(
            context = get(),
            providerManager = get(),
            json = get(),
            memoryRepo = get(),
            conversationRepo = get(),
            toolResultArchiveRepository = get(),
            aiLoggingManager = get(),
            requestLogManager = get(),
            embeddingService = get(),
        )
    }

    single<OkHttpClient> {
        val acceptLang = AcceptLanguageBuilder.fromAndroid(get())
            .build()
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(120, TimeUnit.SECONDS)
            .followSslRedirects(true)
            .followRedirects(true)
            .retryOnConnectionFailure(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .addHeader(HttpHeaders.AcceptLanguage, acceptLang)
                    .addHeader(HttpHeaders.UserAgent, "LastChat-Android/${BuildConfig.VERSION_NAME}")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(AIRequestInterceptor(remoteConfig = get()))
            .addInterceptor(HttpLoggingInterceptor().apply {
                // 发布版关闭网络日志；调试版也要脱敏鉴权信息，避免 API Key 被写进系统日志
                // （Gemini 的 key 挂在 URL query 上，所以还需要 redactQueryParams）
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.HEADERS else HttpLoggingInterceptor.Level.NONE
                redactHeader("Authorization")
                redactHeader("x-api-key")
                redactQueryParams("key")
            })
            .build()
    }

    single {
        SponsorAPI.create(get())
    }

    single {
        ProviderManager(
            client = get(),
            openRouterModelCapabilityProvider = runCatching {
                get<OpenRouterModelCapabilityProvider>()
            }.getOrNull(),
        )
    }

    single {
        WebdavSync(
            settingsStore = get(),
            readPositionStore = get(),
            json = get(),
            context = get(),
            database = get(),
            skillUuidMigration = get(),
        )
    }

    single {
        ObjectStorageSync(context = get(), webdavSync = get())
    }

    single<Retrofit> {
        Retrofit.Builder()
            .baseUrl("https://api.rikka-ai.com")
            .addConverterFactory(get<Json>().asConverterFactory("application/json; charset=UTF8".toMediaType()))
            .build()
    }

    single<LastChatAPI> {
        get<Retrofit>().create(LastChatAPI::class.java)
    }
}
