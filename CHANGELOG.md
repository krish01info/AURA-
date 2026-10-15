# AURA Changelog

All notable changes are documented here.  
Format: `## [vX.Y.Z] - YYYY-MM-DD`

---

## [Unreleased]

### Fixed — safety
- CRITICAL steps (payments) now really need fingerprint/face: `MainActivity` is a `FragmentActivity`, so the
  BiometricPrompt path runs; before, the in-app Allow button approved them without it
- An "always allow" rule no longer skips the fingerprint check for CRITICAL steps
- Permission rules with a limit ("allow GPay up to ₹500") or a contact list no longer match when the amount or
  contact can't be checked; rules can name a whole task ("send_money"), not only a step ("tap")
- "₹5,000" was read as 5 (payment anomaly check); the average-payment check now parses amounts properly
- A tap on Send / Pay / Post / Delete… with no confirm_send before it now asks first (the planner's rule is
  no longer the only guard); after a fingerprint approval inside AURA, the task's app is reopened first
- Screen text can no longer fake the `[UI_CONTENT_END]` marker to step out of the screen-data block
- Learned skills uploaded to the cloud no longer carry the raw request ("send hi to Rahul") in `description`

### Fixed — features
- Floating mic bubble: tapping it did nothing (the tap called `performClick()` with no listener)
- Accessibility service: reports view ids (`flagReportViewIds`), so tapping by id works; settings link fixed;
  no longer asks for key-event filtering it never used
- Update installer never opened on Android 13+ (download-complete receiver must be exported)
- Update worker shares notification id 2001 with the "Hey AURA" notification; now 2002, and the in-app banner
  gets the result
- Macros started the next goal before the previous one finished (cancelling it) and didn't stop on failure
- Disk skill cache could overwrite its keyword index with a single entry
- OS upgrade check ran after the RAM cache warm-up (stale skills stayed cached); same-class upgrades
  (Android 14 → 15) no longer mark skills stale
- "Call Mom on WhatsApp" was filed as send_message; "send an email" as read_inbox
- Dashboard: blocked-apps count was always 0; success rate counted running tasks
- Permissions: an "Always allow" rule with no app and no action (= allow everything) can't be saved
- Debug test buttons (they message "Rahul") only in debug builds

### Changed — model settings (per Groq / Google docs)
- Gemini fallback: `gemini-2.5-flash` (shuts down 2026-10-16) → `gemini-3.8-flash`, thinking level "low",
  default temperature, 8192 output tokens (thinking counts toward the limit)
- Groq: temperature 0.6 (Groq recommends 0.5–0.7 for reasoning models), `max_completion_tokens` 4096,
  reasoning text not returned; Qwen gets `reasoning_format: hidden` (required with JSON mode) and top_p 0.95

## [v0.3.0] - 2026-10-11

First signed release. From now on AURA updates itself from GitHub Releases (Settings shows the update),
keeping your skills and settings. **Uninstall the old AURA once before installing this version** —
older builds were signed with a different, temporary key.

### Changed — smaller, signed release builds
- Release APK is shrunk (about 24 MB, was 107 MB) and signed with one permanent key
- Only ARM native code is included (phones), not the x86 emulator copies
- First-time setup offers the offline voice model download (36 MB, optional)

### Added — live tasks (keep listening inside any app)
- Say "voice camera", "take a photo when I say cheese", "record a video and take photos when I say capture",
  "hands free scrolling", "hands free reels" or "hands free music": AURA keeps listening offline and acts on each word
  until you say "stop"
- New `listen` step (triggers / stop_words / on_stop); the planner can create live tasks for any app, saved as skills
- Offline keyword spotting with Vosk in AURA's own process (Indian-English model, ~36 MB, downloaded on first use);
  tells you if the app in front blocks the microphone
- Safety: steps that run on a word never send, pay or delete; anything needing approval is approved once up front
- Ends on "stop", leaving the app, 3 failed words, a blocked mic, or Stop in the popup / notification

### Added — popup controls and phone-test fixes
- Stop button while working, Wrong after Done (forgets that skill), waits for app locks ("Unlock the app")
- "Call X" places the call; two-part commands no longer stop after opening the app; more Play Store phrases;
  saying "no" asks what to do instead; calmer mic
- Fixed: "send my notes to Rahul", "add a new task" were rejected as attacks
- Cloud: downloaded skills are vetted before running; rows are owner-only; failed uploads retry

### Added — app shortcuts for many apps
- Built-in skills are now data: `assets/app_shortcuts.json` (53 skills, 30+ apps) — WhatsApp, Telegram, Instagram, X, Spotify, YouTube Music, JioSaavn, YouTube, Maps, Uber, Amazon, Flipkart, Play Store, Gmail, Calendar, Camera, Zomato, UPI, 8 settings panels…
- Phones re-download the file from GitHub daily (`AppShortcutsUpdater`); a higher `version` replaces built-ins without an app update. Removed entries are deleted.
- New actions: generic `intent` (allow-listed: nothing calls, pays, deletes or installs by itself), `media` (play/pause/next/previous/volume), `now_playing`, `reply` (from the notification, asks first), `read_notifications`
- Optional notification access (Settings) — messages stay in memory only
- "play X on spotify" beats the generic "play X": the more specific phrase wins, even when the generic skill is in RAM
- CI test validates every shortcut (patterns, slots, actions, risk, intent allow-list)

