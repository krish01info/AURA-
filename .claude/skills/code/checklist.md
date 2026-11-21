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
6. **Scope & cleanliness:** Dead code, unused imports or parameters, unrelated formatting diffs, speculative guards for
   impossible cases, or an unapproved Gradle dependency?
7. **Comments:** Missing *why* on non-obvious Android/API workarounds, comments that repeat the code or tell history,
   banner comments, or em dashes?
8. **Verification:** Were `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` (and lint/detekt/ktlint if
   configured) run after the final edit?

## Report
- Group by file and keep the order above. One line per problem: `path:line`, what is wrong, then the plain fix (as
  short code if helpful).
- Mark a judgment call as one, and say why.
- End with the number of problems, or "Nothing found". No praise and no summary of what the code does.
