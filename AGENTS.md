# AGENTS.md

## 0. Codex CLI Safety Rules (Workspace Only)

- Only read/write files inside the current workspace (this repo). Do not access or modify any paths outside the workspace, except:
    - **Read-only** access to `~/.codex` (e.g. `C:\Users\54xzh\.codex`).
    - **Read-only** access to directories explicitly approved by the user for this session.
- Writing outside the workspace remains prohibited.
- Forbidden commands: `rm`, `rmdir` (do not use them even if requested).

## 1. Core Principles & Design Philosophy

**App Name:** LastChat (Repo: RikkaHub)

**The "Fidget Toy" Philosophy:**
LastChat is not just a tool; it is designed to be a "fidget toy".
-   **Feel:** Interactions must be playful, physics-based, and deeply satisfying.
-   **Tactile Feedback:** High-quality haptics are non-negotiable. Every tap, toggle, and drag must have appropriate feedback.
-   **Motion:** Prefer physics-based interpolators (springs) for all interactive motion to convey momentum and weight. **Exception:** navigation/page transitions may use `tween`.

**Workflow:**
-   **Iterative Polish:** We prefer iterative "glow-ups" of specific components over massive, risky refactors.
-   **Robustness:** The app must be crash-resistant. `NullPointerException` is the enemy.

## 2. Architecture & Codebase Structure

### Modules
-   `app/`: Main application module. Contains UI (Compose), Core Logic, DI, Data Layers, and Room Database.
-   `ai/`: Abstraction layer for AI providers (OpenAI, Google, Anthropic).
-   `common/`: Shared utilities and extensions.
-   `highlight/`: Syntax highlighting features.
-   `search/`: Search functionality (Exa, Tavily, Zhipu).
-   `tts/`: Text-to-Speech implementation.

### Key Technologies
-   **Language:** Kotlin (uses experimental `kotlin.uuid.Uuid`).
-   **UI:** Jetpack Compose (Material You 3 Expressive / Android 16).
-   **Dependency Injection:** Koin.
-   **Database:** Room.
-   **Network:** OkHttp (with SSE support).
-   **Serialization:** Kotlinx Serialization.

## 3. Coding Standards & Best Practices

### Performance & Concurrency
-   **I/O Operations:** MUST be explicitly executed on `Dispatchers.IO`.
    -   *Crucial:* `AppScope` defaults to `Dispatchers.Default`. Do not block the main thread or the default dispatcher with I/O.
-   **Compose Optimization:**
    -   **Lists:** Never pass mutable collections (`SnapshotStateList`) directly to `LazyColumn` items. Use `derivedStateOf` to pass simple, immutable states (e.g., `Boolean`) to prevent unnecessary recompositions.
-   **AI Context:** Prioritize token economy and vector memory efficiency. Use caching.

### Robustness & Safety
-   **JSON Handling:**
    -   **STRICTLY PROHIBITED:** Non-null assertions (`!!`) on JSON elements.
    -   **REQUIRED:** Use safe type checks (`is JsonArray`, `jsonPrimitiveOrNull`).
-   **State Management:**
    -   When updating `StateFlow` in services (e.g., `ChatService`), **snapshot** the current value into a local variable before applying complex transformations to avoid race conditions.

### Serialization
-   Use `me.rerere.rikkahub.utils.JsonInstant` (or `JsonInstantPretty`).
    -   *Note:* It ignores unknown keys but **does not** apply snake_case strategies. Field mapping must be manual for external APIs.

## 4. UI/UX Guidelines

### Design Language
-   **Standard:** Material You 3 Expressive / Android 16.
-   **Shapes:** Adhere strictly to `me.rerere.rikkahub.ui.theme.AppShapes`:
    -   **Cards:** `AppShapes.CardLarge` (28.dp), `AppShapes.CardMedium` (24.dp).
    -   **Buttons:** `AppShapes.ButtonPill` (50%).

### Haptics (Critical)
-   **Library:** Use the custom `PremiumHaptics` wrapper.
    -   `import me.rerere.rikkahub.ui.hooks.rememberPremiumHaptics`
    -   `import me.rerere.rikkahub.ui.hooks.HapticPattern`
-   **Usage:**
    -   **Do not** use `LocalHapticFeedback`.
    -   **Interactive Elements:** Buttons (like `BackButton`) must scale down to `0.85f` on press and trigger `HapticPattern.Pop`.
    -   **Patterns:**
        -   Click/Toggle: `HapticPattern.Pop`
        -   Heavy Action/Drop: `HapticPattern.Thud`
        -   Success: `HapticPattern.Success`

### Animation
-   **Standard Spec:** `spring(dampingRatio = 0.5f, stiffness = 400f)`
-   **Bouncy/Clicky Spec:** `spring(dampingRatio = 0.6f, stiffness = 300f)`
-   **Prohibited (Interactive):** `tween` or linear animations. **Exception:** navigation/page transitions may use `tween`.

## 5. Specific Feature Guidelines

### RAG & Embeddings
-   **Persistence:** Embeddings are stored in source entities (`MemoryEntity`, `ChatEpisodeEntity`) **AND** `EmbeddingCacheDAO`.
-   **Sync:** Operations (add/update/delete) must synchronize both stores.
-   **Retrieval:** Always prefer existing entity embeddings over re-computation.

## 6. Testing & Operations
-   **Unit Tests:** Place in `src/test`. Cover parsing and logic.
-   **Instrumented Tests:** Place in `src/androidTest`. Cover flows.
-   **Build Environment:** Use JDK 21. Prefer a project-local JDK and expose it through `JAVA_HOME`.
-   **Build Cache:** Use a project-local Gradle cache through `GRADLE_USER_HOME` (for example, a `.gradle-user` directory under the repo root).
-   **Android User Home:** Use a project-local Android user directory through `ANDROID_USER_HOME` and ensure it exists before running Gradle.
-   **Build**: After completing the changes, use `export PATH=$JAVA_HOME/bin:$PATH && mkdir -p "$ANDROID_USER_HOME" && source ~/.bashrc && ./gradlew :app:assembleExpRelease --no-daemon -x :app:uploadCrashlyticsMappingFileExpRelease` to build the package for testing. Ensure `JAVA_HOME`, `GRADLE_USER_HOME`, and `ANDROID_USER_HOME` are already set to valid project-local paths in the local environment.
-   **Build Exception**: If the change only updates UI copy or prompt text (no logic, behavior, or resource/schema changes), compile verification can be skipped.
-   **Commit Guidelines:** Use Conventional Commits (`feat:`, `fix:`, `chore:`).
-   **Localization Requirement:** When adding or modifying features that introduce/modify user-visible text, you MUST provide both Simplified Chinese and Traditional Chinese `strings.xml` (typically `values-zh-rCN/strings.xml` and `values-zh-rTW/strings.xml`) alongside the default resources.
-   **Language Support:** Do not submit new languages unless explicitly requested.
