# AURA — start here

AURA is an Android voice assistant/agent: say **"Hey AURA"** from any app, a popup
listens, an LLM plans UI steps, and the Accessibility service performs them, asking
before risky steps. Kotlin + Compose, single module `android/app`. Owner: krish01info.

The code map, data flow and conventions are in **AGENTS.md** (imported below).
Read it, then only the `package.md` of the package you're changing.

## Current status (updated 2026-10-11)

- ✅ CI builds green (first ever on 2026-10-11 after removing a machine-specific `org.gradle.java.home`).
- ✅ Voice assistant rewritten: `voice/WakeWordService` (flow) + `voice/AssistantOverlay` (popup).
- ✅ Tasks: Groq gpt-oss cascade → Gemini 2.5 Flash fallback; stop at first failed step; skills saved only after success.
- ⚠️ The 2026-10-11 voice rewrite is **not yet verified on a real phone**. Ask the owner for results/logcat before assuming it works.

## Next steps (in priority order)

1. Device-test the assistant; fix what the owner reports.
2. Replace the SpeechRecognizer wake loop with Picovoice Porcupine (beeps + battery).
3. Unit tests for `policy/`, `agent/TaskManager`, `llm/PlannerPrompt` (CI runs them; none exist).
4. Real Room migrations — `fallbackToDestructiveMigration()` wipes user data on a version bump.
5. Merge the duplicate skill lookup in `agent/SkillRouter` and `skills/SkillRepository`.

## How to build and ship (cloud sessions have no Android SDK)

- Work on a branch → push → open a PR → GitHub Actions "Android CI" compiles it.
- `gh pr create` (GraphQL) is blocked here: use `gh api repos/krish01info/AURA-/pulls -f title=… -f head=… -f base=main -F body=@file`.
- Raw CI logs are blocked here. On failure the workflow re-posts errors as annotations:
  `gh api repos/krish01info/AURA-/check-runs/<job id>/annotations`.
- Green CI → merge (owner allows merging to `main`): `gh api -X PUT repos/krish01info/AURA-/pulls/<n>/merge -f merge_method=merge`.
- `main` builds upload an installable `aura-debug-apk` artifact. Releases run only on `v*` tags.

## Keys and secrets

- In the app: Settings screen → `llm/ApiKeyStore` (encrypted). Code must read keys only via `ApiKeyStore`.
- In CI: repo secrets `GROQ_API_KEY`, `GEMINI_API_KEY` (optional) are baked into built APKs. Only the owner can set them.
- The repo is **public**: never commit keys, never write them into docs or memory.

## Pitfalls already hit (don't repeat)

- Android 11+ hides other apps/services unless declared in the manifest `<queries>` (speech recognizer, launcher apps).
- A microphone foreground service may only start while AURA is in the foreground (Android 14+). No mic FGS from boot.
- Only one SpeechRecognizer at a time — all listening goes through `WakeWordService`.
- Groq/Gemini retire models; CI warns when a `GroqClient.MODEL_CASCADE` model disappears.
- The planner prompt (`llm/PlannerPrompt.SYSTEM`) must list the exact `ActionStep` JSON keys.
- Never set `org.gradle.java.home` in `android/gradle.properties`.

@AGENTS.md