### Fixed
- Background update checks never ran: WorkManager had no Hilt worker factory (`UpdateCheckWorker` couldn't be created)

### Fixed — wrong taps and rough mic
- Tapping a name no longer hits the search box that shows the same text: taps and checks skip text boxes and prefer exact, tappable matches
- A tap that doesn't change the screen now fails (and recovers) instead of carrying on on the wrong screen
- Typing goes only into the named field (view id or label); if it isn't on screen nothing is typed (was: whatever box had focus, e.g. the search bar)
- WhatsApp skills confirm the chat is open before typing; "message sent" checks ignore text still in the input box
- Mic: on-device recognizer for the wake word when available; system beeps hidden on restarts; no listening during tasks; no gap after "Hey AURA"; live text while speaking; one retry if nothing heard
- "Voice narration" setting now works (off by default) — steps aren't read aloud unless enabled

### Added — reliability
- Mid-task recovery: a failed step makes the LLM plan only the remaining steps from the current screen (max 2), instead of failing
- `verify` step: "Done" means the right app/text is actually on screen; only verified paths are saved as skills
- Shortcut actions via Android intents: `dial`, `sms`, `alarm`, `timer`, `open_link` (wa.me, YouTube search, Maps directions); new built-in skills for call, SMS, alarm, timer
- Contact lookup: "Mom" / "rahul" → exact contact + number; asks when two people match (READ_CONTACTS, optional)
- Speech language setting (phone default, English (India), Hindi, English (US)); popup shows what was heard with Cancel
- Screen snapshot is role-tagged (`[button] Send #id`), deeper, and built only when planning (was rebuilt on every event, 5 levels deep)
- Waits end as soon as the screen settles instead of fixed sleeps


### Changed — skill system (database v7)
- Skills are stored per **variant**: task × OEM skin × Android version group × app major version, so different phones keep their own steps
- Stored skills run with **no LLM call**: phrase patterns ("send {message_text} to {contact_name} on whatsapp") are matched on the phone and fill the step template
- One lookup chain: RAM (most used) → disk → Room → cloud (compatible variants only) → LLM creates, then saved to every layer
- The LLM call also returns intent, params and patterns, so learning a skill costs no extra API call
- A failing stored variant is re-planned and replaced for this phone only (version + 1); new phrasings are shared across variants
- Room auto-migration 6 → 7 keeps all other data; old unsafe skill rows are cleared
- Cloud schema: `docs/cloud/supabase_skill_packs.sql`

### Fixed — skills
- Disk cache never stored anything (`SkillPackEntity` wasn't `@Serializable`)
- Seed skills tapped literal `{contact_name}` (placeholders were never filled); learned skills replayed old contacts/messages
- Any shared word (e.g. "open") could trigger an unrelated skill — a phrase pattern must now match
- Repairs were thrown away (an existing skill ID blocked saving the fixed plan)
- A single failure could permanently disable a skill; now needs ≥2 failures
- Seeds had invented usage/verified stats; removed the Google Pay seed (it never completed a payment)

### Fixed — voice assistant and tasks
- Voice never worked on Android 11+: added `<queries>` so the speech recognizer (and other apps for `open_app`) are visible
- Wake-word loop restarted the mic every 0.3 s, so "Hey AURA" could never be heard; now restarts only when a session ends
- The Groq key typed in Settings was ignored (only the empty build-time key was sent); keys now come from `ApiKeyStore`
- All three Groq models AURA used were retired by Groq; switched to gpt-oss-120b → gpt-oss-20b → qwen3.8-27b, and Gemini fallback to gemini-2.5-flash (it was never called before)
- Planner prompt asked for an array in JSON-object mode and never named the step fields; now one shared prompt with the exact keys
- Failed steps were reported as "Task completed"; tasks now stop at the first failed step and say why
- Skills were saved before running (failed plans got cached); now saved only after success, and failing stored skills are re-planned
- Removed the keep-alive service that requested a microphone foreground service from the background (crashes on Android 14+)

### Added
- Google-Assistant-style popup over any app: "Hey AURA" → listen → run → Allow/Deny (tap or say yes/no) → result
- "Hey AURA, open YouTube" in one sentence; "Hey AURA, stop" cancels a running task
- Gemini key field in Settings; clear spoken messages when Accessibility is off or no key is set

### Changed
- Repo layout: design, plan and research docs moved into `docs/`; AGENTS.md slimmed to an index with per-package `package.md` files
- README corrected to match the code (no Python backend; real LLM models, actions, permissions; feature status column)

### Added
- `CLAUDE.md`, `.claude/settings.json` (keeps large research docs and build output out of AI context), root `.gitignore`, `docs/plans/roadmap.md`
- `GEMINI_API_KEY` in `.env.example`

### Fixed
- Broken characters in README and AGENTS.md; IDE files and compiler logs no longer tracked

---

## [v0.2.0] - 2026-09-30

### Added
- Complete OTA Updater (GitHub Releases)
- Automated CI pipeline without keystore or secrets
- Automatic check for updates on every app launch
- Check Now button in Settings

---

## [v0.1.0] - 2026-09-30

### Added
- Core accessibility agent (AURAAccessibilityService)
- Action primitives: open_app, tap, type, swipe, scroll, back, home
- TaskManager state machine (Created → Planning → Executing → Completed)
- Groq LLM integration with 3-model cascade (Llama 70B → 8B → Gemma)
- PolicyEngine — risk classification (LOW / MEDIUM / HIGH / CRITICAL)
- Biometric confirmation gate for HIGH/CRITICAL actions
- Emergency stop button (floating overlay)
- Task history screen with audit log (Room DB)
- Settings screen — Groq API key (AES-256 encrypted storage)
- Phase 1 test panel — "Open WhatsApp" + full message flow (no LLM)
- OTA updater — checks GitHub Releases on every app launch + every 12 hrs
- GitHub Actions CI (build on PR) + auto-release workflow (push tag → APK)

---

<!-- Template for next release:

## [vX.Y.Z] - YYYY-MM-DD

### Added
- 

### Changed
- 

### Fixed
- 

-->
