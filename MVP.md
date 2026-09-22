# AURA MVP Definition
## Minimum Viable Product — What We Build First

> **Goal**: Prove the core idea works in the simplest possible form.
> **Timeline**: 6 weeks | **Cost**: $0/month | **Team**: 1–2 developers

---

## The One-Line MVP Goal

> *A user speaks or types a goal → AURA controls the phone to achieve it → pauses for approval before any sensitive action.*

If that works reliably for **WhatsApp messaging** and **Google Pay payments** — the MVP is done.

---

## MVP Core Principle

```
Build ONLY what is needed to prove this demo works:

  "Send Rahul a WhatsApp message: I am on my way"
        |
        v
  AURA opens WhatsApp → finds Rahul → types message
        |
        v
  Dialog: "Send this to Rahul? [Deny] [Allow]"
        |
        v
  User taps Allow → message sent → TTS: "Done!"
```

Everything else is **post-MVP**.

---

## What IS in the MVP

### Layer 1 — Android Control Engine
| Feature | Description |
|---|---|
| `AccessibilityService` | Core service — reads and controls any Android app |
| `openApp(package)` | Launch any installed app by package name |
| `tap(node)` | Tap any UI element |
| `typeText(text)` | Type into any text field |
| `swipe(direction)` | Swipe up/down/left/right |
| `scroll(node)` | Scroll within a scrollable container |
| `pressBack()` / `pressHome()` | Navigation actions |
| `findNodeByText(text)` | Find UI element by visible text |
| `findNodeById(viewId)` | Find UI element by resource ID |
| Task state machine | Created → Planning → Executing → WaitingForUser → Done/Failed |

### Layer 2 — AI Brain (Groq)
| Feature | Description |
|---|---|
| Groq API integration | Llama 3.3 70B via Groq (free, 300+ tok/s) |
| Intent parsing | Natural language → structured goal |
| Action planning | Goal → ordered list of action steps |
| WhatsApp skill pack | Pre-built optimized flow (no LLM needed) |
| Google Pay skill pack | Pre-built UPI payment flow |
| Gemini 1.5 Flash fallback | Backup if Groq is unavailable |

### Layer 3 — Safety (Non-Negotiable)
| Feature | Description |
|---|---|
| Risk classification | Every action tagged LOW / MEDIUM / HIGH / CRITICAL |
| Confirmation dialog | Shown for HIGH risk actions (send message, submit form) |
| Payment confirmation | Shown for CRITICAL actions (transfer money) |
| Emergency Stop button | Persistent button — halts all execution instantly |
| Action audit log | Local log of what AURA did (no sensitive data) |

### Layer 4 — Voice (Makes it feel like AI)
| Feature | Description |
|---|---|
| Voice input button | Tap to speak your command |
| Android SpeechRecognizer | STT — converts voice to text |
| TTS narration | AURA speaks its actions aloud during execution |
| Voice error handling | "Sorry, I didn't catch that. Please try again." |

### Layer 5 — Basic UI
| Feature | Description |
|---|---|
| Home screen | Enable/disable agent, type or speak a command |
| Task log screen | List of past tasks with status (success/failed/cancelled) |
| Service status indicator | Green/red dot showing if AccessibilityService is active |
| Settings screen | API key input, enable/disable voice narration |

---

## What is NOT in the MVP

| Feature | Reason to Skip |
|---|---|
| Wake word ("Hey AURA") | Button tap for voice is enough at MVP |
| Long-term memory | Proves core value without it |
| Offline LLM (Gemma 2B) | Groq free tier is sufficient |
| Floating overlay bubble | Nice UX but not core to the idea |
| Macro / Recipe system | Needs memory first |
| Biometric gate | Confirmation dialog is enough for MVP |
| Anomaly detection | Post-MVP security hardening |
| Analytics dashboard | No real users yet |
| Zomato / Swiggy skill packs | WhatsApp + GPay proves the concept |
| Multi-agent architecture | Way too early |
| Web + App bridge | Phase 8+ feature |
| Proactive suggestions | Needs memory and usage data |

---

## 6-Week MVP Timeline

