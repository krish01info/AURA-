# AURA — AI Coding Context (AGENTS.md)

> **Read this file first.** It gives you the full picture of the codebase so you
> don't need to read every source file before making changes.

---

## 1. Project at a Glance

| Item | Value |
|---|---|
| **App** | AURA — Android AI automation agent |
| **Language** | Kotlin (100%) |
| **UI** | Jetpack Compose |
| **DI** | Hilt |
| **Async** | Kotlin Coroutines + Flow |
| **DB** | Room |
| **LLM** | Groq (primary) → Gemini 1.5 Flash (fallback) |
| **Root package** | `com.aura` |
| **Source root** | `android/app/src/main/java/com/aura/` |

---

## 2. Module Map

```
com.aura/
├── accessibility/      UI tree reading + event handling
├── agent/              Core agent loop (plan → execute → verify)
├── cloud/              Supabase cloud layer (skills, auth, telemetry)
├── data/
│   ├── db/             Room DAOs + AURADatabase
│   └── model/          All data classes / entities
├── di/                 Hilt modules (DB, Network, Supabase)
├── llm/                LLM API clients (Groq, Gemini)
├── macros/             Macro recording & replay
├── memory/             Long-term semantic memory (embed + recall)
├── policy/             Safety, risk classification, audit
├── skills/             Skill pack cache, learning, identity
├── ui/
│   ├── screens/        Compose screens (6 screens)
│   ├── theme/          Material 3 theme
│   └── viewmodel/      ViewModels (one per screen)
├── update/             OTA skill-pack update logic
├── voice/              Wake word, TTS, STT, floating bubble
└── AURAApplication.kt  Hilt app entry point
```

---

## 3. Key Classes — Quick Reference

### Agent (core loop)
| Class | File | Purpose |
|---|---|---|
| `TaskManager` | `agent/TaskManager.kt` | Central orchestrator. Accepts goals, checks skill cache, calls LLM, runs step loop, gates HIGH/CRITICAL steps |
| `ActionExecutor` | `agent/ActionExecutor.kt` | Translates `ActionStep` → real Android gestures via AccessibilityService |
| `SkillRouter` | `agent/SkillRouter.kt` | 2-layer lookup: L1 RAM cache → L2 Room DB → null (LLM fallback) |
| `TaskState` | `agent/TaskState.kt` | Sealed class: Created/Planning/Executing/WaitingForUser/Verifying/Completed/Failed/Cancelled |

### LLM Clients
| Class | File | Purpose |
|---|---|---|
| `GroqClient` | `llm/GroqClient.kt` | Primary LLM. Model cascade: llama-3.3-70b → llama-3.1-8b → gemma2-9b. Calls plan(goal, uiContext, memoryContext) |
| `GeminiClient` | `llm/GeminiClient.kt` | Fallback when Groq unavailable. Same plan() interface |

### Memory
| Class | File | Purpose |
|---|---|---|
| `MemoryManager` | `memory/MemoryManager.kt` | remember() after tasks; recall() top-3 similar; buildContextString() → compact [MEMORY:...] tag |
| `EmbeddingEngine` | `memory/EmbeddingEngine.kt` | Hash-based 384-dim TF-IDF embeddings (offline). Future: ONNX all-MiniLM-L6-v2 |

### Accessibility
| Class | File | Purpose |
|---|---|---|
| `UIObserver` | `accessibility/UIObserver.kt` | Live UI snapshot. getTextSnapshot() → up to 2000 chars of visible text |
| `NodeFinder` | `accessibility/NodeFinder.kt` | Finds nodes by text or view ID |
| `AURAAccessibilityService` | `accessibility/AURAAccessibilityService.kt` | Android service entry point |

### Policy / Safety
| Class | File | Purpose |
|---|---|---|
| `PolicyEngine` | `policy/PolicyEngine.kt` | Classifies ActionStep → RiskLevel (LOW/MEDIUM/HIGH/CRITICAL) |
| `PromptInjectionDefender` | `policy/PromptInjectionDefender.kt` | Sanitizes user input before LLM |
| `AnomalyDetector` | `policy/AnomalyDetector.kt` | Flags suspicious step sequences |
| `AuditLogger` | `policy/AuditLogger.kt` | Logs every auto-executed / approved / denied step |
| `AppPermissionManager` | `policy/AppPermissionManager.kt` | Per-app rules: ALWAYS_ALLOW, ALWAYS_DENY, ALWAYS_CONFIRM |
| `BiometricGate` | `policy/BiometricGate.kt` | Biometric auth before CRITICAL actions |

