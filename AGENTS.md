# AURA — AI Coding Context (AGENTS.md)

> Read this first. It is the **index** of the codebase: enough to find the right
> file without opening the others. Keep it short — every AI session loads it.
>
> **Current status, next steps, build/ship workflow and known pitfalls: [CLAUDE.md](CLAUDE.md)**
> (any AI tool should read it too, not just Claude).

## 1. Project at a glance

| Item | Value |
|---|---|
| App | AURA — Android AI automation agent (single module `android/app`) |
| Stack | Kotlin, Jetpack Compose, Hilt, Coroutines + Flow, Room, WorkManager |
| LLM | Groq (primary, gpt-oss cascade) → Gemini 2.5 Flash (fallback); keys via `llm/ApiKeyStore` |
| Cloud | Supabase (optional; app works fully offline without keys) |
| SDK | minSdk 29, target/compile 35 |
| Source root | `android/app/src/main/java/com/aura/` |
| Build | `cd android && ./gradlew assembleDebug` |
| Unit tests | `cd android && ./gradlew testDebugUnitTest` |
| Keys | Entered in the app's Settings, or `android/local.properties` (see `.env.example`) |

## 2. How to read this repo (token budget)

Read in three tiers and stop as soon as you have what the task needs.

1. **This file** — always.
2. **`<package>/package.md`** — only for the package(s) you're changing. Each one
   lists every file in that package with its role, plus the package's rules.
3. **`docs/`** — only when the task is about design, planning or research:
   - `docs/design/SKILL-SYSTEM.md` — skill system design (large)
   - `docs/plans/implementation-plan.md`, `docs/plans/MVP.md`, `docs/plans/roadmap.md`
   - `docs/research/deep-research-report.md` — background research (large)

Rules:
- Search (grep for a class or function name) before opening files. File name = class name.
- Open a single function's range rather than a whole large file when you can.
- Don't open `docs/` unless asked. Don't read `build/`, `.gradle/` or `android/app/schemas/`.

## 3. Module map

```
com.aura/
├── accessibility/  Read the screen (UI tree → text) and find nodes
├── agent/          Task lifecycle: goal → plan → run steps → learn
├── cloud/          Supabase auth + community skill store client
├── data/db/        Room database (v7) + DAOs
├── data/model/     Entities and data classes (ActionStep, SkillPackEntity, …)
├── di/             Hilt modules: Database, Network, Supabase
├── llm/            Groq + Gemini planning clients
├── macros/         Run a list of goals in sequence
├── memory/         Offline embeddings + recall of past tasks
├── policy/         Risk classification, per-app rules, injection defence, audit
├── skills/         Skills: per-OS variants, phrase matching, learning, RAM/disk caches, health
├── ui/             Compose screens, ViewModels, theme, MainActivity, NavHost
├── update/         APK self-update from GitHub Releases
├── voice/          Assistant service ("Hey AURA" → popup → task), TTS/STT, bubble
└── AURAApplication.kt  App entry: Supabase session, OS migration, update worker
```

## 4. Data flow — one task

```
"Hey AURA …" / mic button / bubble
 └─ voice/WakeWordService  (popup: voice/AssistantOverlay)
     └─ TaskManager.execute(goal)          typed commands enter here too
         ├─ PromptInjectionDefender.sanitize/validate
         ├─ setupProblem()?  → speak why (Accessibility off / no API key) and stop
         ├─ SkillRouter.findSkill(goal)   L1 RAM → L2 disk → L3 Room → L4 cloud
         │     (variants compatible with this phone; phrase pattern fills {slots})
         │     hit  → run filled steps — NO LLM call; failed step → SkillUpdater + re-plan once
         │     miss → MemoryManager context + UIObserver snapshot (≤2000 chars)
         │            → GroqClient.plan  (fails / no key → GeminiClient.plan) → Plan
         │     contacts: ContactResolver adds {contact_number}; ambiguous name → ask
         └─ runWithRecovery(steps)   (ends with a verify step: right app / text on screen)
               runStepsInternal, per step:
                 AppPermissionManager.evaluate → PolicyEngine.classify
                 LOW/MEDIUM → ActionExecutor.execute → StepResult (screen actions or intents)
                 HIGH/CRITICAL → WaitingForUser; popup or HomeScreen asks
                                 (voice yes/no for HIGH, BiometricGate for CRITICAL)
               step failed → LLM plans the REST from the current screen (max 2), continue
            success → SkillLearner.learn (path that worked) + MemoryManager.remember
```

- Skill model and rules: `skills/package.md`. `SkillRepository` only serves the Skill Store UI.

## 5. Conventions

- DB work → DAO `suspend fun` or `withContext(Dispatchers.IO)`.
- UI state → `StateFlow` in the ViewModel, collected with `collectAsStateWithLifecycle`.
- Risk → always `PolicyEngine.classify()`; never hard-code risk in UI.
- New action type → constant in `ActionStep` companion + branch in `ActionExecutor.execute()`.
- New screen → route in `ui/AURAApp.kt` NavHost + its own ViewModel.
- New table / column → entity + DAO + `AURADatabase.kt` version bump + `AutoMigration`; commit the generated schema JSON.
- New skill from a plan → `SkillLearner.learn()`; never insert `SkillPackEntity` by hand.
- Cloud calls → check `SupabaseModule.isConfigured` and no-op when false.
- API keys → read through `llm/ApiKeyStore`; never `BuildConfig` directly, never in source.
- Voice listening → only through `voice/WakeWordService` (one SpeechRecognizer at a time).

## 6. Keep docs true (required)

When you add, rename, move or delete a class, update **that package's `package.md`**
in the same change, and this file only if the module map, data flow or conventions changed.
Wrong docs cost more tokens than missing ones.

## 7. Large files — open only when working on them

| File | Lines | Note |
|---|---|---|
| `ui/screens/HomeScreen.kt` | ~585 | Compose UI only |
| `ui/screens/PermissionsScreen.kt` | ~475 | Compose UI only |
| `ui/screens/SettingsScreen.kt` | ~455 | Compose UI only |
| `agent/TaskManager.kt` | ~460 | Core loop — open the function you need |
| `voice/WakeWordService.kt` | ~580 | Assistant flow — open the section you need |
| `ui/screens/DashboardScreen.kt` | ~375 | Compose UI only |
| `skills/SkillSeeder.kt` | ~225 | Static seed data |
