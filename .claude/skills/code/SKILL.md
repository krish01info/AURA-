---
name: code
description: Write, fix and refactor Android (Kotlin/Java) code the user can explain line by line, add tests to untested code, and explain how existing code works. Enforces root-cause bug fixes with a failing test first, behavior-preserving refactors, clean OOP design and reusability, small incremental diffs, clear Android layer boundaries, lifecycle and thread safety, current Android rules for services, receivers, WorkManager, permissions and the manifest, and Gradle verification. Starts with a plan the user approves.
argument-hint: "[what to build, fix, refactor, test or explain]"
---

# Android Code

Explainable and a little longer beats short and clever. A line the user can't explain gets rewritten, even if it
compiles and works.

Pick the workflow that fits the request:
- **New code:** start with a plan (below), then build it in small steps.
- **Refactor:** the refactoring workflow.
- **Something behaves wrongly:** the bug-fix workflow.
- **Important code has no tests:** adding tests to old code.
- **The user wants to understand code:** investigate and explain. It changes nothing, so it needs no plan.

## Before you start
- Read the repo's rules (`agents/repo_rules.md` and `agents/this_repo.md`, if present), its `HANDOFF.md`, and its
  `memory/MEMORY.md`. Where they disagree with this file, the repo wins.
- Read `settings.gradle(.kts)` and the relevant `build.gradle(.kts)` to confirm the exact stack (Kotlin/Java,
  Compose/XML, Hilt/Koin, Coroutines/Flow, Room/Retrofit, minSdk/targetSdk, linters).
- Before refactoring any class or function, find and read all its callers, tests, XML/navigation references, and
  serialization or database annotations. Match the conventions already in the repo.

## Start with a plan
Always show a plan and wait for the user's yes before changing anything. Scale the plan to the change:
- **Small change** (one file, clearly described, such as a rename or one new test): three lines saying the file,
  what changes, and how it will be checked, then ask "OK?".
- **Anything bigger:** enter plan mode (where your tool has none, write the plan in the chat). The plan lists:
  - every file to create, change, or delete, and what changes in each
  - responsibilities being moved or extracted (new classes/functions by name, one line each on what they do)
  - risks to watch out for (lifecycle, threading, serialization, Room schema, navigation args)
  - the exact Gradle command that will be run to verify the work
  - anything still undecided, as a short question
- Discuss the structure until the user approves the plan. Change nothing before that.
- If the open question is *what* to change rather than *how*, settle that in conversation first instead of inside the
  plan.

