# Examples

Cleaned-up copies of real AURA files, so an assistant matches this repo's conventions (package style, naming,
state, DI and tests) and the comment rules in `agents/repo_rules.md` §7. They are style references only: Gradle
never compiles this folder, and the real code lives under `android/app/src/`. When the real file changes in a
way that matters for style, refresh the copy.

- `PermissionsViewModel.kt`: a Hilt `ViewModel` exposing Room `Flow`s as read-only `StateFlow`, with writes in
  `viewModelScope`. Real file: `android/app/src/main/java/com/aura/ui/viewmodel/PermissionsViewModel.kt`.
- `TriggerMatcher.kt`: pure Kotlin logic with no Android types and an injected clock, so it can be unit-tested
  on the JVM. Real file: `android/app/src/main/java/com/aura/agent/TriggerMatcher.kt`.
- `TriggerMatcherTest.kt`: its JUnit test, with a fake clock and readable test names. Real file:
  `android/app/src/test/java/com/aura/agent/TriggerMatcherTest.kt`.

What the copies change from the real files: no lined-up `=` signs, short lowercase comments that give the reason
instead of KDoc on everything, and long expressions split into named steps. Behavior is the same.

There is no repository or screen example yet. AURA's screens are 300 to 650 lines, which is too long to copy as
a model.
