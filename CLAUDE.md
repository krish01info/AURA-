# AURA — start here

AURA is an Android voice assistant/agent: say **"Hey AURA"** from any app, a popup
listens, an LLM plans UI steps, and the Accessibility service performs them, asking
before risky steps. Kotlin + Compose, single module `android/app`. Owner: krish01info.

The code map, data flow and conventions are in **AGENTS.md** (imported below).
Read it, then only the `package.md` of the package you're changing.

## Current status (updated 2026-10-11)

- ✅ CI builds green (first ever on 2026-10-11 after removing a machine-specific `org.gradle.java.home`).
- ✅ Voice assistant rewritten: `voice/WakeWordService` (flow) + `voice/AssistantOverlay` (popup).
- ✅ Tasks: Groq gpt-oss cascade → Gemini 2.5 Flash fallback; stop at first failed step.
- ✅ Reliability (2026-10-11): mid-task recovery from the current screen (max 2), `verify` steps,
  shortcut actions (dial/sms/alarm/timer/open_link), role-tagged screen snapshot + settle-based waits,
  contact lookup, speech-language setting, "heard: … [Cancel]" popup. Plan: `docs/plans/reliability-plan.md`.
- ✅ Skills (DB v7): one row per **variant** (task × skin × Android group × app major version);
  stored skills run with **no LLM call** via phrase patterns; lookup RAM → disk → Room → cloud → LLM creates.
  See `android/app/src/main/java/com/aura/skills/package.md`.
- ✅ App shortcuts (2026-10-11): built-in skills live in `android/app/src/main/assets/app_shortcuts.json`
  (53 skills, 30+ apps, intents/links/media keys); phones re-download it from `main` daily.
  **Add/fix an app: edit the JSON, bump `version`, merge** — `AppShortcutsTest` validates it in CI.
  New actions `intent` (allow-list), `media`, `now_playing`, `reply`, `read_notifications` (notification access, optional).
- ⚠️ First phone test (2026-10-11) found: commands with two parts only opened the app, "call X" only dialed,
  Play Store search missed, saying "no" got stuck, mic flickered on/off. Fixed in the "device-test fixes" PR
  (compound-command guard in `SkillMatcher`, new `call` action, more Play Store phrases, `ApprovalParser` +
  "what should I do instead?", wake-session backoff). **Not re-tested on the phone yet** — ask the owner.
- ⚠️ Second phone test (2026-10-11, tester): on-screen (floating) mic did nothing (in-app mic worked); "play the
  song" placed a call; YouTube search opened but didn't play; AI didn't know the current screen. Fixed in
  "device-test round 2": floating mic talks to the running service, offline "Hey AURA" when the voice model is
  downloaded, `verify_playing` + YouTube taps the first result, unrequested calls need approval, snapshot has
  `[app]` + long labels. **Not re-tested yet** — get logcat if the mic still fails.
- ✅ Popup controls (2026-10-11): **Stop** on the working banner, **Wrong** after Done (drops that skill, asks
  what you wanted), app-lock wait ("Unlock the app", 45 s, then retries the step). Not phone-tested yet.
- ⚠️ Cloud (Supabase) isn't configured yet. To turn it on: create a Supabase project, run
  `docs/cloud/supabase_skill_packs.sql` (now with owner-only writes), add repo secrets `SUPABASE_URL` +
  `SUPABASE_ANON_KEY` (CI already passes them into the build). Downloads are vetted (`CloudSkillVetting`);
  failed uploads retry at app start.
- ✅ Room schema 7 committed. CI now commits any newly generated schema JSON to the PR branch itself.

## Next steps (in priority order)

