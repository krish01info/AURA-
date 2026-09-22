<div align="center">

<img src="https://img.shields.io/badge/AURA-Autonomous%20Android%20Agent-6C63FF?style=for-the-badge&logo=android&logoColor=white"/>

#  AURA  Autonomous Universal Relief Agent

> **A secure, permission-gated AI agent that understands what you mean � and gets it done across any Android app.**

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg?style=flat-square)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android%2010%2B-green?style=flat-square&logo=android)](https://developer.android.com)
[![Language: Kotlin](https://img.shields.io/badge/Language-Kotlin-orange?style=flat-square&logo=kotlin)](https://kotlinlang.org)
[![Backend: Python](https://img.shields.io/badge/Backend-Python%203.10%2B-blue?style=flat-square&logo=python)](https://python.org)
[![Status: In Development](https://img.shields.io/badge/Status-In%20Development-yellow?style=flat-square)]()
[![PRs Welcome](https://img.shields.io/badge/PRs-Welcome-brightgreen?style=flat-square)](CONTRIBUTING.md)

<br/>

**Say it once. AURA handles the rest.**

*Pay Rahul � Book a ride � Send a message � Set a reminder � all with a single voice command.*

</div>

---

## ?? Table of Contents

- [What is AURA?](#-what-is-aura)
- [Key Features](#-key-features)
- [Architecture](#-architecture)
- [Tech Stack](#-tech-stack)
- [Permission & Risk Engine](#-permission--risk-engine)
- [Action Schema](#-action-schema)
- [Task State Machine](#-task-state-machine)
- [Security & Privacy](#-security--privacy)
- [Enhancements & Roadmap](#-enhancements--roadmap)
- [Developer Setup](#-developer-setup)
- [Testing Strategy](#-testing-strategy)
- [Contributing](#-contributing)
- [License](#-license)

---

## ?? What is AURA?

**AURA** is  **AI-powered Android agent** that bridges the gap between what you *say* and what your phone *does*. Instead of navigating through 5 apps to complete a task, just tell AURA:

```
"Pay Rahul ?2,000 for dinner"
"Send my team the meeting notes from Gmail"
"Order my usual lunch from Zomato"
```

AURA uses Android's **Accessibility Service** to observe and control any app on your device, an **LLM** to plan the steps, and a **Risk Engine** that always pauses before doing anything sensitive � keeping you 100% in control.

> ? **No root required. Works with any app. Your data never leaves your control.**

---

## ? Key Features

| Feature | Description |
|---|---|
| ?? **LLM-Powered Planning** | Understands natural language and breaks goals into precise UI actions |
| ?? **Human-in-the-Loop** | Always pauses for your approval before sending messages, payments, or deletions |
| ??? **Voice First** | Wake word activation (*"Hey AURA"*) + full TTS narration of actions |
| ?? **Works Across All Apps** | Controls WhatsApp, GPay, Zomato, Gmail, Maps � any app via Accessibility API |
| ?? **App Skill Packs** | Optimized, pre-built flows for popular Indian apps (faster & more reliable) |
| ?? **Long-Term Memory** | Remembers your habits and preferences across sessions |
| ?? **Offline Mode** | On-device LLM (Gemma 2B / Phi-3 Mini) for basic tasks without internet |
| ??? **Biometric Security** | Fingerprint/face unlock gates all financial and critical actions |
| ?? **Macro / Recipes** | Save multi-step workflows as named, reusable routines |
| ?? **Analytics Dashboard** | See exactly how much time AURA saved you this week |

---

## ??? Architecture

```
+---------------------------------------------------------+
�                      Android Device                      �
�                                                         �
�  ?? Voice/Text Input                                    �
�       �                                                 �
�       ?                                                 �
�  +----------+    +--------------+    +---------------+  �
�  �   STT    �---?� Intent Parser�---?�  Task Planner �  �
�  +----------+    �   (LLM)      �    �    (LLM)      �  �
�                  +--------------+    +---------------+  �
�                                             �           �
�                                             ?           �
�                                    +-----------------+  �
�                                    �  Policy / Risk  �  �
�                                    �     Engine      �  �
�                                    +-----------------+  �
�                                             �           �
�                                             ?           �
�  +--------------+    +------------+   +---------------+ �
�  �  UI Observer �?---� Android    �?--� Action        � �
�  �(Accessibility�    �   Apps     �   � Executor      � �
�  �  Node Tree)  �    +------------+   +---------------+ �
�  +--------------+                                       �
�         �                                               �
�         ?                                               �
�    Task Manager / State                                 �
�                                                         �
�  ??  LLM API (Cloud: GPT-4, Claude, Gemini)             �
�  ??  On-Device (Gemma 2B, Phi-3 Mini, Llama)            �
+---------------------------------------------------------+
```

### Core Components

| Component | Role |
|---|---|
| **STT / Wake Word** | Captures voice input; *"Hey AURA"* triggers listening |
| **Intent Parser** | LLM that converts natural language to structured goal |
| **Task Planner** | Breaks goal into ordered action steps |
| **Policy / Risk Engine** | Classifies each action by risk; gates confirmations |
| **Action Executor** | Calls `AccessibilityNodeInfo.performAction()` or `dispatchGesture()` |
| **UI Observer** | Listens to `onAccessibilityEvent` to maintain live UI state |
| **Task Manager** | Manages state machine: Created ? Planning ? Executing ? Done |
| **Memory Store** | SQLite + embeddings for long-term user context |

---

## ??? Tech Stack

### Android App
- **Language**: Kotlin (+ Java interop)
- **UI**: Jetpack Compose
- **Core**: Android AccessibilityService, Jetpack WorkManager, ForegroundService
- **ML / On-Device**: TensorFlow Lite, ONNX Runtime, Google ML Kit (OCR)
- **Storage**: Room / SQLite (encrypted), Android Keystore

### AI & LLM

| Model | Provider | Mode | License |
|---|---|---|---|
| GPT-4o / GPT-4 Turbo | OpenAI | Cloud API | Closed |
| Claude 3.5 | Anthropic | Cloud API | Closed |
| Gemini 1.5 Pro | Google Vertex | Cloud API | Closed |
| **Llama 3** (7B�70B) | Meta / HuggingFace | On-device / Cloud | **Apache 2.0** |
| **Mistral 7B** | Mistral AI | On-device / Cloud | **Apache 2.0** |
| **Gemma 2B** | Google | On-device | **Apache 2.0** |
| **Phi-3 Mini** | Microsoft | On-device | **MIT** |

### Speech

| Function | Option | Mode |
|---|---|---|
| STT | Android SpeechRecognizer | On-device |
| STT | OpenAI Whisper | On-device / Cloud |
| STT | Vosk / DeepSpeech | On-device (offline) |
| TTS | Android TextToSpeech | On-device |
| TTS | Google Cloud TTS | Cloud |
| Wake Word | Picovoice Porcupine | On-device |

### Backend (Optional)
- **Framework**: Python FastAPI or Node.js Express
- **LLM SDK**: OpenAI SDK, Anthropic SDK, Hugging Face Transformers
- **CI/CD**: GitHub Actions, Fastlane
- **Testing**: JUnit, Mockito, Espresso, UI Automator, PyTest

---

## ?? Permission & Risk Engine

Every action AURA plans is classified by risk level before execution:

| Risk Level | Example Actions | User Prompt | Behavior |
|---|---|---|---|
| ?? **LOW** | Open app, scroll, navigate, read text | None | Auto-execute |
| ?? **MEDIUM** | Read messages, draft text | Informational toast | Optional confirm |
| ?? **HIGH** | Send message, upload file, delete data | Explicit dialog | Must approve |
| ?? **CRITICAL** | Transfer money, change password, buy item | Biometric + confirm | Secure auth required |

**Example � Sending a WhatsApp message (HIGH risk):**

```
+---------------------------------+
�     ?? SEND WHATSAPP MESSAGE    �
�                                 �
�  To:   Rahul Sharma             �
�  Text: "I will arrive at 7 PM." �
�                                 �
�     [? Deny]    [? Allow]       �
+---------------------------------+
```

**PolicyEngine pseudocode:**
```kotlin
val risk = classify(action)
when (risk) {
    LOW      -> execute()
    MEDIUM   -> { log(action); execute() }
    HIGH     -> requestConfirmation("Allow this action?") { execute() }
    CRITICAL -> requestBiometric("Authenticate to proceed") { execute() }
}
```

---

## ?? Action Schema

AURA's planner outputs structured JSON � validated against the live UI before any execution:

```json
[
  { "action": "open_app",     "package": "com.whatsapp" },
  { "action": "find_element", "text": "Rahul Sharma" },
  { "action": "tap",          "target": "first_match" },
  { "action": "type",         "text": "I am on my way!" },
  { "action": "confirm_send", "message": "Send this to Rahul?", "risk": "HIGH" }
]
```

**Supported Primitives:**

`open_app` � `tap` � `type` � `swipe` � `scroll` � `back` � `home` � `find_element` � `press_key` � `wait` � `request_confirmation` � `read_text` � `confirm_send`

---

## ?? Task State Machine

```
   [Created] ? [Planning] ? [Executing] --(HIGH/CRITICAL)--? [WaitingForUser]
                                 ?                                    �
                                 +---------- user approves -----------+
                                 �
                            [Verifying]
                           /           \
                      [Failed]       [Completed ?]
```

**State snapshot example:**
```json
{
  "taskId": "task_1234",
  "status": "WAITING_FOR_USER",
  "goal": "Pay Rahul Rs.2,000 via GPay",
  "completedSteps": ["open_app", "select_recipient", "enter_amount"],
  "currentStep": "authentication",
  "nextStep": "final_confirmation",
  "riskLevel": "CRITICAL"
}
```

---

## ??? Security & Privacy

- **No secrets to LLM** � PINs, passwords, OTPs are never sent to any AI model
- **Android Keystore** � All cryptographic material in hardware-backed secure enclave
- **Biometric Gate** � `BiometricPrompt` API for CRITICAL-level actions
- **Prompt Injection Defense** � UI content isolated from LLM instructions
- **TLS Everywhere** � All network calls use HTTPS/TLS
- **Encrypted Storage** � `EncryptedSharedPreferences` + encrypted SQLite
- **Audit Logs** � Every action logged with timestamp (no sensitive data)
- **Emergency Stop** � "Stop AURA" button + *"Hey AURA, stop"* halts all execution instantly
- **Least Privilege** � Only `BIND_ACCESSIBILITY_SERVICE`, `RECORD_AUDIO`, `INTERNET`

---

## ?? Enhancements & Roadmap

### Planned Features

| Feature | Description | Priority |
|---|---|---|
| ?? **Long-Term Memory** | Vector store remembers past actions & preferences | **P0** |
| ??? **App Skill Packs** | Pre-optimized flows for WhatsApp, GPay, Zomato, etc. | **P0** |
| ?? **Floating Bubble** | Persistent overlay � always reachable, never intrusive | **P1** |
| ?? **Wake Word** | Hands-free on-device keyword detection (Porcupine) | **P1** |
| ?? **Macro / Recipe System** | Save & replay named multi-step workflows | **P1** |
| ?? **Context Awareness** | Time, location, calendar-aware planning | **P2** |
| ?? **Multi-Agent Architecture** | Orchestrator + domain-specialist sub-agents | **P2** |
| ??? **Multimodal Input** | Act on shared images ("Book this restaurant") | **P2** |
| ?? **Analytics Dashboard** | "AURA saved you 47 minutes this week" | **P3** |

### Timeline
```
2026 Q4 -------------------------------------- 2027 Q2

Phase 1 ���� Core Agent MVP
Phase 2      ���� Voice & UX + Floating Bubble
Phase 3           ���� Memory + Context Awareness
Phase 4                ���� App Skill Packs
Phase 5                     ���� Advanced Security
Phase 6                          �������� Recipes � Multi-Agent � Dashboard
```

---

## ?? Developer Setup

### Prerequisites
- Android Studio Hedgehog or later
- JDK 11+, Kotlin 1.9+
- Android device or emulator (API 29 / Android 10+)
- *(Optional)* Python 3.10+ for backend

### Quick Start

```bash
# 1. Clone the repo
git clone https://github.com/krish01info/AURA-.git
cd AURA-

# 2. Open android/ in Android Studio, build & run

# 3. Enable the agent on your device:
#    Settings ? Accessibility ? AURA ? Use Service ?

# 4. (Optional) Start the backend
cd agent-backend
pip install -r requirements.txt
cp .env.example .env   # Add your LLM API keys
uvicorn main:app --reload
```

### Project Structure

```
AURA-/
+-- android/                  # Android app (Kotlin)
�   +-- app/src/
�       +-- accessibility/    # AURAAccessibilityService.kt
�       +-- agent/            # Planner, PolicyEngine, ActionExecutor
�       +-- memory/           # Long-term memory store
�       +-- ui/               # Jetpack Compose UI + overlay bubble
�       +-- skills/           # App-specific skill packs (JSON)
+-- agent-backend/            # Optional LLM backend (Python FastAPI)
�   +-- main.py
�   +-- planner.py
�   +-- requirements.txt
+-- docs/
�   +-- architecture.md
�   +-- security.md
�   +-- contributing.md
+-- tests/
�   +-- android-tests/        # Espresso + UI Automator
�   +-- backend-tests/        # PyTest
+-- deep-research-report.md   # Full technical research & design doc
+-- .github/workflows/        # CI/CD (GitHub Actions)
+-- README.md
+-- LICENSE
+-- CONTRIBUTING.md
```

---

## ?? Testing Strategy

| Level | Tools | Coverage |
|---|---|---|
| **Unit** | JUnit 5, Mockito, PyTest | Planning logic, policy engine, state machine |
| **Integration** | Espresso, UI Automator | Cross-app flows on emulator |
| **Voice** | Pre-recorded audio samples | STT accuracy, intent parsing |
| **Security** | OWASP ZAP, Android Lint | API surface, accessibility permissions |
| **Fuzzing** | Android Monkey, custom prompt fuzzer | Random inputs, prompt injection attempts |

**Sample log output:**
```
10:05:32 [TASK]   Task #42 started: "Send WhatsApp message to Rahul"
10:05:32 [ACTION] Opened com.whatsapp
10:05:33 [ACTION] Found contact: Rahul Sharma
10:05:33 [ACTION] Typed message: "Hello Rahul"
10:05:33 [PROMPT] Awaiting user approval � HIGH risk action
10:05:45 [USER]   Approved
10:05:45 [ACTION] Message sent successfully
10:05:45 [TASK]   Task #42 completed in 13s
```

---

## ?? Contributing

Contributions are very welcome!

1. **Fork** this repository
2. **Create** a feature branch: `git checkout -b feature/your-feature`
3. **Write tests** for any new behavior
4. **Submit** a Pull Request

See [CONTRIBUTING.md](CONTRIBUTING.md) for full guidelines.

**High-priority contributions needed:**
- App skill packs for popular Indian apps
- On-device LLM integration (Llama / Gemma via TFLite)
- Multi-language support (Hindi, Tamil, Bengali)
- UI Automator test coverage

---

## ?? License

Licensed under the **Apache License 2.0** � see [LICENSE](LICENSE) for details.

Apache 2.0 was chosen for its permissive terms and explicit **patent grant**, making AURA safe for both open-source and commercial use.

---

<div align="center">

**Built with ?? for the future of mobile AI**

*AURA � Make mobile automation as easy as talking to a helpful assistant, without sacrificing control or security.*

? **Star this repo** if you believe AI should work *for* you, not just *with* you.

</div>
