# Live tasks — implementation plan

> Status: **proposed** (2026-10-11). Nothing here is built yet.
> Goal: AURA keeps listening *inside any app* and reacts to words you choose.

## 1. What the user gets

> "Hey AURA, open the camera — start recording when I say **start**, take a photo whenever I
> say **click**, stop when I say **stop**."

AURA opens the camera, shows a small "Listening: start · click · stop" pill, and from then on
taps the right button within about 0.3 s of each word. There's **one LLM call the first time and zero after**:
the live task is saved as a skill like any other.

The same feature covers:

| Say | AURA does |
|---|---|
| "open the camera, record on *start*, photo on *click*, stop on *stop*" | camera control while recording |
| "when I say *next*, scroll down" (recipes, reading) | hands-free scrolling |
| "skip reels when I say *skip*" | swipe up on each word |
| "in Spotify, *next* / *back* / *pause*" | media control without the wake word |
| "take a photo when I say *cheese*" (photo mode) | voice shutter |

## 2. Hard constraints (from Android docs and this codebase)

1. **Mic sharing (Android 10+).** Two ordinary apps can't capture at the same time. Video recording uses
   the `CAMCORDER` source, which is *privacy-sensitive*, so the camera wins and others get **silence**.
   A non-system assistant gets no exception; the default-assistant exception applies only to pre-installed apps.
   **The one exception that fits AURA:** *"If the [accessibility] service's UI is on top, both the
   service and the app receive audio input."* AURA is an accessibility service and already draws
   `TYPE_ACCESSIBILITY_OVERLAY` windows. So **a visible AURA overlay must be on screen while listening,
   and the audio must be captured inside AURA's own process.**
   (Source: developer.android.com/media/platform/sharing-audio-input)
2. **`SpeechRecognizer` can't be used for this.** It records inside Google's app, a different app,
   so it gets silenced during recording. It is also too slow (1–2 s per phrase) and beeps.
   → Live tasks need an **in-process, offline keyword spotter**: Vosk (no key) now, Porcupine later.
3. **One mic user at a time inside AURA** (see the CLAUDE.md pitfall). Live mode pauses the
   `WakeWordService` recognizer loop and resumes it afterwards.
4. **Android 11+ `setPrivacySensitive(true)`.** A camera app that sets this blocks *all* concurrent
   capture. Some OEM cameras may do so. We detect it (`AudioRecordingConfiguration.isClientSilenced()`)
   and tell the user instead of failing silently.

Points 1 and 4 can only be confirmed on a real phone, so **Phase 0 is a test before any feature work.**

## 3. Phases

### Phase 0 — Mic test (decision gate, ~2 h + owner test)
A hidden debug screen ("Settings → Developer → Mic test"):
- Starts an in-process `AudioRecord` (16 kHz, `VOICE_RECOGNITION` source) with an
  `AudioRecordingCallback`, and shows a live level meter plus a `silenced: yes/no` flag in an accessibility overlay.
- The owner opens the camera, starts a video and talks. We log: level > 0? `isClientSilenced`?
  Then repeat with the overlay hidden, to prove the overlay is what matters.
