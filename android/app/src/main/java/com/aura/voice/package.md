# voice/ package

| File | Role |
|---|---|
| VoiceManager.kt | TTS + online STT: `speak()`, `narrateStep()`, `announceCompletion()`. |
| WakeWordService.kt | Background "Hey AURA" wake-word detection. |
| FloatingBubble.kt | Overlay bubble shown while a task runs. |
| VoskEngine.kt | Offline STT fallback — **stub**: the Vosk dependency isn't added yet, so it returns null and `HomeViewModel` uses online STT. |
