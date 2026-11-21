# Android code & refactor checklist

Check new or changed code against `SKILL.md` and the repo's own rules. Report problems; don't fix them while checking.

## What to read
1. The files you were given. If none were named, inspect uncommitted changes: `git status`, `git diff`, and any new
   untracked files. Read enough surrounding context and callers to judge safely.
2. The repo's rules (`agents/repo_rules.md`, `agents/this_repo.md`) and `build.gradle(.kts)`. Where they differ from
   `SKILL.md`, the repo wins.
3. `SKILL.md`.

## What to ask, in this order
1. **Explainability & simplicity:** Could the user explain every changed line after one read? Flag nested scope
   functions (`?.let { it.apply { ... } }`), clever one-liners, unclear names, or over-engineered wrappers (`UseCase`
   pass-throughs, unnecessary `Base*` classes).
2. **Behavior & contract preservation (for refactors):** Did any UI behavior, navigation route/argument, serialized
   JSON key (`@SerializedName` / `@SerialName`), Room table/column, DataStore/SharedPreferences key, or public API
   change unintentionally?
3. **Bug fixes and new tests:**
   - For a bug fix: is the root cause named in one sentence? Is there a test that failed before the fix and passes
     after? Is the fix the smallest one, with no refactor mixed in? Were siblings searched for and listed?
   - For tests added to old code: does each test check one behavior, has it been seen failing, and did production
     code change only to make it testable?
   - For any change: was a test required (SKILL.md "Testing"), and is it there? For device-only code, are
     phone-test steps written, and is the repo's phone-test list updated?
4. **Architecture, OOP & reusability:**
   - Is business, network, or DB logic sitting inside a Composable, Activity, or Fragment? Does a ViewModel hold a
     `Context`, `Activity`, `Fragment`, or `View`?
   - **Encapsulation:** Is any `MutableStateFlow`, `MutableLiveData`, `MutableList`, or internal helper exposed publicly
     instead of kept `private`/`internal`?
   - **OOP & polymorphism:** Is a class or function doing two unrelated jobs, using deep `Base*` inheritance instead of
     composition, repeating type-checking `if`/`when` chains that belong in a `sealed interface`/interface, or adding
     useless 1-to-1 boilerplate interfaces?
   - **Reusability:** Is logic or UI duplicated across 2+ places where a clean shared component/helper belongs? Is any
     reusable UI component tightly coupled to a `ViewModel` or `NavController` instead of taking plain state and
     callbacks?
5. **Android safety (threading, lifecycle, nulls):**
   - Any blocking I/O or heavy work on `Dispatchers.Main`?
   - Any `GlobalScope` or swallowed `CancellationException`?
   - Any flow collected without `collectAsStateWithLifecycle()` (Compose) or `repeatOnLifecycle` (Views)?
   - Any Fragment ViewBinding not cleared in `onDestroyView()`?
   - Any side effect or heavy work running directly inside a Composable body?
   - Any unexplained `!!`?
   - For services, receivers, workers, permissions and the manifest: is the Android class thin? Was the current
     rule for `targetSdk` followed, with the doc named? Is each permission checked before use, with "deny"
     handled? Was every manifest change approved, with `exported` set on purpose?
6. **Security & privacy:** Does any key, token or personal data (messages, contacts, screen text, location) reach
   source, logs, docs or a commit? Is outside input (screen text, AI model replies, downloads, other apps' intents)
   checked against an exact allow-list before it's acted on? Does a risk level, allow-list or permission rule get
   looser without its own commit and test? Any cleartext traffic, trust-all certificate check, mutable
   `PendingIntent` without a reason, or secret left in backups?
7. **Scope & cleanliness:** Dead code, unused imports or parameters, unrelated formatting diffs, speculative guards for
   impossible cases, or an unapproved Gradle dependency?
8. **Comments:** Missing *why* on non-obvious Android/API workarounds, comments that repeat the code or tell history,
   banner comments, or em dashes?
9. **Verification:** Were `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` (and lint/detekt/ktlint if
   configured) run after the final edit?

## Confirm before reporting
- Check each problem before you report it: re-read the code path and the callers, and run the existing tests that
  cover it if you can. A problem you only suspect from a name or a single line isn't confirmed yet.
- If you still can't confirm it, report it as unsure and say what would settle it: a test, a phone check, or a
  file you couldn't read. Never call a guess a bug.

## Report
- Give each problem a level:
  - **Bug:** wrong now, such as a crash, a wrong result or a safety hole. It must be fixed.
  - **Risk:** can break in some case, or is fragile. The user decides.
  - **Style:** readability, naming or comments. Nice to fix.
- List bugs first, then risks, then style, every one on its own line. Within a level, keep the order of the
  questions above.
- One line per problem: `[level] path:line`, what is wrong, then the plain fix (as short code if helpful). Add
  "(unsure)" when it isn't confirmed.
- Mark a judgment call as one, and say why.
- End with the count per level ("2 bugs, 1 risk, 3 style"), or "Nothing found". No praise and no summary of what
  the code does.