```
Week 1  ─── Phase 0: Project Setup
              Android project skeleton (Kotlin + Compose)
              Gradle KTS, Hilt DI, Room, Retrofit
              GitHub Actions CI (build + test on every push)
              Firebase Crashlytics configured
              .env.example created

Week 2  ─── Phase 1a: Core Accessibility
              AURAAccessibilityService declared and running
              UIObserver — live node tree snapshot
              ActionExecutor — openApp, tap, typeText, scroll, swipe
              Manual test: "Open WhatsApp" works

Week 3  ─── Phase 1b: Task Manager
              TaskState sealed class + state machine
              Basic home screen UI
              Task log screen
              Hard-coded task test passes end-to-end

Week 4  ─── Phase 2a: Groq LLM Integration
              GroqClient.kt with Retrofit
              IntentParser — NL to Goal
              TaskPlanner — Goal to List<ActionStep>
              Prompt injection defense from day one
              Test: typed command → plan generated → executed

Week 5  ─── Phase 2b: Skill Packs + Voice
              WhatsApp skill pack (send message)
              Google Pay skill pack (send money)
              SpeechRecognizer integration (tap-to-speak)
              TTS narration during execution

Week 6  ─── Phase 3: Safety + Polish
              HIGH/CRITICAL confirmation dialogs
              Emergency stop button
              Action audit logger
              Gemini 1.5 Flash fallback
              End-to-end demo tested and working
              APK built and ready to share
```

---

## The MVP Demo Script

This is the exact scenario that proves AURA works:

### Demo 1 — WhatsApp Message

```
User taps mic button and says:
"Send Rahul a WhatsApp message: I will be there in 10 minutes"

AURA (TTS): "Opening WhatsApp..."
  → AccessibilityService launches com.whatsapp

AURA (TTS): "Finding Rahul..."
  → findNodeByText("Rahul") → taps first result

AURA (TTS): "Typing your message..."
  → typeText("I will be there in 10 minutes")

AURA shows dialog:
┌──────────────────────────────────────┐
│       📤 SEND WHATSAPP MESSAGE       │
│                                      │
│  To:   Rahul Sharma                  │
│  Text: "I will be there in 10 mins"  │
│                                      │
│     [✗ Deny]        [✓ Allow]        │
└──────────────────────────────────────┘

User taps Allow

AURA (TTS): "Message sent!"
  → Task marked COMPLETED in log
```

### Demo 2 — Google Pay

```
User types:
"Pay Rahul 200 rupees on Google Pay"

AURA (TTS): "Opening Google Pay..."
  → Launches com.google.android.apps.nbu.paisa.user

AURA (TTS): "Finding Rahul..."
  → Navigates to Pay tab, finds contact

AURA (TTS): "Entering amount..."
  → Types 200

AURA shows dialog:
┌──────────────────────────────────────┐
│         💳 PAYMENT CONFIRMATION      │
│                                      │
│  Recipient: Rahul Sharma             │
│  Amount:    ₹200                     │
│  Via:       Google Pay               │
│                                      │
│  ⚠️  This action cannot be undone    │
│                                      │
│     [✗ Cancel]      [✓ Confirm]      │
└──────────────────────────────────────┘

User taps Confirm → enters UPI PIN in Google Pay's own secure UI
AURA (TTS): "Payment initiated!"
```

---

## MVP Tech Stack (All Free)

```
Android App (Kotlin + Jetpack Compose)
  ├── AccessibilityService API      — controls any app
  ├── Groq API (Llama 3.3 70B)     — LLM planning, free 14,400 req/day
  ├── Gemini 1.5 Flash              — fallback LLM, free 1M tokens/day
  ├── Android SpeechRecognizer      — voice input, free built-in
  ├── Android TextToSpeech          — voice narration, free built-in
  ├── Room + EncryptedSharedPrefs   — local storage
  ├── Hilt                          — dependency injection
  └── Retrofit + OkHttp             — API calls

Monthly Cost: $0
```

---

## MVP File Structure