- **Result A — audio arrives:** build Phases 1–7 as written.
- **Result B — silenced:** live tasks still work in apps that don't record audio (reading, reels,
  Spotify, photo mode). For video+photo, build **Phase 8** (AURA's own camera screen).

### Phase 1 — Data model: the `listen` step (no DB migration)
A live task is an ordinary plan whose **last step** is `listen`:

```json
{"steps":[
  {"action":"intent","intent_action":"android.media.action.VIDEO_CAMERA"},
  {"action":"wait","wait_ms":1500},
  {"action":"listen","timeout_s":600,"stop_words":["stop"],
   "triggers":[
     {"words":["{start_word}"],"once":true,"then":[{"action":"tap","text":"Record|Start recording|Shutter"}]},
     {"words":["{photo_word}"],"then":[{"action":"tap","text":"Capture|Take photo|Snapshot"}]}
   ],
   "on_stop":[{"action":"tap","text":"Stop|Stop recording"}]}
]}
```

- `ActionStep` gets `triggers: List<Trigger>?`, `stopWords`, `timeoutS`, `onStop: List<ActionStep>?`.
  `Trigger(words, then, once=false, cooldownMs=800)`. They're stored inside the existing `steps`
  JSON column, so the **Room schema doesn't change**.
- `text` alternatives with `|` ("Record|Start recording"), since OEM cameras label buttons differently.
  `NodeFinder` tries each in order. This also helps ordinary taps.
- `SkillMatcher.fill/templatize/slotsInSteps` recurse into `triggers[].then`, `onStop` and
  `triggers[].words`, so trigger words can be slots: pattern "take a photo when I say {photo_word}".
- `PolicyEngine`: `listen` is LOW. Each nested step is classified **when the live task starts**.
  If any is HIGH, the user approves once ("AURA will tap *Send* each time you say *send* — allow?").
  CRITICAL steps are **not allowed** inside triggers.

### Phase 2 — Keyword spotter (Vosk, offline, ~0.3 s)
- Add `com.alphacephei:vosk-android:0.3.47` (Apache-2.0).
- Rewrite the existing `voice/VoskEngine` stub into a **`KeywordSpotter`** that:
  - downloads the model once (Settings button + progress bar). English: `vosk-model-small-en-in-0.4`
    (~36 MB, Indian English); Hindi: `vosk-model-small-hi-0.22`. It follows the existing "Speech language"
    setting and is mirrored as a GitHub Release asset so the URL is under our control;
  - runs `Recognizer(model, 16000, grammar)` with a **grammar of only the trigger words + `[unk]`**,
    which is fast, accurate and ignores everything else said;
  - uses its own `AudioRecord` in AURA's process and registers an `AudioRecordingCallback`, so silencing is detected;
  - emits `Flow<Heard(word, confidence)>`; partial results fire triggers early to keep latency low.
- Matching (pure, unit-tested `TriggerMatcher`): normalize ("click." → "click"), word-level match,
  per-trigger cooldown (800 ms), and ignore words spoken within 300 ms after AURA itself spoke (TTS echo).
- Picks trigger words that are easy to recognize. The planner prompt says to avoid words shorter than 2 syllables
  where possible, and the Settings help suggests "capture"/"cheese" over "click" if misses are frequent.
- Later: Porcupine with custom keywords once the Picovoice key exists (CLAUDE.md next step 3).

### Phase 3 — `LiveTaskRunner` (agent/)
New class, run by `TaskManager` when it reaches a `listen` step:
1. **Pre-flight:** classify every nested step; ask once if needed; check the model is downloaded
   (if not, offer the download, then continue).
2. **Pin targets:** for every `tap` in triggers, find the node **now** and cache its bounds and view id.
   If a target isn't on screen (e.g. Stop appears only after Record), it is resolved lazily on first use.
3. **Unknown camera layout:** if a target can't be found at pre-flight *and* there's a key, make **one**
   LLM call with the screen snapshot: "which element is the record/shutter button?". This saves a
   phone-specific variant (existing variant system) so it never asks again on this phone.
4. **Loop:** `KeywordSpotter.heard` → `TriggerMatcher` → run the `then` steps via `ActionExecutor`.
   Pinned taps use `dispatchGesture` at the cached center if the screen hasn't changed (~50 ms),
   otherwise `NodeFinder` again. A trigger never waits for screen-settle unless its steps include
   navigation.
5. **Stop** on any of:
   - a stop word (runs `on_stop` first)
   - `timeout_s` (default 10 min, max 60)
   - leaving the target app for more than 5 s
   - the overlay ✕ button or the notification "Stop"
   - "Hey AURA, stop"
   - 3 consecutive failed triggers
6. `TaskState.Live(words, firedCount)` is a new state, so the Home screen and overlay can show it.
7. Learning: a live task that ran and was stopped normally (≥1 trigger fired OK) counts as a success and goes to
   `SkillLearner.learn`, as today.

### Phase 4 — Live overlay (voice/)
- `AssistantOverlay.showLive(words)`: a small draggable pill at the screen edge showing
  `● Listening  start · click · stop   ✕`. It flashes the word when heard and shows "mic blocked by
  <app>" if silenced.
- It is `TYPE_ACCESSIBILITY_OVERLAY`, `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL` (touches pass
  through), and stays small and away from the shutter area. **It must stay visible: it is what keeps the mic shared.**
- Haptic tick on each trigger (the camera may be in a noisy place; sound feedback would get recorded).

### Phase 5 — WakeWordService integration
- `Mode.LIVE`: stop the `SpeechRecognizer` wake loop; `KeywordSpotter` owns the mic.
  "aura stop" / "hey aura" are added to the grammar so the wake word still works during live mode.
- Notification shows "Live: listening for start, click, stop" with a Stop action.
- On end: release `AudioRecord`, hide the pill, return to `WAKE`.

### Phase 6 — Planner prompt
- Document `listen` (triggers / stop_words / on_stop / timeout_s, `|` alternatives) in
  `PlannerPrompt.SYSTEM` with the camera example, plus a rule: *goals with "when I say / whenever I say /
  every time I say" → end with one listen step; no verify after it*. Update `PlannerPromptTest`.
- `parsePlan` accepts nested steps. Prompt budget: about +120 tokens.

### Phase 7 — Built-in live shortcuts (`app_shortcuts.json`)
Zero LLM calls on day one:
- camera: video + photo on word; photo on word (selfie timer replacement)
- hands-free scroll: "scroll when I say {next_word}" (any app currently open)
- reels/shorts skip: swipe up on word
- media: next / back / pause words without the wake word, for a set time

`AppShortcutsTest` is extended: trigger steps use known actions, have no CRITICAL risk, have no adjacent-slot
patterns, and words are non-empty.

### Phase 8 — Fallback: AURA camera screen (only if Phase 0 = B)
- `ui/screens/LiveCameraScreen` with CameraX: `Preview` + `VideoCapture` + `ImageCapture` bound together
  (supported on most phones; falls back to taking a frame from the preview when the device can't bind all three).
- AURA owns both the camera and the mic: Vosk listens. Video audio is recorded through the same
  capture where the device allows; otherwise the video is saved **without sound** and the user is told.
- Saves to `DCIM/AURA/` via MediaStore. The planner routes "record… photo when I say…" here when
  the mic test result says the camera app silences AURA (remembered per phone).

### Phase 9 — Tests
- Unit (pure):
  - `TriggerMatcher`: normalization, cooldown, echo window, partial vs final
  - `SkillMatcher`: fill/templatize recursion into triggers
  - `PlannerPrompt`: parsing nested `listen`
  - `NodeFinder` `|` alternative ordering (pure part)
- On-device checklist (owner), each run with logcat:
  - [ ] Phase 0 mic test on the owner's phone: camera video + talking
  - [ ] "record on start, photo on click, stop on stop": 5 photos during a 1-min video
  - [ ] wrong-word speech for 1 min → no false taps
  - [ ] leave the camera mid-task → live mode ends within 5 s
  - [ ] Hindi trigger word with Hindi model
  - [ ] battery: 10 min live mode, note % drop

## 4. Files

| File | Change |
|---|---|
| `data/model/ActionStep.kt` | `LISTEN`, `triggers`, `stopWords`, `timeoutS`, `onStop`; `Trigger` class |
| `skills/SkillMatcher.kt` | recurse into triggers / on_stop / words |
| `voice/VoskEngine.kt` → `voice/KeywordSpotter.kt` | real Vosk, model download, grammar, silencing callback |
| `agent/TriggerMatcher.kt` (new, pure) | word matching, cooldown, echo window |
| `agent/LiveTaskRunner.kt` (new) | pre-flight, pinning, loop, stop rules |
| `agent/TaskManager.kt` | hand off at `listen`; `TaskState.Live` |
| `agent/ActionExecutor.kt` | `tapPinned(bounds)`; `|` alternatives |
| `accessibility/NodeFinder.kt` | `|` alternatives |
| `voice/AssistantOverlay.kt` | `showLive()`, word flash, silenced warning |
| `voice/WakeWordService.kt` | `Mode.LIVE`, notification |
| `policy/PolicyEngine.kt` | `listen` LOW; nested-step pre-approval; no CRITICAL in triggers |
| `llm/PlannerPrompt.kt` | `listen` docs + rule |
| `ui/screens/SettingsScreen.kt` | offline voice model download, mic test (debug) |
| `assets/app_shortcuts.json` | live shortcuts (Phase 7) |
| `ui/screens/LiveCameraScreen.kt` (Phase 8 only) | CameraX fallback |
| `gradle/libs.versions.toml`, `app/build.gradle.kts` | vosk-android (+ CameraX if Phase 8) |
| docs | `agent`, `voice`, `skills`, `llm` package.md; AGENTS data flow; CLAUDE.md; CHANGELOG |

## 5. Risks

| Risk | Mitigation |
|---|---|
| Camera app blocks concurrent mic (`setPrivacySensitive`) | Detect with `isClientSilenced`, tell the user, Phase 8 fallback |
| Overlay hidden by the camera's fullscreen mode | Accessibility overlays sit above app windows; verify in Phase 0 |
| Wrong taps from false triggers (TV, people talking) | Grammar-only recognition, confidence threshold, cooldown, 3-failure stop |
| OEM camera buttons have no labels | `|` alternatives → one-time LLM lookup → phone-specific variant |
| Model download size (~36 MB) | One-time, Wi-Fi prompt, only when live tasks are first used |
| Battery | Vosk small model is light; hard timeout; ends when leaving the app |
| Privacy | Audio never stored or sent; only matched words are logged |

## 6. Cost and order

- API: **1 call** for a new live task (+ at most 1 to find unknown buttons on a new phone), **0** while
  running and **0** on later runs. Built-in live shortcuts: 0 ever.
- Order: Phase 0 → owner test → 1, 2, 3, 4, 5, 6 → 7 → 9 (→ 8 only if needed).
- Rough effort: Phase 0 half a day; Phases 1–7 two to three days; Phase 8 one to two days.

## 7. Decisions for the owner

1. Default words: start / **capture** (recommended over "click": longer, fewer misses) / stop?
2. Voice model: English (India) only, or also download Hindi?
3. Default live timeout: 10 minutes?
4. OK to add a hidden "Mic test" debug screen for Phase 0?
