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
| SkillLearner.kt | Saves/updates a variant after a successful LLM plan (Room + disk + RAM + cloud upload); returns its id. `uploadPending()` (app start) retries uploads that failed. |
| CloudSkillVetting.kt | Checks a downloaded community skill before it may run: known actions, allowed intents/link schemes, and no Send/Pay/Delete-type tap or UPI link without a `confirm_send` first. Unit-tested. |
| SkillIdentity.kt | `generateCanonicalId` (task/intent id), `variantId`, intent heuristics. |
| SkillCache.kt | L1 RAM: LRU of the ~20 most-used variants (warmed at startup). |
| SkillDiskCache.kt | L2 disk: one JSON file per variant + keyword index (`filesDir/skill_disk_cache/`). Warmed with the next 200. |
| SkillHealthMonitor.kt | Success/failure counts; BROKEN (≥2 failures and < 60%) → stale + evicted from RAM/disk. |
| SkillUpdater.kt | Called by TaskManager after each stored-skill run; DEGRADED → checks cloud for a newer version. `markWrong()` (popup "Wrong"): deletes a skill learned on this phone, switches off a built-in/downloaded one. |
| SkillRepository.kt | Data access for the Skill Store/Dashboard UI (not used for lookup). |
| SkillSeeder.kt | Built-in skills ("app shortcuts") from `assets/app_shortcuts.json` (or a newer downloaded copy). Each launch: insert missing, refresh changed (counters kept), delete removed. Also `ShortcutFile`/`ShortcutDef`. |
| AppShortcutsUpdater.kt | Daily `@HiltWorker`: downloads `app_shortcuts.json` from `main` on GitHub; a valid file with a higher `version` is saved to `filesDir` and re-seeded. |
| OsFingerprint.kt | Android API / version group, OEM skin, device, installed app versions. |
| OsMigrationHandler.kt | On Android upgrade, marks old-version-group variants stale. |

## App shortcuts (`android/app/src/main/assets/app_shortcuts.json`)
- ~50 built-in skills for 30+ apps (WhatsApp, Spotify, YouTube, Maps, Uber, Amazon, Gmail,
  settings panels…), mostly intents/links/media keys instead of tapping.
- **Add or fix an app = edit the JSON, bump `version`, merge to `main`.** Phones fetch it within a day.
- `AppShortcutsTest` (CI) checks every entry: patterns usable, every step slot filled by every
  pattern, known actions, HIGH risk has `confirm_send`, intents on `ActionExecutor`'s allow-list.
- Specific phrases beat generic ones ("play {query} on spotify" over "play {query}"), so a
  generic fallback entry is fine.

## Rules
- Never insert a `SkillPackEntity` by hand; use `SkillLearner.learn()` (or seeds).
- A pattern must have fixed words and no adjacent slots (`{a} {b}`) — `SkillMatcher.isUsable`.
- A slot never swallows a second command ("open {app}" doesn't match "open whatsapp **and send** hi"), except free-text slots (`*message*`, `*text*`, `*content*`).
- In patterns "on"/"in" are interchangeable and may be followed by "the"; app spellings are unified (playstore → play store, you tube → youtube).
- Changing the entity → bump `AURADatabase` version with an auto-migration (needs the old
  schema JSON in `android/app/schemas/`), and update `docs/cloud/supabase_skill_packs.sql`.
- Live tasks (`listen` steps) are skills too: `SkillMatcher.fill/templatize/slotsInSteps` recurse into `triggers[].words/then`, `stop_words` and `on_stop`, so a trigger word can be a slot ("take a photo when i say {photo_word}"). Built-in live shortcuts must keep every trigger step LOW/MEDIUM (`AppShortcutsTest`).
