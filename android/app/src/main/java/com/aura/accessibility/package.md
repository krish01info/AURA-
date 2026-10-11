# accessibility/ package

Reads what is on screen and finds UI nodes to act on.

| File | Role |
|---|---|
| AURAAccessibilityService.kt | The Android AccessibilityService entry point (declared in the manifest). |
| UIObserver.kt | Live UI snapshot. `getTextSnapshot()` → up to 2000 chars of visible text for the LLM prompt. |
| NodeFinder.kt | Finds nodes by text or view ID: `findNode`, `findClickableAncestorOrSelf`, `findFirstScrollable`, `findScrollableParent`, `nodeExists`. |

## Rules
- The 2000-char snapshot cap is the biggest part of each LLM prompt; change it with care.
- Gestures are performed in `agent/ActionExecutor.kt`, not here.
