# AURA reliability plan (2026-10-11)

Goal: more commands succeed on a real phone, and "Done" only when it really worked.
Status: parts 1–5 implemented; Porcupine (part 5b) waits on the owner's Picovoice key.

| Part | What | Main files | Extra LLM calls |
|---|---|---|---|
| 1 | Recover mid-task + verify | `agent/TaskManager`, `llm/PlannerPrompt`, `agent/ActionExecutor` | Only after a failed step |
| 2 | Shortcuts (intents / links) | `ActionStep`, `ActionExecutor`, `PolicyEngine`, prompt, seeds | None |
| 3 | Role-tagged screen + settle-based waits | `accessibility/UIObserver`, `ActionExecutor` | None |
| 4 | Contact lookup | `agent/ContactResolver`, `agent/SkillRouter` | None |
| 5 | Voice: language, "heard …" + Cancel | `voice/VoicePrefs`, `WakeWordService`, Settings | None |
| 5b | Porcupine wake word | `voice/WakeWordService` | None — needs owner's key |

## 1. Recovery and verification
- A failed step → `PlannerPrompt.continueMessage` (goal, finished steps, failed step + error,
  current screen) → the LLM returns only the remaining steps. Max 2 recoveries (`MAX_RECOVERIES`).
- Approvals still apply to recovered steps. No API key → the first failure is final.
- `verify` action: app in front (or the app AURA opened for it) and/or text visible, within 5 s.
  The planner ends app tasks with one; `withVerify` adds an app check if missing.
- Learning saves the steps that actually worked (prefix + recovered steps), templatized.
  A stored skill that needed recovery is reported as a failure and replaced for this phone.

## 2. Shortcuts
`dial` (opens dialer), `sms` (opens composer), `alarm`, `timer` (skip UI), `open_link` (wa.me,
YouTube search, Maps directions). LOW risk except `open_link` (MEDIUM); sends still need a
tap gated by `confirm_send`. Values in links are URL-encoded by `SkillMatcher.fill`.

## 3. Screen reading and waits
`UIObserver.getTextSnapshot()` is built only when planning: `[button] Send #id`, `[input] …`,
`[text] …`, `[toggle on] …`, depth ≤ 30, de-duplicated, ≤ 2000 chars. Events only record the
time of the last change; `waitForStable()` replaces fixed sleeps (a `wait` step is a maximum).

## 4. Contacts
`ContactResolver.enrich` turns the spoken name into the exact contact and adds
`{contact_number}` / `{contact_number_digits}` (derived slots, never in patterns). Two equally
good matches → the user is asked. Without READ_CONTACTS everything still works by name.

## 5. Voice
Speech language in Settings; the wake listener uses en-IN when Hindi is chosen; Devanagari
"हे ऑरा" also wakes. The popup shows the recognised command with Cancel for ~1.3 s.

## 5b. Porcupine (to do — owner)
1. Free account at console.picovoice.ai → copy the AccessKey.
2. Create a custom wake word "Hey AURA", platform Android → download the `.ppn` file.
3. Then: add `ai.picovoice:porcupine-android`, put the `.ppn` in `app/src/main/assets/`, add an
   AccessKey field in Settings, and use Porcupine in `WakeWordService` when a key is set.