1. Re-test on the phone after the device-test fixes; get logcat (`adb logcat -s TaskManager ActionExecutor SkillRouter WakeWordService VoiceManager`) for anything still failing.
2. **Live tasks — built, not phone-tested** (`docs/plans/live-tasks-plan.md`). Owner's choices: words start /
   capture / stop, English (India) model only, no time limit (until "stop"), no mic-test screen. Test on the phone:
   "voice camera" → say capture; "record a video and take photos when I say capture"; "hands free scrolling" in
   Chrome; "hands free reels". If the camera app silences AURA while recording video, build plan Phase 8
   (AURA's own CameraX screen). Possible later: a Settings button to pre-download the voice model.
3. Porcupine wake word (beeps + battery). Blocked on the owner: needs a free Picovoice AccessKey and a custom
   "Hey AURA" keyword file (.ppn, Android) from console.picovoice.ai. Then: add `ai.picovoice:porcupine-android`,
   key field in Settings, run Porcupine in `WakeWordService` instead of the SpeechRecognizer wake loop
   (keep the loop as fallback when no key).
4. More unit tests: `agent/TaskManager` (needs fakes for its ~16 dependencies) and `policy/AnomalyDetector`
   (needs a fake `AuditLogDao`). Done: SkillMatcher, PlannerPrompt, AppShortcuts, ApprovalParser, LockDetector,
   PolicyEngine, PromptInjectionDefender, VariantRanking (SkillRouter's selection), CloudSkillVetting.
5. Cloud: the owner sets up Supabase (see status above); then test upload/download between two phones.

## How to build and ship (cloud sessions have no Android SDK)

- Work on a branch → push → open a PR → GitHub Actions "Android CI" compiles it.
- `gh pr create` (GraphQL) is blocked here: use `gh api repos/krish01info/AURA-/pulls -f title=… -f head=… -f base=main -F body=@file`.
- Raw CI logs are blocked here. On failure the workflow re-posts errors as annotations:
  `gh api repos/krish01info/AURA-/check-runs/<job id>/annotations`.
- Green CI → merge (owner allows merging to `main`): `gh api -X PUT repos/krish01info/AURA-/pulls/<n>/merge -f merge_method=merge`.
- `main` builds upload `aura-debug-apk` (~87 MB, unshrunk) and `aura-release-apk` (~24 MB, R8-shrunk) artifacts.
- **Releases:** bump `versionCode`/`versionName`, add `## [vX.Y.Z] - date` to CHANGELOG.md (= release notes),
  merge, then `git tag vX.Y.Z && git push origin vX.Y.Z`. `release.yml` builds a signed release APK and publishes
  it; phones update through the in-app checker (`update/`).
- **Signing:** every CI/release APK is signed with one permanent key (repo secrets `AURA_KEYSTORE_BASE64`,
  `AURA_KEYSTORE_PASSWORD`; alias `aura`), so updates install over each other. Never commit the key (`*.jks`
  is gitignored). Without the secrets CI warns and uses a throwaway key; `release.yml` fails on purpose.

## Keys and secrets

- In the app: Settings screen → `llm/ApiKeyStore` (encrypted). Code must read keys only via `ApiKeyStore`.
- In CI: repo secrets `GROQ_API_KEY`, `GEMINI_API_KEY`, `SUPABASE_URL`, `SUPABASE_ANON_KEY` (all optional) are baked into built APKs; `AURA_KEYSTORE_BASE64` + `AURA_KEYSTORE_PASSWORD` sign them. Only the owner can set them.
- The repo is **public**: never commit keys, never write them into docs or memory.

## Pitfalls already hit (don't repeat)

- Android 11+ hides other apps/services unless declared in the manifest `<queries>` (speech recognizer, launcher apps).
- A microphone foreground service may only start while AURA is in the foreground (Android 14+). No mic FGS from boot.
- Only one SpeechRecognizer at a time — all listening goes through `WakeWordService`.
- Groq/Gemini retire models; CI warns when a `GroqClient.MODEL_CASCADE` model disappears.
- The planner prompt (`llm/PlannerPrompt.SYSTEM`) must list the exact `ActionStep` JSON keys.
- WorkManager is initialized with Hilt's worker factory (`AURAApplication` is a `Configuration.Provider`; the manifest removes the default initializer). New workers: `@HiltWorker` + `@AssistedInject`.
- `app_shortcuts.json` reaches every phone from `main` without review — keep intents on `ActionExecutor.isAllowedIntent`'s list and risky entries behind `confirm_send`.
- Never set `org.gradle.java.home` in `android/gradle.properties`.
- Release builds use R8: a new library may need `-dontwarn`/`-keep` lines in `android/app/proguard-rules.pro`.
  CI builds release on every PR so R8 errors show up there. Reflection-heavy code only fails at run time —
  test a release APK on the phone after adding libraries.
- Native code is ARM-only (`ndk.abiFilters`): an x86 emulator can't run live tasks.
- Skill steps are templates: never save concrete values (contacts, messages) — use `SkillMatcher.templatize`.
  Patterns with adjacent slots (`{a} {b}`) are rejected because the split is ambiguous.
- The disk cache serializes `SkillPackEntity` — it must stay `@Serializable`.
- Local quick check of pure Kotlin (no SDK/Maven here): download `kotlin-compiler-2.0.21.zip` from
  github.com/JetBrains/kotlin/releases and compile the file + a stub; JUnit runs in CI.

@AGENTS.md