```
android/app/src/main/java/com/aura/
├── accessibility/
│   ├── AURAAccessibilityService.kt   ← THE core agent
│   ├── UIObserver.kt                 ← reads live node tree
│   └── NodeFinder.kt                 ← find elements by text/id
├── agent/
│   ├── ActionExecutor.kt             ← tap, type, swipe, etc.
│   ├── IntentParser.kt               ← NL → Goal
│   ├── TaskPlanner.kt                ← Goal → ActionSteps
│   ├── TaskManager.kt                ← state machine
│   └── SkillRouter.kt                ← routes to skill packs
├── llm/
│   ├── GroqClient.kt                 ← primary LLM
│   └── GeminiClient.kt               ← fallback LLM
├── policy/
│   ├── PolicyEngine.kt               ← LOW/MEDIUM/HIGH/CRITICAL
│   └── AuditLogger.kt                ← logs all actions
├── skills/
│   ├── WhatsAppSkill.kt              ← MVP skill pack #1
│   └── GPaySkill.kt                  ← MVP skill pack #2
├── voice/
│   ├── VoiceManager.kt               ← STT + TTS
│   └── SpeechEngine.kt               ← SpeechRecognizer wrapper
└── ui/
    ├── MainActivity.kt
    ├── screens/
    │   ├── HomeScreen.kt             ← command input + mic button
    │   ├── TaskLogScreen.kt          ← task history
    │   └── SettingsScreen.kt         ← API keys, preferences
    └── components/
        ├── ConfirmationDialog.kt     ← HIGH risk gate
        ├── PaymentDialog.kt          ← CRITICAL risk gate
        └── EmergencyStopButton.kt    ← always visible stop button
```

---

## MVP Definition of Done

The MVP is complete when ALL of these pass:

### Functional
- [ ] AccessibilityService starts and stays alive in background
- [ ] "Open WhatsApp" via typed command works
- [ ] "Open WhatsApp" via voice command works
- [ ] WhatsApp send message flow completes end-to-end
- [ ] Google Pay send money flow completes end-to-end
- [ ] Confirmation dialog appears for every HIGH risk action
- [ ] Payment confirmation dialog appears for CRITICAL actions
- [ ] Emergency stop button halts execution within 500ms
- [ ] TTS narrates each step during execution
- [ ] Task history screen shows completed and failed tasks

### Quality
- [ ] No crashes in 20 consecutive test runs
- [ ] App works on Android 10, 12, and 14
- [ ] App works on at least 2 different phone models
- [ ] Groq API errors handled gracefully (fallback or retry)
- [ ] No sensitive data in logs or shared preferences

### Cost
- [ ] Total monthly running cost: **$0**
- [ ] Groq usage stays within free tier (14,400 req/day)

---

## What MVP Unlocks

Once MVP is done, you have proof to:

| Unlock | Description |
|---|---|
| 🧪 **User Testing** | Share APK with 5–10 beta users, collect real feedback |
| 📈 **Feature Priority** | Users tell you what skill packs they want next |
| 🤝 **Collaboration** | Real working product attracts contributors |
| 💡 **Post-MVP Backlog** | Memory, wake word, recipes — based on real usage data |
| 📱 **Play Store Alpha** | MVP-quality app can be listed in closed testing |

---

## Post-MVP Backlog (In Priority Order)

| Priority | Feature | Phase |
|---|---|---|
| P0 | Long-term memory (remember habits) | Phase 5 |
| P0 | Zomato / Swiggy skill packs | Phase 6 |
| P1 | Wake word "Hey AURA" (Porcupine) | Phase 4 |
| P1 | Floating overlay bubble | Phase 8 |
| P1 | Biometric gate for payments | Phase 7 |
| P1 | Macro / Recipe system | Phase 8 |
| P2 | Offline LLM (Gemma 2B) | Phase 5 |
| P2 | Anomaly detection | Phase 7 |
| P2 | Context awareness (time/location) | Phase 5 |
| P3 | Analytics dashboard | Phase 8 |
| P3 | Maps / Gmail / YouTube skill packs | Phase 6 |

---

*AURA MVP — 6 weeks to prove the future of mobile AI. Build it small. Build it right.*
