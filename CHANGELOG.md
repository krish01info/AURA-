# AURA Changelog

All notable changes are documented here.  
Format: `## [vX.Y.Z] - YYYY-MM-DD`

---

## [Unreleased]

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
