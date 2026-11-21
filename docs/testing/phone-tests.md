# Phone tests

Things only a real phone can check: the Accessibility service, the mic, overlays, other apps. Unit tests can't
cover them. Each row is one test. "Never" means it hasn't passed on a phone yet.

## How to run a test
1. Install the latest build (the `aura-debug-apk` artifact from CI, or `assembleDebug` from Android Studio).
2. Make sure AURA's Accessibility service is on and a Groq or Gemini key is set in Settings.
3. With the phone connected over USB, start the log before you begin:

   ```powershell
   adb logcat -s TaskManager ActionExecutor SkillRouter WakeWordService VoiceManager
   ```
4. Do what the row says. If it works, write the date and phone model under "Last passed". If it doesn't, keep
   the log lines and say what happened instead.

When a change affects one of these rows, the assistant updates the row and sets "Last passed" back to "never".
New phone-only features get a new row in the same change.

## Voice and popup
| Feature | Say or do | Should happen | Last passed |
|---|---|---|---|
| Floating mic | Turn on the bubble, open another app, tap the bubble | The popup opens and listens | never |
| Offline wake word | Let the voice model download, turn off Wi-Fi and data, say "Hey AURA" | The popup opens without internet | never |
| Mic stays steady | Leave voice mode on for a few minutes in another app | The mic icon doesn't flicker on and off | never |
| Saying no | "Hey AURA, send hi to Rahul on WhatsApp", then say "no" at the approval | It doesn't send and asks "What should I do instead?" | never |
| Stop while working | Start any task, tap Stop on the working banner | The task stops at once and says "Stopped" | never |
| Wrong after Done | After a task says Done, tap Wrong | It drops that skill and asks what you wanted | never |
| App lock wait | Lock WhatsApp with fingerprint, then ask AURA to message someone | It says "Unlock the app", waits up to 45 seconds, then carries on | never |

## Tasks
| Feature | Say or do | Should happen | Last passed |
|---|---|---|---|
| Two-part command | "Open WhatsApp and send hi to Rahul" | It opens the chat, types "hi" and asks before sending; it doesn't stop after opening the app | never |
| Call | "Call Rahul" | The call starts, not just the dialer | never |
| No surprise calls | "Play the song Believer" | Music plays; no call is placed | never |
| YouTube plays | "Play lofi music on YouTube" | The first result starts playing, not just the search page | never |
| Play Store search | "Search for Spotify on Play Store" | The Play Store opens on the search results | never |
| Knows the screen | With YouTube already open, "Hey AURA, search for cats here" | It searches inside the open YouTube app instead of opening something else | never |

## Shortcuts
These come from `app_shortcuts.json` and skip the AI plan, so they should be quick. Settings shortcuts open the
screen; you flip the switch.

| Feature | Say or do | Should happen | Last passed |
|---|---|---|---|
| Maps: start navigation | "Drive to India Gate" | Google Maps starts turn-by-turn navigation, not just the route preview | never |
| Maps: walking | "Walking directions to India Gate" | Maps shows the walking route | never |
| Maps: bike | "Bike directions to India Gate" | Maps shows the two-wheeler route | never |
| Maps: public transport | "Bus route to India Gate" | Maps shows bus, train or metro routes | never |
| Maps: near me | "Find petrol pump near me" | Maps lists petrol pumps around you | never |

## Safety
| Feature | Say or do | Should happen | Last passed |
|---|---|---|---|
| Fingerprint for payments | Ask AURA to pay someone a small amount in a UPI app | It asks for fingerprint inside AURA before the pay step | never |
| Amount limit rule | Add a rule "allow GPay up to ₹500", then ask to pay ₹2,000 | It asks for approval instead of allowing it | never |

## Live tasks
For each one, say "stop" at the end. Live tasks need an ARM phone, not an x86 emulator.

| Feature | Say or do | Should happen | Last passed |
|---|---|---|---|
| Voice camera | "Voice camera", then say "capture" a few times | A photo is taken on each "capture" | never |
| Video with photos | "Record a video and take photos when I say capture" | Recording starts; each "capture" takes a photo; "stop" ends the video | never |
| Hands-free scrolling | In Chrome: "Scroll down when I say next", then say "next" | The page scrolls on each "next" | never |
| Hands-free reels | In Instagram reels: "Skip reels when I say skip", then say "skip" | The next reel shows on each "skip" | never |

If the camera app silences AURA while it records video, note it here; the live-tasks plan's Phase 8 (AURA's own
camera screen) is the fix.