### Skills
| Class | File | Purpose |
|---|---|---|
| `SkillCache` | `skills/SkillCache.kt` | In-memory LRU of top-20 skills |
| `SkillLearner` | `skills/SkillLearner.kt` | Auto-learns new skill from successful LLM plan |
| `SkillRepository` | `skills/SkillRepository.kt` | CRUD over SkillPackDao |
| `SkillIdentity` | `skills/SkillIdentity.kt` | Canonical skill ID generation |
| `OsFingerprint` | `skills/OsFingerprint.kt` | Detects OS class, skin (MIUI/OneUI/etc), API level |
| `SkillSeeder` | `skills/SkillSeeder.kt` | Pre-seeds common skills on first install |

### Voice / UI
| Class | File | Purpose |
|---|---|---|
| `VoiceManager` | `voice/VoiceManager.kt` | TTS + STT. speak(), narrateStep(), announceCompletion() |
| `WakeWordService` | `voice/WakeWordService.kt` | Background wake-word detection ("Hey AURA") |
| `FloatingBubble` | `voice/FloatingBubble.kt` | Overlay bubble shown during execution |

### Data Models (all in `data/model/`)
| Class | Purpose |
|---|---|
| `ActionStep` | Single LLM output step. Fields: action, pkg, text, viewId, inputText, direction, waitMs, message, risk, clarification |
| `MemoryEntry` | Room entity for long-term memory |
| `SkillPackEntity` | Room entity for a skill pack |
| `TaskLogEntity` | Room entity for task audit log |
| `AuditLogEntity` | Room entity for policy audit events |
| `MacroEntity` | Room entity for recorded macros |
| `LlmModels` | API request/response data classes |

---

## 4. Data Flow — Single Task

```
User speaks/types goal
        │
        ▼
TaskManager.execute(goal)
        │
        ├─ PromptInjectionDefender.sanitize/validate
        ├─ SkillRouter.findBestSkill(goal)  ← L1 RAM → L2 Room
        │       hit → use cached steps (NO LLM CALL)
        │       miss ↓
        ├─ MemoryManager.buildContextString(goal)
        ├─ ActionExecutor.getUiSnapshot()   ← UIObserver (max 2000 chars)
        ├─ GroqClient.plan(goal, ui, memory) ← LLM
        │       └─ on fail → GeminiClient.plan(...)
        └─ runStepsInternal(steps)
                per step:
                ├─ AppPermissionManager.evaluate() → Allow/Deny/Confirm
                ├─ PolicyEngine.classify() → RiskLevel
                ├─ LOW/MEDIUM → ActionExecutor.execute(step)
                └─ HIGH/CRITICAL → suspendForApproval() → user → execute
                after all steps:
                ├─ MemoryManager.remember(goal, "success", app)
                └─ SkillLearner.learnFromSuccess(goal, steps, app)
```

---

## 5. Room Database

**File:** `data/db/AURADatabase.kt` | **Version:** 1

| DAO | Entity | Key queries |
|---|---|---|
| `TaskLogDao` | `TaskLogEntity` | insert, updateStatus, getAll flow |
| `MemoryDao` | `MemoryEntry` | insert, getAllList, getByApp, getByKeyword, deleteOldest |
| `SkillPackDao` | `SkillPackEntity` | findByKeyword, findBySkillId, upsert, incrementUsage |
| `AuditLogDao` | `AuditLogEntity` | insert, getAll flow |
| `MacroDao` | `MacroEntity` | insert, getAll flow |
| `PermissionRuleDao` | (inline model) | upsert, getByPackage |

---

## 6. LLM Prompt Structure

```
[system]: compact rules (~70 tokens)
[user]:
  [MEMORY: 1."<past goal>"→<outcome>(<app>)|...]   ← only if recall hits
  Goal: <user goal>

  [UI_CONTENT_START]
  <visible screen text, max 2000 chars>
  [UI_CONTENT_END]
```

