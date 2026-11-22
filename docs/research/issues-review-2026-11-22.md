# AURA: issues found (22 Nov 2026)

AURA can't go on Google Play yet. Play bans its main feature, and the upload would be refused for 3 technical
reasons. The code also has 6 privacy problems, 2 of them serious. Line numbers are from branch `vid/agents-rules`
on 22 Nov 2026.

## Summary

| # | Problem | Area | Severity |
| --- | --- | --- | --- |
| 1 | Play bans AI that plans and performs taps through Accessibility | Google Play | Blocker |
| 2 | A new personal account needs a 14-day closed test with 12 testers | Google Play | Blocker |
| 3 | Target SDK is 35; Play has required 36 since 31 Aug 2026 | Google Play | Upload refused |
| 4 | Self-update from GitHub and `REQUEST_INSTALL_PACKAGES` aren't allowed | Google Play | Upload refused |
| 5 | Play needs an AAB file; CI only builds APKs | Google Play | Upload refused |
| 6 | Screen text goes to Groq and Gemini without telling users | Privacy | Serious |
| 7 | The wake word can stream room audio to an online speech service | Privacy | Serious |
| 8 | The Stop button doesn't stop AI planning | Code | Bug |

## Google Play: why AURA can't be uploaded

Two blockers stop a release, three technical problems would get the upload refused, and several forms must be done
before review.

### Blocker 1: Play bans AI agents that use Accessibility

Play's [Accessibility API policy](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)
strictly forbids using the API to let an app "autonomously initiate, plan, and execute actions or decisions". That is
AURA's core loop: the AI plans taps and typing, and the Accessibility service performs them.

- **Not allowed in AURA:** AI-planned taps and typing, re-planning from the current screen after a failed step,
  skills learned from AI plans, community skills from the cloud.
- **Allowed:** fixed scripts written by a person ("if X, do Y"), such as the hand-written shortcuts in
  `app_shortcuts.json`. Intent actions (call, SMS, alarm, Maps, links) don't use Accessibility at all.
- **The one exemption:** apps flagged `isAccessibilityTool="true"` whose main purpose is helping people with
  disabilities. Google says a general voice-activated assistant doesn't qualify. Setting the flag falsely can get the
  app suspended and the developer account closed.

The ways forward:

1. **Play-safe version.** In the Play build, Accessibility only runs the hand-written shortcuts; the AI may plan
   intent actions. Letting the AI pick which shortcut to run is a grey area. The full agent stays on GitHub.
2. **A real accessibility tool,** for example hands-free phone control for people with motor disabilities. Only if
   that is honestly the purpose; it means a redesign.
3. **GitHub only,** as now. Google has announced developer verification for apps installed outside Play too; check
   when it applies in India.

### Blocker 2: the 14-day testing rule

