# skills/ package

Learned skills (stored step lists) so repeat goals skip the LLM. Design: `docs/design/SKILL-SYSTEM.md`.

| File | Role |
|---|---|
| SkillCache.kt | L1: in-RAM LRU of the top 20 skills. |
| SkillDiskCache.kt | L2: one JSON file per skill + keyword index under `filesDir/skill_disk_cache/`. |
| SkillRepository.kt | Lookup L1 → L2 → L3 Room → **L4 Supabase cloud**; CRUD over `SkillPackDao`. Used by the Skill Store. |
| SkillLearner.kt | `learnFromSuccess()` saves a new LLM plan as a skill (OS-tagged, canonical ID). |
| SkillIdentity.kt | Canonical skill ID generation. |
| OsFingerprint.kt | Detects OS class, skin (MIUI / OneUI / …) and API level. |
| OsMigrationHandler.kt | On app start, detects an Android upgrade and marks old-OS skills stale. |
| SkillHealthMonitor.kt | HEALTHY ≥ 0.85 success, DEGRADED ≥ 0.60, else BROKEN. |
| SkillUpdater.kt | Self-healing: `TaskManager` reports each stored-skill run (`recordSuccess` / `handleFailure`); broken skills go stale and get re-planned. |
| SkillSeeder.kt | Static seed skills inserted on first install (large, data only). |

## Rules
- The agent's own lookup is `agent/SkillRouter.kt` (L1–L3, no cloud).
- Create skills only through `SkillLearner.learnFromSuccess()`.
