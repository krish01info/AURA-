# accessibility/ package

Reads what is on screen and finds UI nodes to act on.

| File | Role |
|---|---|
| AURAAccessibilityService.kt | The Android AccessibilityService entry point. Binds `ActionExecutor`, keeps the process alive, and resumes "Hey AURA" listening after a reboot if voice mode is on. |
| UIObserver.kt | Live UI snapshot. `getTextSnapshot()` → up to 2000 chars of visible text for the LLM prompt. |
| NodeFinder.kt | Finds nodes by view ID, falling back to text: `findNode`, `findClickableAncestorOrSelf`, `findFirstScrollable`, `findScrollableParent`, `nodeExists`. |

## Rules
- The 2000-char snapshot cap is the biggest part of each LLM prompt; change it with care.
- Gestures are performed in `agent/ActionExecutor.kt`, not here.