## Refactoring workflow (preserve behavior)
When refactoring existing Android code, safety and clarity come first:
1. **Baseline first:** run `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` (or the module's test task)
   *before* editing so you know what already compiles and passes.
2. **Preserve behavior strictly:** a refactor changes structure, not behavior. Never silently alter UI flows,
   navigation routes/arguments, JSON/Gson/Moshi/Kotlinx Serialization field names (`@SerializedName`, `@SerialName`),
   Room table/column names, SharedPreferences/DataStore keys, or Manifest component names unless explicitly asked.
3. **Small, incremental steps:** refactor one screen, ViewModel, or repository per step. Extract clean pieces first,
   verify the build/tests, then remove replaced code. Avoid rewriting 10 files at once.
4. **Fix the real smell without over-engineering:**
   - Pull business, network, and DB logic out of Activities, Fragments, and Composables.
   - Break up God ViewModels or God Repositories by clear domain responsibility.
   - Do *not* invent empty `UseCase` pass-throughs, generic `BaseViewModel`/`BaseFragment` hierarchies, or extra Gradle
     modules unless the repo already uses them.
5. **Keep diffs clean:** no unrelated reformatting of untouched files, no surprise library upgrades, and no mixed
   feature-plus-refactor commits.

## Bug-fix workflow
Use this when something behaves wrongly. A fix changes behavior only where the bug is.
1. **Read the evidence first:** the stack trace, logcat, the failing CI annotation, or the user's exact steps.
   Don't guess from the symptom.
2. **Reproduce it:** find the smallest steps or input that show the bug. If you can't, say so and ask for more
   evidence before changing anything.
3. **Find the root cause and say it in one sentence.** A cause names the code: "`GroqClient.complete` catches
   `CancellationException`, so a stopped task keeps going". "Stop shows an error" is a symptom. Show the user the
   cause and the planned fix, and wait for a yes.
4. **Check git history** when the cause isn't obvious or the line looks deliberate: `git log -S "<code>"`,
   `git blame`, or `git bisect` between a good and a bad commit. Read the commit that added the line, because it may
   guard against something.
5. **Write a test that fails because of the bug,** and run it to see it fail for the right reason. Where the code
   can't run on the JVM, write down the manual steps that show the bug instead.
6. **Make the smallest fix:** no refactoring, renames or reformatting in the same change.
7. **Run the test again and see it pass,** then all the unit tests and the build.
8. **Look for siblings:** search for the same pattern elsewhere and list every place. Fix them in this change only
   if the user agrees, each with its own test.
9. **Hand over:** the cause in one sentence, the fix, the test that now passes, the siblings found, and what wasn't
   checked (for example on a phone).

## Adding tests to old code
Use this when code that matters has no tests. The goal is to pin down what it does today, not what it should do.
1. **Pick one class and list its behaviors** from reading the code: each branch, early return and error path, as
   plain sentences ("a denied step stops the task"). Show the list to the user before writing tests.
2. **Test what it does now, even when that looks wrong.** Name the test after what happens, add a short comment,
   and tell the user. Fixing it is a separate bug fix.
3. **Change production code only to make it testable,** in small behavior-preserving steps: pass in a clock,
   dispatcher or scope instead of creating it, extract a pure function, or add a narrow interface at a real boundary
   so a fake can stand in. List each such change in the plan and get it approved.
4. **Write the tests as the Testing section says:** fakes over mocks, one behavior per test, and each one seen
   failing once.
5. **Hand over:** the behaviors covered, the ones not covered and why, and anything that looked like a bug.

## Investigate and explain
Use this when the user wants to understand code, not change it ("what happens when I say Hey AURA?"). Change
nothing.
1. **Restate the question in one line** and name the entry point you'll start from: a screen, a service or a
   public function.
2. **Follow the real path:** read the code instead of guessing from names, from the entry point through each call
   that matters to where the work ends.
3. **Explain it as numbered steps,** one per hop: `file:line`, what happens there and why, in plain words. Skip
   plumbing such as DI and logging unless asked.
4. **Point out the decisions:** branches, error paths, which thread or dispatcher runs each part, and anything
   surprising or risky. A suspected bug is reported, not fixed.
5. **End with where to read first:** the one or two places worth opening, and a text diagram for a long flow.
   Don't quiz the user.
6. **Say what you didn't read** and what you're unsure about.

## Who reads the code
The user, and any Android developer who knows Kotlin/Java and basic Android lifecycle concepts but has never seen this
project. After one read they can say what every line does and why it is there.

## Android layer boundaries
Follow the repo's existing package layout, and keep responsibilities separated:
- **UI layer (Composables, Activities, Fragments):** renders state and forwards user actions. No direct network calls,
  database queries, or business rules.
- **State holder (`ViewModel`):** exposes immutable UI state (`StateFlow` or `LiveData`) and handles screen events.
  Never hold references to `Activity`, `Fragment`, `View`, or a UI `Context`.
- **Data layer (`Repository`, `DataSource`):** owns API calls, Room/DataStore access, and caching. Maps network DTOs
  and DB entities into clean app models at the boundary.
- **Domain helpers / UseCases:** only add when logic is shared across multiple ViewModels or complex enough to need
  its own unit-tested unit. Otherwise keep it simple.

## OOP design and reusability
Write clean object-oriented Kotlin/Java that stays easy to extend and reuse without turning into boilerplate:
- **Encapsulation and immutability:**
  - Keep properties and helper functions `private` or `internal` by default; expose only the contract callers need.
  - Never expose `MutableStateFlow`, `MutableLiveData`, or `MutableList` publicly. Expose read-only `StateFlow`,
    `LiveData`, or `List` backed by a private mutable field.
  - Prefer `val` and immutable `data class` state updates (`copy(...)`) so state changes happen in one predictable place.
- **SOLID and polymorphism (pragmatic, not dogmatic):**
  - **Single responsibility:** one clear reason to change per class, ViewModel, Repository, or Composable.
  - **Composition over inheritance:** inject collaborators through constructors instead of building `BaseActivity`,
    `BaseFragment`, or `BaseViewModel` chains. Keep any inheritance shallow.
  - **Polymorphism over repeated branches:** when multiple variants share a contract (UI states, feed items, payment or
    auth types), model them with a `sealed interface` or small focused interface instead of duplicating long `if`/`when`
    chains across files.
  - **Dependency inversion at real boundaries:** depend on narrow interfaces for repositories, data sources, clocks, or
    dispatchers so logic is swappable and unit-testable. Do not create 1-to-1 interfaces for simple internal classes
    that have no real abstraction boundary.
- **Reusability (Rule of Two, no premature abstraction):**
  - Extract a shared Composable/custom View, mapper, validator, or domain helper once it is used in **2 or more places**
    (or when extracting it makes a complex screen clearly easier to read).
  - Keep reusable UI components stateless and decoupled: accept plain data, event lambdas (`onClick`, `onChange`), and
    `modifier: Modifier = Modifier` (for Compose). Never pass a `ViewModel` or `NavController` into a reusable UI
    component.
  - Group shared code by domain (`PriceFormatter`, `AuthValidator`, `OrderMapper`), not in a catch-all `Utils.kt`.
  - Solve the concrete reuse cases in the repo; do not add speculative flags or generic abstractions nobody asked for.
- **Types and naming:**
  - Use `data class` for models and UI state snapshots, and `sealed interface`/`sealed class` for finite states/events.
  - One job per function, short enough to explain in a minute.
  - Plain domain names (`user`, `uiState`, `loadOrders`, `formatPrice`). Avoid vague names like `Manager`, `Helper`,
    `Util`, or `Processor` unless the repo already has a clear pattern for them.

## Inside Kotlin and Android code
- **Readable control flow:** prefer clear `if`, `when`, and early returns over chained scope functions. Do not stack
  `?.let { it.apply { ... } }` or clever one-liners. Write three plain lines instead.
- **Null safety:** avoid `!!`. Use safe calls (`?.`), Elvis (`?:`), early returns, or `requireNotNull(x) { "reason" }`
  when an invariant must hold.
- **Threading and Coroutines:**
  - Never run network, database, disk I/O, or heavy loops on `Dispatchers.Main`. Switch explicitly with
    `withContext(Dispatchers.IO)` or `Dispatchers.Default` in the data/domain layer.
  - Never use `GlobalScope`. Use structured concurrency (`viewModelScope`, `lifecycleScope`, or an injected
    `CoroutineScope`).
  - Do not swallow `CancellationException` in generic `catch (e: Exception)` blocks.
- **Lifecycle safety:**
  - In Compose, collect flows with `collectAsStateWithLifecycle()`.
  - In Views/Fragments, collect flows inside `viewLifecycleOwner.lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { ... } }`.
  - In Fragments using ViewBinding, always clear `_binding = null` in `onDestroyView()`.
- **Jetpack Compose (when used):**
  - Hoist state out of leaf Composables; pass immutable state down and event lambdas up.
  - Never run side effects (network calls, navigation, logging, state mutations) directly inside the Composable body;
    use `LaunchedEffect`, `DisposableEffect`, or event callbacks.
  - Keep Composables small and focused; avoid heavy allocations or sorting directly during recomposition without
    `remember`.
- **Cleanliness:** no dead code, unused imports, unused parameters, or options nobody asked for. Don't guard against
  cases that can't happen; do handle real failures (network errors, missing permissions, null API fields, empty states).
- **Formatting:** standard 4-space indentation (Kotlin official style). Never add a new Gradle dependency without
  asking first.

## Android parts without a screen
Services, receivers, background jobs, permissions and the manifest break in ways screens don't. The rest of this
skill still applies: explainable code, one job per class, logic out of the Android component.

- **Check the current rules first.** Android changes them almost every release. Before writing service, receiver,
  background-job, permission or manifest code, read the official docs for the app's `targetSdk` and name the page
  you used. Don't rely on memory.
- **Keep the component thin.** A `Service`, `BroadcastReceiver` or `Worker` only receives the event and hands it to
  a plain class that holds the logic, so that class can be tested and explained on its own.

**Services**
- Use a foreground service only for work the user can see and would miss if it stopped, such as listening to the
  mic. Declare its `foregroundServiceType` and the matching permission in the manifest.
- Since Android 12, an app in the background usually can't start a foreground service. A mic or camera service
  must start while the app is in front: on Android 14+ it throws `SecurityException` otherwise, and older versions
  start it without mic or camera access. Plan where it starts, and handle the start failing.
- Call `startForeground()` right after starting, stop the service when the work ends, and cancel its coroutine
  scope in `onDestroy()`.
```kotlin
// the type must match foregroundServiceType in the manifest (required on Android 14+)
ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
```

**Broadcast receivers**
- `onReceive` runs on the main thread and must return within seconds. Hand longer work to WorkManager.
- A receiver registered in code needs an exported flag on Android 14+. Unregister it when you're done.
```kotlin
// only this app sends this broadcast, so other apps must not reach the receiver
ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
```

**Background jobs (WorkManager)**
- Use WorkManager, not a service, for work that must happen eventually even if the app closes, such as syncs and
  daily checks.
- Give repeating work a unique name (`enqueueUniquePeriodicWork`), so it's never scheduled twice.
- Write workers so running twice does no harm. Return `Result.retry()` for temporary failures like no network.

**Runtime permissions**
- Ask when the user starts the feature that needs it, with one sentence on why. Never ask for everything at app
  start.
- Check the permission every time before using it, because the user can take it back at any time.
- Handle "deny" and "don't ask again": the feature explains what's missing and how to turn it on in Settings.
```kotlin
val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
    PackageManager.PERMISSION_GRANTED
if (!granted) return MicResult.NeedsPermission // the screen asks the user; this class never does
```

**Special permissions** (overlay, Accessibility, notification access, exact alarms, installing apps)
- The user turns these on in system Settings, not in a dialog. Explain why first, open the right Settings screen,
  and check again when the user comes back.

**The manifest**
- Every new permission, exported component, intent filter or `<queries>` entry needs the user's yes. Say what it
  allows.
- Set `android:exported` on purpose: `false` unless another app must start the component. If it's `true`, protect it
  with a permission, as system-bound services do with their `BIND_...` permission.
- Since Android 11, other apps are hidden unless listed in `<queries>`. Add only what's needed.

**The app can be killed**
- Android can stop the app at any time, even in the middle of a task. Save what must survive (Room, DataStore) and
  make sure the restart path works.
- Never keep an `Activity`, `View` or screen `Context` in a singleton or a static field, because it leaks memory.
  Use the application context.

**Android versions**
- Wrap any API newer than `minSdk` in `if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.X)` and handle the older
  path, or use the `Compat` helper that does it for you.

## Comments and KDoc
- A comment gives the *why*, not the *what*, and never git history ("changed from X"). Good reasons to comment:
  - an Android version or OEM quirk (`// API 33+ requires POST_NOTIFICATIONS before showing foreground notification`)
  - a threading or lifecycle invariant (`// main-safe: repository switches to Dispatchers.IO internally`)
  - a serialization or schema constraint (`// matches backend v2 payload key; do not rename`)
- Short and lowercase, one or two lines. No banner comments (`// ---- helpers ----`) and no KDoc on every function.
- No em dashes, no words in capitals for emphasis, no filler jargon.

## Testing

**When a test is required**
- Every bug fix: a test that failed before the fix (see the bug-fix workflow).
- Every logic change: decisions, parsing, state changes, mappers. Code that decides what runs without asking
  (permissions, risk, payments) always gets one.
- Not required for layout and styling, or for thin Android glue that only works on a device (services, overlays,
  speech, camera). Write phone-test steps for those instead.
- Code that's hard to test is a design signal: pull the decision into a plain function and test that, rather than
  skipping the test.

**How to write one**
- JVM tests go in `app/src/test/`, in the same package as the code, in a class named `<Class>Test`.
- One behavior per test, named as a sentence in backticks (`` `unknown amount does not match an allow rule` ``).
  Set up, act, check, with no loops or `if`s inside a test.
- Prefer hand-written fakes that implement the real interface (a DAO, a client) over mocks. Don't add a mocking
  library unless the repo already has one.
- Pass in what you don't control, such as a clock, a dispatcher, a scope or a `Uri` parser. Never use
  `Thread.sleep` or the real network in a unit test.
- For coroutines, use `runTest` from `kotlinx-coroutines-test`, so `delay()` runs instantly. For ViewModels, swap
  the main dispatcher with `Dispatchers.setMain(StandardTestDispatcher())` and reset it after.
- Android classes like `Uri`, `Log` and `Context` return defaults or null on the JVM. Keep them out of the logic,
  or pass them in.
- Use made-up names, numbers and messages, never real personal data.
- See each new test fail once by breaking the line it covers, then see it pass.

**Phone tests**
- For a change that affects the phone, write the test as steps: what to do or say, what should happen, and what to
  check in logcat if it doesn't. If the repo keeps a phone-test list (named in `this_repo.md`), add or update its
  row and mark it untested until the user confirms it passed.

## Checking and handing over
- Run `./gradlew assembleDebug` and `./gradlew testDebugUnitTest` (plus `./gradlew lintDebug` or ktlint/detekt if
  configured in the repo) before handing over.
- Before showing the result, check the diff against `checklist.md` and fix what it finds. If your tool can start a
  fresh subagent, give `checklist.md` to one so the code is reviewed independently.
- Hand over concisely:
  1. what changed and why
  2. which Gradle commands were run and passed
  3. what was *not* checked (e.g., manual UI/navigation check on a device or emulator)

## Examples
Drop 2 or 3 clean, real files from your Android repo into `examples/` (such as a `ViewModel`, a `Repository`, and a
Composable or Fragment) so your assistant matches your project's exact style.
