# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

**Full release build (exp flavor):**
```bash
export PATH=$JAVA_HOME/bin:$PATH && mkdir -p "$ANDROID_USER_HOME" && source ~/.bashrc && ./gradlew :app:assembleExpRelease --no-daemon -x :app:uploadCrashlyticsMappingFileExpRelease
```
Requires `JAVA_HOME`, `GRADLE_USER_HOME`, and `ANDROID_USER_HOME` set to project-local paths. Build exception: skip if change is purely UI copy or prompt text.

**Always package after code changes:** After making any code change that is not purely UI copy or prompt text, run the full release build command above before reporting the task done. Do not stop at `compileKotlin` — produce the actual APK. Only skip when the change falls under the build exception above.

**Run all unit tests:**
```bash
./gradlew test --no-daemon
```

**Run a single test class:**
```bash
./gradlew :app:testExpReleaseUnitTest --tests "me.rerere.rikkahub.SomeTest" --no-daemon
```

**Run AI module tests:**
```bash
./gradlew :ai:test --no-daemon
```

## Safety Rules

- Only read/write files inside this workspace. Read-only access to user-approved directories is acceptable.
- Writing outside the workspace is prohibited.
- Forbidden commands: `rm`, `rmdir`.

## Available Tools

- **LSP (Language Server Protocol):** Available for goToDefinition, findReferences, hover, documentSymbol, workspaceSymbol, goToImplementation, and call hierarchy. Prefer LSP over grep when navigating to symbol definitions, finding references, or inspecting type/documentation info — especially in well-indexed Kotlin files. Note: cross-file `goToDefinition` may be limited if the LSP server hasn't fully indexed the project.

## Design Philosophy

**App Name:** LastChat (package: `lastchat.rikkafork.cocolal`)

**The "Fidget Toy" Philosophy:** Interactions must be playful, physics-based, and deeply satisfying. High-quality haptics on every tap, toggle, and drag. Prefer spring-based interpolators for interactive motion. Navigation/page transitions may use `tween`.

**Workflow:** Iterative "glow-ups" over risky refactors. Crash-resistant code; `NullPointerException` is the enemy.

## Architecture

### Modules
- `app/` - Main module: UI (Compose), services, DI (Koin), Room database, navigation
- `ai/` - AI provider abstraction (OpenAI, Google/Gemini, Anthropic/Claude). Stateless `Provider<T : ProviderSetting>` interface with `streamText`, `generateText`, `generateImage`, `createEmbedding`
- `common/` - Shared utilities, caching (LRU, file-based), JSON helpers (`JsonInstant`)
- `highlight/` - Syntax highlighting
- `search/` - Web search providers (Exa, Tavily, Bing, Brave, Firecrawl, BochaAI, etc.)
- `tts/` - Text-to-Speech (SystemTTS, OpenAI, Gemini, ElevenLabs, MiniMax)
- `document/` - PDF and DOCX parsing

### Key Wiring (DI)
Koin modules in `app/src/main/java/me/rerere/rikkahub/di/`:
- **AppModule** - Singletons: `ChatService`, `TemplateTransformer`, `TTSManager`, `BackupCoordinator`, Firebase services
- **DataSourceModule** - Room `AppDatabase` + all DAOs, `OkHttpClient`, `Retrofit`, `ProviderManager`, `McpManager`, Pebble template engine
- **RepositoryModule** - `ConversationRepository`, `MemoryRepository`, `EmbeddingService`, `GenMediaRepository`, etc.
- **ViewModelModule** - 22+ ViewModels via `viewModelOf` DSL

### Navigation
Type-safe Compose Navigation 2 with `@Serializable` route classes under `sealed interface Screen` in `RouteActivity.kt`. Key routes: `Screen.Chat(id)`, `Screen.Menu`, `Screen.Assistant`, `Screen.Setting*`, `Screen.ImageGen`, `Screen.Translator`.

### AI Provider Pattern
`ProviderManager` is a stateless registry. `ProviderSetting` is a sealed class with subtypes `OpenAI`, `Google`, `Claude`. Providers are resolved at runtime by name. The `ai/` module has no Android dependency awareness; the `app/` module's `ChatService` and `GenerationHandler` coordinate actual AI requests.

### Database
Room (version 34) with auto-migrations. Key entities: `ConversationEntity`, `MemoryEntity`, `ChatEpisodeEntity`, `EmbeddingCacheEntity`, `ToolResultArchiveEntity`, `ScheduledTaskEntity`. Migrations defined in `DataSourceModule`.

### Build Variants
Product flavors (dimension `channel`): `plus`, `exp`, `zh` - each with an applicationId suffix. Compile SDK 36, min SDK 31, target SDK 36. JVM target 17.

## Coding Standards

### Performance & Concurrency
- I/O operations MUST run on `Dispatchers.IO`. `AppScope` defaults to `Dispatchers.Default`.
- In `LazyColumn`: never pass `SnapshotStateList` directly. Use `derivedStateOf` to derive simple immutable states.
- Prioritize token economy and vector memory caching.

### Robustness
- **JSON:** `!!` on JSON elements is STRICTLY PROHIBITED. Use `is JsonArray`, `jsonPrimitiveOrNull`, etc.
- **StateFlow updates:** Snapshot the current value into a local variable before complex transformations to avoid race conditions.

### Serialization
Use `me.rerere.rikkahub.utils.JsonInstant` (or `JsonInstantPretty`). Ignores unknown keys but does NOT auto-apply snake_case. Manual field mapping required for external APIs.

## UI/UX Guidelines

### Design Language
Material You 3 Expressive / Android 16. Shapes from `me.rerere.rikkahub.ui.theme.AppShapes`:
- Cards: `AppShapes.CardLarge` (28.dp), `AppShapes.CardMedium` (24.dp)
- Buttons: `AppShapes.ButtonPill` (50%)

### Haptics
Use the custom `PremiumHaptics` wrapper (`rememberPremiumHaptics`, `HapticPattern`). Do NOT use `LocalHapticFeedback`.
- Buttons must scale to `0.85f` on press + `HapticPattern.Pop`
- Click/Toggle: `Pop` | Heavy/Drop: `Thud` | Success: `Success`

### Animation
- Standard: `spring(dampingRatio = 0.5f, stiffness = 400f)`
- Bouncy: `spring(dampingRatio = 0.6f, stiffness = 300f)`
- `tween`/linear prohibited for interactive elements (allowed for navigation transitions)

## RAG & Embeddings
Embeddings stored in source entities (`MemoryEntity`, `ChatEpisodeEntity`) AND `EmbeddingCacheDAO`. Add/update/delete must sync both stores. Prefer existing entity embeddings over re-computation.

## Operations
- **Commits:** Conventional Commits (`feat:`, `fix:`, `chore:`).
- **Localization:** New/modified user-visible text requires `values-zh-rCN/strings.xml` and `values-zh-rTW/strings.xml`. Do not add new languages unless requested.
- **Unit tests** in `src/test`, **instrumented tests** in `src/androidTest`.