Personal developer accounts created after 13 Nov 2023 must run a closed test with at least 12 testers, opted in for
14 days in a row, before applying for production
([Google](https://support.google.com/googleplay/android-developer/answer/14151465)). The earliest production launch
is about 3 weeks away.

### The upload would be refused

| Problem | Where in AURA | Fix |
| --- | --- | --- |
| Target SDK 35; Play needs 36 since 31 Aug 2026 ([Google](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)) | `android/app/build.gradle.kts:34` (`compileSdk` on line 29) | Raise both to 36, update the Android Gradle Plugin, test Android 16 changes |
| Play apps may only update through Play; `REQUEST_INSTALL_PACKAGES` is restricted ([Google](https://support.google.com/googleplay/android-developer/answer/12085295)) | `update/` package, `AndroidManifest.xml:38`, the FileProvider | A "play" build flavor without the updater, the permission and the FileProvider |
| Play needs an AAB file, not an APK | `.github/workflows/release.yml` | Add `bundleRelease`; give the GitHub build a different app ID so the two don't fight |
| 16 KB memory pages ([Android](https://developer.android.com/guide/practices/page-sizes)) | `libvosk.so`, `libjnidispatch.so`, `libdatastore_shared_counter.so` | Already fine: all three are 16 KB aligned in the built APK |

### Forms and privacy items for review

1. **Disclosure screen inside the app,** before Accessibility is turned on: what AURA reads, that it goes to Groq and
   Google, and why, with an "I agree" button. Going back must not count as agreeing
   ([User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en)).
2. **Accessibility declaration form** in the Play Console, with a video: open app, disclosure, agree, refuse, a
   feature working.
3. **Microphone foreground service declaration,** with a video.
4. **Privacy policy** at a public web address (not a PDF), linked in the Console and inside the app.
5. **Data safety form:** screen content, dictated messages, contact names, app activity and the anonymous Supabase
   ID, and that they go to Groq and Google.
6. **Gemini:** Settings points users to a free key, and the free tier's terms say not to send personal information
   ([terms](https://ai.google.dev/gemini-api/terms-archive/terms_10_07_25)). Use a paid key and set the target
   audience to 18+.
7. **Keys inside the APK:** on Play, strangers can extract them and spend the quota. A small server (for example a
   Supabase Edge Function) can hold the keys and make the AI calls.
8. **Cloud skills:** they are learned from AI plans, not written by a person, so turn them off in the Play build.
9. **The privacy fixes** in the next section, especially the wake word and old commands sent again.

### Store listing

- A real app icon: the manifest uses Android's default icon (`AndroidManifest.xml:120`).
- A 512 x 512 icon, a 1024 x 500 feature graphic, at least 2 screenshots, a short and a full description.
- Content rating questionnaire, target audience 18+, ads declaration (no ads).
- "App access" instructions for reviewers: how to turn on Accessibility and test AURA.
- The $25 developer fee and identity verification.

## Privacy problems in the code

Six places where personal data leaves the phone or is kept without users being told; the first two are serious.
Paths are under `android/app/src/main/java/com/aura/`, except `res/`, which is under `android/app/src/main/`.

| # | Problem | Severity | Where | Fix |
| --- | --- | --- | --- | --- |
| 1 | When no saved skill matches, up to 2,000 characters of the screen (chats, emails, balances, OTPs) go to Groq or Gemini, and again on each retry. The app never says so; Settings points to a free Gemini key, whose prompts Google may use and human reviewers may read. The keys are in the APK, so every user's data goes through the owner's accounts. | Serious | `accessibility/UIObserver.kt`, `res/values/strings.xml:4`, `ui/screens/SettingsScreen.kt:166` | Consent screen on first launch; paid Gemini or Groq with Zero Data Retention; hide OTPs, card numbers and emails before sending; never send banking or blocked apps' screens |
| 2 | If the phone has no on-device recognizer, or it keeps failing, the always-on "Hey AURA" loop switches to the online recognizer, so speech near the phone can leave it. Everything heard is also logged. | Serious | `voice/WakeWordService.kt:286`, `:419`, `:385` | Offline wake word only (Vosk now, Porcupine later); if neither works, use the mic bubble |
| 3 | Memory saves the full text of every command, and up to 3 similar old commands are added to each new AI request. | Medium | `memory/MemoryManager.kt:60`, `:139` | Save and send templates ("send {message} to {contact}") or only app and action |
| 4 | Learned skills go to a public Supabase table. Only values the AI marked as slots are removed, so a missed message or chat name is uploaded. Each upload also carries the phone model and an anonymous account ID. Off until Supabase is set up. | Medium | `skills/SkillLearner.kt:58`, `:123`, `cloud/SupabaseSkillApi.kt` | Refuse uploads with fixed typed text; drop the phone model; ask before the first upload |
| 5 | The task log keeps the full command, message included, though a comment says it doesn't. Not encrypted, never deleted. | Low to medium | `data/model/TaskLogEntity.kt:9` vs line 15 | "Delete history" button, auto-delete after 30 days, correct the comment |
| 6 | Release builds keep logs that hold commands, typed messages and raw AI replies, and testers are asked to send logcat. | Low | `agent/TaskManager.kt:265`, `agent/ActionExecutor.kt:139`, `llm/PlannerPrompt.kt:204` | Strip `Log.d` and `Log.i` from release builds with an R8 rule, or stop logging text |

Already right: notifications stay in memory only, the audit log never stores typed text, backups are off, API keys
are encrypted, the AI never sees phone numbers, and AURA never types PINs or passwords.

## Other code and setup issues

One real bug and several upkeep items; none blocks a GitHub release. Paths are under
`android/app/src/main/java/com/aura/` unless they start with `android/`, `docs/` or a file name.

| Issue | Where | Fix |
| --- | --- | --- |
| The Stop button doesn't stop AI planning. `catch (e: Exception)` also catches the coroutine's `CancellationException`, so Stop makes AURA try the next model. | `llm/GroqClient.kt:130`, `llm/GeminiClient.kt:136` | Catch `CancellationException` first and rethrow it; add a unit test |
| API keys are baked into the APK. Accepted for GitHub builds; on Play anyone can extract and use them. | `android/app/build.gradle.kts:45`-`52` | Move AI calls behind a small server that holds the keys |
| Security Crypto (encrypted key storage) is deprecated by Google and pinned to an alpha. | `security-crypto 1.1.0-alpha06` in `android/gradle/libs.versions.toml` | Move to Android Keystore with DataStore |
| Old or alpha libraries | Compose BOM 2024.08.00, Room 2.6.1, Biometric 1.2.0-alpha05 | Update, then rerun the tests and check the release build |
| Two network stacks: Retrofit/OkHttp for Groq, Ktor for Supabase | `android/gradle/libs.versions.toml` | Possible simplification later |
| No unit tests for the core loop and the anomaly check | `agent/TaskManager.kt`, `policy/AnomalyDetector.kt` | Add fakes and tests |
| Nothing is tested on a real phone yet: every row says "never" | `docs/testing/phone-tests.md` | Work through the list and fill in "Last passed" |
| Gradle skips `AppShortcutsTest` after a JSON-only change and reports "up-to-date" | local builds | Run `testDebugUnitTest --rerun` |
| Planned safety fixes not done: one rule for every Send or Pay tap, a narrower settings-intent allow-list | `policy/PolicyEngine.kt`, `agent/ActionExecutor.kt` | One commit per fix |
| Stale docs: large-file line counts, README boot permission and Vosk "Planned", the `BiometricGate` comment, the `TaskLogEntity` comment | `AGENTS.md`, `README.md`, `policy/BiometricGate.kt`, `data/model/TaskLogEntity.kt` | Correct them |
| Open repo-rules topics: copied SKILL.md text, deny rules in `.claude/settings.json`, path-filtered rules, a line that the rules are for Claude Code, not AURA's planner | `agents/repo_rules.md`, `agents/this_repo.md` | Decide each one |
| Two branches are waiting to be merged | `vid/agents-rules`, then `vid/more-shortcuts` | Merge the rules pull request first (normal merge), then the shortcuts one |

## Fix order and timeline

If work starts on 23 Nov 2026, production is about 3 weeks away, and the 14-day closed test sets the date.

| Phase | When (estimate) | What |
| --- | --- | --- |
| Prepare | 23 Nov, day 1 | Developer account, choose the Play path, privacy policy |
| Play build | 23-26 Nov, days 1-4 | Target SDK 36, AAB, no self-update, consent screen, privacy fixes |
| Closed test | 27 Nov - 10 Dec | 12+ testers, 14 days in a row, fix what they find |
| Production | From 11 Dec | Apply for access; Google's review takes several days, often longer for Accessibility apps |

- [ ] Choose the Play path: Play-safe version, accessibility tool, or GitHub only
- [ ] Fix the Stop-button bug in `GroqClient` and `GeminiClient`
- [ ] Privacy fixes 1 and 2: consent screen and offline-only wake word
- [ ] Privacy fixes 3, 5 and 6: templates in memory, history delete, no text in release logs
- [ ] Play build: target SDK 36, AAB, no self-update, cloud skills off
- [ ] Privacy policy page, Data safety form, declarations with videos, store listing
- [ ] Closed test: 12 or more testers for 14 days
- [ ] Apply for production
- [ ] Before turning on Supabase: privacy fix 4

## Sources

- [Google Play: Use of the AccessibilityService API](https://support.google.com/googleplay/android-developer/answer/10964491?hl=en)
- [Google Play: User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en)
- [Google Play: Target API level requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)
- [Google Play: Testing requirements for new personal accounts](https://support.google.com/googleplay/android-developer/answer/14151465)
- [Google Play: REQUEST_INSTALL_PACKAGES policy](https://support.google.com/googleplay/android-developer/answer/12085295)
- [Google Play: Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/9888379?hl=en)
- [Android: 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)
- [Gemini API Additional Terms (archived Oct 2025)](https://ai.google.dev/gemini-api/terms-archive/terms_10_07_25)
- [Groq: Your data](https://console.groq.com/docs/your-data)
