<div align="center">

<img src="https://img.shields.io/badge/AURA-Autonomous%20Android%20Agent-6C63FF?style=for-the-badge&logo=android&logoColor=white"/>

# AURA — Autonomous Universal Relief Agent

> **A secure, permission-gated AI agent that understands what you mean — and gets it done across any Android app.**

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=flat-square)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android%2010%2B-green?style=flat-square&logo=android)](https://developer.android.com)
[![Language: Kotlin](https://img.shields.io/badge/Language-Kotlin-orange?style=flat-square&logo=kotlin)](https://kotlinlang.org)
[![Status: In Development](https://img.shields.io/badge/Status-In%20Development-yellow?style=flat-square)]()

**Say it once. AURA handles the rest.**

*Pay Rahul · Book a ride · Send a message · Set a reminder — all with a single voice command.*

</div>

---

## Contents

- [What is AURA?](#what-is-aura)
- [Features and status](#features-and-status)
- [Architecture](#architecture)
- [Tech stack](#tech-stack)
- [Permission and risk engine](#permission-and-risk-engine)
- [Action schema](#action-schema)
- [Task state machine](#task-state-machine)
- [Security and privacy](#security-and-privacy)
- [Developer setup](#developer-setup)
- [Project structure](#project-structure)
- [Docs](#docs)
- [Contributing](#contributing)
- [License](#license)

---

## What is AURA?

**AURA** is an AI-powered Android agent that bridges the gap between what you *say* and what your phone *does*. Instead of navigating through five apps to finish a task, just tell AURA:

```
"Pay Rahul ₹2,000 for dinner"
"Send my team the meeting notes from Gmail"
"Order my usual lunch from Zomato"
```

AURA uses Android's **Accessibility Service** to observe and control apps on your device, an **LLM** to plan the steps, and a **risk engine** that always pauses before anything sensitive — so you stay in control.

> No root required. Works with any app.

---

## Features and status

| Feature | What it does | Status |
|---|---|---|
| LLM planning | Turns a goal into UI steps (Groq, with Gemini as fallback) | Built |
| Human in the loop | Pauses for approval before messages, payments or deletions | Built |
| Works across apps | Controls any app through the Accessibility API | Built |
| Learned skills | Saves successful plans so repeat goals skip the LLM; RAM → disk → DB cache | Built |
| Community skill store | Share and download skills through Supabase | Built (optional) |
| Long-term memory | Recalls similar past tasks, fully offline | Built |
| Voice assistant | Say "Hey AURA" from any app: a popup listens, runs the task, asks before risky steps (tap or say yes/no) | Built (online STT) |
| Biometric gate | Fingerprint/face before critical actions | Built |
| Macros | Run a named list of goals in sequence | Built |
| Dashboard and task log | Stats and audit history | Built |
| Self-update | Checks GitHub Releases and installs the new APK | Built |
| App shortcuts | 50+ built-in skills for 30+ apps (WhatsApp, Spotify, YouTube, Maps, Uber, Amazon, settings…) using intents and links instead of tapping; updated from GitHub daily, no app update needed | Built |
| Notification actions | Reply from a notification, read your latest messages, "what's playing" (optional notification access) | Built |
| Self-healing skills | A stored skill that fails is reported and re-planned by the LLM | Built |
| Offline speech | Vosk on-device STT | Planned (stub) |
| On-device LLM | Gemma / Phi-3 for offline planning | Planned |
| Low-power wake word | Picovoice Porcupine | Planned |

---

## Architecture

```
 Voice / text input
        │
        ▼
 TaskManager ──► PromptInjectionDefender
        │
        ├─► SkillRouter  (RAM → disk → Room)  ── hit ──► stored steps
        │        miss
        ▼
 MemoryManager + UIObserver snapshot
        │
        ▼
 GroqClient.plan  ──(on failure)──►  GeminiClient.plan
        │
        ▼
 For each step:  AppPermissionManager ─► PolicyEngine (risk)
        │             LOW / MEDIUM ─► ActionExecutor ─► Android apps
        │             HIGH / CRITICAL ─► user approval (+ biometric) ─► ActionExecutor
        ▼
 MemoryManager.remember  ·  SkillLearner saves new plans
```

| Component | Role |
|---|---|
| `TaskManager` | Orchestrates a task and its state machine |
| `SkillRouter` / `skills/` | Finds a stored skill so the LLM call can be skipped |
| `GroqClient` / `GeminiClient` | Plan steps from the goal, screen text and memory |
| `PolicyEngine` | Classifies each step's risk |
| `ActionExecutor` | Performs taps, typing, swipes via the AccessibilityService |
| `UIObserver` | Reads the live screen (up to 2000 chars) |
| `MemoryManager` | Offline embeddings to recall similar past tasks |

Full code map for contributors and AI tools: [AGENTS.md](AGENTS.md).

---

## Tech stack

**Android app:** Kotlin, Jetpack Compose (Material 3), Hilt, Coroutines + Flow, Room, WorkManager, DataStore, Retrofit + OkHttp, kotlinx.serialization, AndroidX Biometric, AndroidX Security (EncryptedSharedPreferences).

**Cloud (optional):** Supabase — Auth (anonymous), Postgrest, Realtime.

**LLMs (free tiers):**

| Model | Provider | Role |
|---|---|---|
| `openai/gpt-oss-120b` | Groq | Default |
| `openai/gpt-oss-20b` | Groq | Fast fallback |
| `qwen/qwen3.8-27b` | Groq | Last Groq fallback |
| `gemini-3.8-flash` | Google AI Studio | Fallback when Groq fails or has no key |

Add your keys in the app's **Settings** screen (free keys: console.groq.com, aistudio.google.com).

**Speech:** Android `SpeechRecognizer` (STT and wake-word loop), Android `TextToSpeech`.

**CI:** GitHub Actions — debug build, unit tests, release.

---

## Permission and risk engine

Every planned step is classified before it runs:

| Risk | Example actions | Behaviour |
|---|---|---|
| **LOW** | Open app, scroll, navigate, read text | Runs automatically |
| **MEDIUM** | Read messages, draft text | Runs automatically, logged |
| **HIGH** | Send message, upload file, delete data | Waits for your approval |
| **CRITICAL** | Send money, change password, buy item | Approval + biometric |

You can also set per-app rules (always allow / always deny / always confirm) in Settings.

**Example — sending a WhatsApp message (HIGH risk):**

```
┌─────────────────────────────────┐
│     SEND WHATSAPP MESSAGE       │
│                                 │
│  To:   Rahul Sharma             │
│  Text: "I will arrive at 7 PM." │
│                                 │
│      [ Deny ]    [ Allow ]      │
└─────────────────────────────────┘
```

---

## Action schema

The planner returns a JSON array of steps:

```json
[
  { "action": "open_app",     "pkg": "com.whatsapp" },
  { "action": "find_element", "text": "Rahul Sharma" },
  { "action": "tap",          "text": "Rahul Sharma" },
  { "action": "type",         "input_text": "I am on my way!" },
  { "action": "confirm_send", "message": "Send this to Rahul?", "risk": "HIGH" }
]
```

**Supported actions:** `open_app` · `tap` · `type` · `swipe` · `scroll` · `back` · `home` · `find_element` · `wait` · `confirm_send` · `read_text` · `press_key` · `clarify`

---

## Task state machine

```
[Created] → [Planning] → [Executing] ──(HIGH/CRITICAL)──► [WaitingForUser]
                              ▲                                   │
                              └────────── user approves ──────────┘
                              │
                         [Verifying]
                         /         \
                   [Failed]     [Completed]

Any state ──(stop)──► [Cancelled]
```

---

## Security and privacy

- **Risk gating** — HIGH steps need approval; CRITICAL steps need biometric auth (`BiometricPrompt`).
- **Prompt-injection defence** — the goal is sanitized, and screen text is fenced off from instructions in the prompt.
- **Encrypted API keys** — stored with `EncryptedSharedPreferences` (AES-256-GCM, Android Keystore).
- **TLS** — all network calls use HTTPS.
- **Audit log** — every auto-run, approved or denied step is recorded.
- **Emergency stop** — the STOP button in the app, Cancel in the popup, or Stop in AURA's notification.
- **Planned:** encrypting the Room database.

Permissions used: Accessibility service, microphone, overlay (floating bubble), notifications, foreground service, start on boot, biometric, internet, and install packages (for self-update).

---

## Developer setup

**Prerequisites:** Android Studio (recent), JDK 17, an Android 10+ (API 29) device or emulator.

```bash
# 1. Clone
git clone https://github.com/krish01info/AURA-.git
cd AURA-

# 2. (Optional) build-time keys: copy .env.example values into android/local.properties.
#    Or skip this and enter your Groq / Gemini key in the app's Settings screen.

# 3. Build and run
cd android
./gradlew assembleDebug        # or open android/ in Android Studio
./gradlew testDebugUnitTest    # unit tests

# 4. On the device: Settings → Accessibility → AURA → turn on
# 5. In AURA: add a Groq (or Gemini) key in Settings, then turn on Voice mode
#    and say "Hey AURA, open YouTube" from any app.
```

---

## Project structure

```
AURA-/
├── android/                     Android app (single module)
│   └── app/src/main/java/com/aura/
│       ├── accessibility/       Screen reading, node finding
│       ├── agent/               Task lifecycle (TaskManager, ActionExecutor, SkillRouter)
│       ├── cloud/               Supabase auth + skill store client
│       ├── data/                Room DB (db/) and data classes (model/)
│       ├── di/                  Hilt modules
│       ├── llm/                 Groq + Gemini clients
│       ├── macros/              Macro runner
│       ├── memory/              Offline memory + embeddings
│       ├── policy/              Risk engine, app rules, audit, biometric
│       ├── skills/              Skill caches, learning, health
│       ├── ui/                  Compose screens + ViewModels
│       ├── update/              Self-update from GitHub Releases
│       └── voice/               STT/TTS, wake word, floating bubble
├── docs/
│   ├── design/                  SKILL-SYSTEM.md
│   ├── plans/                   implementation-plan.md, MVP.md, roadmap.md
│   └── research/                deep-research-report.md
├── .github/workflows/           CI and release
├── AGENTS.md                    Code map for contributors and AI tools
├── CLAUDE.md                    Points Claude Code at AGENTS.md
├── CHANGELOG.md
└── LICENSE
```

Each package folder has a short `package.md` describing its files.

---

## Docs

| Doc | What's in it |
|---|---|
| [AGENTS.md](AGENTS.md) | Code map, data flow, conventions — start here |
| [docs/design/SKILL-SYSTEM.md](docs/design/SKILL-SYSTEM.md) | Skill system design |
| [docs/plans/implementation-plan.md](docs/plans/implementation-plan.md) | Phase-by-phase build plan |
| [docs/plans/MVP.md](docs/plans/MVP.md) | MVP scope |
| [docs/plans/roadmap.md](docs/plans/roadmap.md) | Deferred work (Supabase Realtime, pgvector) |
| [docs/research/deep-research-report.md](docs/research/deep-research-report.md) | Background research |

---

## Contributing

Contributions are welcome.

1. Fork the repo and create a branch: `git checkout -b feature/your-feature`
2. Read [AGENTS.md](AGENTS.md) and the `package.md` of the package you're changing
3. Add tests for new behaviour, and update the `package.md` if you add or rename classes
4. Open a pull request

**Most wanted:** skills for popular Indian apps, unit tests (`policy/`, `agent/`, `skills/`), on-device LLM, Hindi/Tamil/Bengali support.

---

## License

Licensed under the **Apache License 2.0** — see [LICENSE](LICENSE). Apache 2.0 was chosen for its permissive terms and explicit patent grant, so AURA is safe for both open-source and commercial use.

---

<div align="center">

*AURA — make mobile automation as easy as talking to a helpful assistant, without giving up control or security.*

</div>
