# accessibility/ package

Reads what is on screen and finds UI nodes to act on.

| File | Role |
|---|---|
| AURAAccessibilityService.kt | The Android AccessibilityService entry point. Binds `ActionExecutor` + `UIObserver`, keeps the process alive, and resumes "Hey AURA" listening after a reboot if voice mode is on. Events must stay cheap. |
| UIObserver.kt | `getTextSnapshot()` builds, on demand, role-tagged lines (`[button] Send #id`, `[input] …`, `[text] …`, ≤2000 chars) for the LLM. `onEvent()` only records when the screen changed; `waitForStable()` waits until it stops changing. |
| NodeFinder.kt | Finds nodes by view ID, falling back to text: `findNode`, `findClickableAncestorOrSelf`, `findFirstScrollable`, `findScrollableParent`, `nodeExists`. |

## Rules
- The 2000-char snapshot cap is the biggest part of each LLM prompt; change it with care.
- Gestures are performed in `agent/ActionExecutor.kt`, not here.
