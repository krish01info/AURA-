# cloud/ package

Supabase layer. Everything here is optional: when `SupabaseModule.isConfigured` is false
(no keys in `local.properties`), calls no-op and the app works offline.

| File | Role |
|---|---|
| SupabaseAuthManager.kt | Anonymous sign-in on first launch; session restored after. `ensureSession()` is called from `AURAApplication.onCreate()`. |
| SupabaseSkillApi.kt | Client for the `skill_packs` table (one row per skill variant): `searchSkills(keywords, osClass, skin)` (L4 lookup, compatible variants only), `getLatestVersion`, `uploadSkill` (upsert per variant), `getTopSkills` (Skill Store), `recordExecution` (telemetry). |

## Rules
- Always check `SupabaseModule.isConfigured` before a network call.
- Client setup lives in `di/SupabaseModule.kt`.
- Cloud schema: `docs/cloud/supabase_skill_packs.sql` (run once in Supabase; idempotent). Keep it in sync with `CloudSkill`.
- Trust: a row belongs to the account that first uploaded it (`owner_id`); only it may change the row, and a trigger stops clients setting `is_verified`/`downloads`. Phones vet every download (`skills/CloudSkillVetting`). Uploading to a variant someone else owns fails quietly — expected.
- Deferred work (Realtime, pgvector): `docs/plans/roadmap.md`.
