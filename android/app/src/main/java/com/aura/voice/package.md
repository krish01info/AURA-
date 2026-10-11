# voice/ package

| File | Role |
|---|---|
| WakeWordService.kt | **The assistant.** Foreground mic service that owns the whole voice flow: "Hey AURA" → popup + beep → capture command → shows what it heard with Cancel (~1.3 s) → `TaskManager.execute` → progress, Allow/Deny (tap or "yes"/"no"), result. Also handles the in-app mic button and bubble (`listenNow`), and "Hey AURA, stop". Start/stop only via its companion helpers (`startWake`, `listenNow`, `turnOff`). |
| AssistantOverlay.kt | The popup panel (plain Views). Drawn as an accessibility overlay (no extra permission), else a normal overlay if "Display over other apps" is granted. Shrinks to a touch-through banner while steps run. |
| VoiceManager.kt | TTS + one-shot STT: `speak()`, `narrateStep()`, `recognizeSpeech()` (main thread only; language from `VoicePrefs`). |
| VoicePrefs.kt | Speech language setting (phone default / English (India) / Hindi / English (US)). Wake listener uses en-IN when Hindi is chosen. |
| FloatingBubble.kt | Draggable mic bubble overlay; tap → `WakeWordService.listenNow`. |
| VoskEngine.kt | Offline STT — **stub**, not used (Vosk dependency not added). |

## Rules
- Only one SpeechRecognizer may run at a time. All listening goes through `WakeWordService`; don't start recognition from ViewModels.
- A microphone foreground service may only be started while AURA is in the foreground (Android 14+). The helpers catch the error if not.
- Wake detection uses the SpeechRecognizer loop (some phones beep on each restart; uses more battery). Planned upgrade: Picovoice Porcupine.
