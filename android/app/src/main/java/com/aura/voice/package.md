# voice/ package

| File | Role |
|---|---|
| WakeWordService.kt | **The assistant.** Foreground mic service that owns the whole voice flow: "Hey AURA" → popup + beep → capture command (live text while you speak, one retry if nothing heard) → shows what it heard with Cancel (~1.3 s) → `TaskManager.execute` → progress, Allow/Deny (tap or "yes"/"no", up to 3 tries; a spoken "no" asks "what should I do instead?", and "no, call Rohit" runs the correction), result. Wake sessions that end at once back off (and switch on-device → online) so the mic doesn't flicker. Also handles the in-app mic button and bubble (`listenNow`). Stop a task: Cancel in the popup or Stop in the notification. Start/stop only via its companion helpers (`startWake`, `listenNow`, `turnOff`). |
| AssistantOverlay.kt | The popup panel (plain Views). Drawn as an accessibility overlay (no extra permission), else a normal overlay if "Display over other apps" is granted. Shrinks to a touch-through banner while steps run. |
| VoiceManager.kt | TTS + one-shot STT: `speak()`, `narrateStep()`, `recognizeSpeech()` / `recognizeAlternatives()` (main thread only; language from `VoicePrefs`). |
| VoicePrefs.kt | Speech language setting (phone default / English (India) / Hindi / English (US)). Wake listener uses en-IN when Hindi is chosen. |
| ApprovalParser.kt | Pure Kotlin: reads a spoken answer to "Allow this?" (yes / no / "no, call Rohit instead"), English + Hindi. Unit-tested. |
| FloatingBubble.kt | Draggable mic bubble overlay; tap → `WakeWordService.listenNow`. |
| VoskEngine.kt | Offline STT — **stub**, not used (Vosk dependency not added). |

## Rules
- Only one SpeechRecognizer may run at a time. All listening goes through `WakeWordService`; don't start recognition from ViewModels.
- A microphone foreground service may only be started while AURA is in the foreground (Android 14+). The helpers catch the error if not.
- Wake detection: SpeechRecognizer loop, on-device recognizer when available; system start-beeps muted on restarts (never while music plays — only streams AURA muted are restored). No listening during a task. Planned upgrade: Picovoice Porcupine.
- Step narration is off unless Settings → Voice narration is on (`VoiceManager.PREF_NARRATION`).
