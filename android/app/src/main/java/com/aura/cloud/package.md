# cloud/ package

Supabase layer. Everything here is optional: when `SupabaseModule.isConfigured` is false
(no keys in `local.properties`), calls no-op and the app works offline.

| File | Role |
|---|---|
| SupabaseAuthManager.kt | Anonymous sign-in on first launch; session restored after. `ensureSession()` is called from `AURAApplication.onCreate()`. |
| SupabaseSkillApi.kt | Client for the `skill_packs` table: `searchSkills` (L4 lookup), `getLatestVersion`, `uploadSkill`, `getTopSkills` (Skill Store), `recordExecution` (telemetry). |

## Rules
- Always check `SupabaseModule.isConfigured` before a network call.
- Client setup lives in `di/SupabaseModule.kt`.
- Deferred work (Realtime, pgvector): `docs/plans/roadmap.md`.
