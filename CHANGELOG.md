# AURA Changelog

All notable changes are documented here.  
Format: `## [vX.Y.Z] - YYYY-MM-DD`

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