LLM responds with a JSON array of ActionStep objects only — no prose.

---

## 7. UI Screens & ViewModels

| Screen | ViewModel | Purpose |
|---|---|---|
| `HomeScreen` | `HomeViewModel` | Main command UI, task state |
| `DashboardScreen` | `DashboardViewModel` | Stats & quick actions |
| `SettingsScreen` | `SettingsViewModel` | API keys, permissions, app rules |
| `PermissionsScreen` | `PermissionsViewModel` | Android permission request flow |
| `SkillStoreScreen` | `SkillStoreViewModel` | Browse/install skill packs |
| `TaskLogScreen` | `TaskLogViewModel` | Audit log |

Navigation: single-activity, Compose NavHost in `ui/AURAApp.kt`.

---

## 8. Dependency Injection

| Module | Provides |
|---|---|
| `di/DatabaseModule.kt` | AURADatabase, all DAOs |
| `di/NetworkModule.kt` | Retrofit (Groq), OkHttpClient with auth header |

GroqClient uses shared Retrofit. GeminiClient builds its own (different base URL).

---

## 9. Coding Conventions

- **DB calls** → always in `withContext(Dispatchers.IO)` or DAO `suspend fun`
- **UI state** → `StateFlow` from ViewModel, collected with `collectAsStateWithLifecycle`
- **Risk** → always via `PolicyEngine.classify()`, never hardcoded in screens
- **New action type** → add constant in `ActionStep.companion` + when-branch in `ActionExecutor.execute()`
- **New screen** → add route in `AURAApp.kt` NavHost + new ViewModel
- **New DB table** → entity + DAO + register in `AURADatabase.kt` + bump version + migration
- **Skill learning** → use `SkillLearner.learnFromSuccess()`, never insert `SkillPackEntity` manually

---

## 10. Large Files to SKIP Unless Working On Them Directly

| File | Size | Why skip |
|---|---|---|
| `ui/screens/HomeScreen.kt` | 26 KB | Pure Compose UI |
| `ui/screens/PermissionsScreen.kt` | 20 KB | Pure Compose UI |
| `ui/screens/SettingsScreen.kt` | 20 KB | Pure Compose UI |
| `skills/SkillSeeder.kt` | 12 KB | Static seed data only |
| `voice/VoiceManager.kt` | 11 KB | TTS/STT wiring |
| `voice/WakeWordService.kt` | 11 KB | Background service |
| `voice/FloatingBubble.kt` | 10 KB | Overlay UI only |
| `SKILL-SYSTEM.md` | 66 KB | Design doc |
| `deep-research-report.md` | 46 KB | Research doc |
| `implementation-plan.md` | 41 KB | Planning doc |

---

## 11. Future Work (Deferred � Do When Ready)

### Supabase Realtime � Live Skill Push Notifications

**What it does:**
Instead of polling every few hours, Supabase pushes a WebSocket notification the instant a community skill changes (new version, verified, marked stale). Every phone gets the update silently within seconds.

**Why deferred:**
- `UpdateCheckWorker` + `SkillUpdater.checkForCloudUpdate()` already cover the use case via periodic polling.
- A persistent WebSocket costs battery � not worth it until there are many active users.
- Free tier supports 200 concurrent connections (sufficient for early launch).

**How to implement:** Create `cloud/SupabaseRealtimeManager.kt`, subscribe to `skill_packs` UPDATE events via `supabase.realtime.channel()`, silently upsert newer versions into Room + promote to cache. Call `subscribeToSkillUpdates()` from `AURAApplication.onCreate()` after `ensureSession()`.

---

### Memory Sync + pgvector

**What it does:**
Replace the in-memory cosine loop in `MemoryManager.recall()` with a Postgres `pgvector` nearest-neighbor query. Scales to millions of memories and enables cross-device memory sync.

**Why deferred:** `pgvector` extension requires the Supabase Pro plan.

**SQL when upgrading:**
```sql
CREATE EXTENSION IF NOT EXISTS vector;
ALTER TABLE memory ADD COLUMN IF NOT EXISTS embedding vector(384);
CREATE INDEX ON memory USING ivfflat (embedding vector_cosine_ops);
```