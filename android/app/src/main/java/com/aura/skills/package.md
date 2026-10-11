# skills/ package

Learned skills: stored step templates so repeat tasks run with **no LLM call**.
Design background: `docs/design/SKILL-SYSTEM.md` (older; this file wins where they differ).

## Model
- One row in `skill_packs` = one **variant**: a task (`intentId` = app + category + action)
  in one environment (OEM skin + Android version group + app major version) → `skillId`.
- `steps` is a template with `{slot}`s; `phrasePatterns` say how users ask for it
  ("send {message_text} to {contact_name} on whatsapp"). `SkillMatcher` matches a goal
  against the patterns on the phone and fills the slots.

## Flow
- **Load**: `agent/SkillRouter` → RAM → disk → Room → cloud (compatible variants only,
  most specific wins). A hit is promoted to the faster layers; a cloud hit is saved locally.
- **Create**: no match → LLM plans (one call also returns intent, params, patterns) →
  steps run → only on success `SkillLearner.learn()` saves this phone's variant.
- **Update**: each run reports to `SkillUpdater` → `SkillHealthMonitor` (counts, health).
  A failing stored variant is re-planned once; the new plan replaces this phone's variant
  (version + 1). New phrasings are shared with all variants of the task.

| File | Role |
|---|---|
| SkillMatcher.kt | Pure Kotlin: normalize goals, match patterns → params, fill/templatize steps, derive & validate patterns. Unit-tested. |
| SkillLearner.kt | Saves/updates a variant after a successful LLM plan (Room + disk + RAM + cloud upload). |
| SkillIdentity.kt | `generateCanonicalId` (task/intent id), `variantId`, intent heuristics. |
| SkillCache.kt | L1 RAM: LRU of the ~20 most-used variants (warmed at startup). |
| SkillDiskCache.kt | L2 disk: one JSON file per variant + keyword index (`filesDir/skill_disk_cache/`). Warmed with the next 200. |
| SkillHealthMonitor.kt | Success/failure counts; BROKEN (≥2 failures and < 60%) → stale + evicted from RAM/disk. |
| SkillUpdater.kt | Called by TaskManager after each stored-skill run; DEGRADED → checks cloud for a newer version. |
| SkillRepository.kt | Data access for the Skill Store/Dashboard UI (not used for lookup). |
| SkillSeeder.kt | Built-in universal seeds (WhatsApp, YouTube, Maps, Gmail, Zomato), inserted if missing. |
| OsFingerprint.kt | Android API / version group, OEM skin, device, installed app versions. |
| OsMigrationHandler.kt | On Android upgrade, marks old-version-group variants stale. |

## Rules
- Never insert a `SkillPackEntity` by hand; use `SkillLearner.learn()` (or seeds).
- A pattern must have fixed words and no adjacent slots (`{a} {b}`) — `SkillMatcher.isUsable`.
- Changing the entity → bump `AURADatabase` version with an auto-migration (needs the old
  schema JSON in `android/app/schemas/`), and update `docs/cloud/supabase_skill_packs.sql`.
