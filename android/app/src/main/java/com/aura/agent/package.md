# agent/ package

Owns the task execution lifecycle.

| File | Role |
|---|---|
| TaskManager.kt | Entry point. `execute(goal)` / `executeDirectSteps()`. Orchestrates everything; `setupProblem()` explains what's missing (Accessibility off, no API key). (~460 lines — open the function you need) |
| ActionExecutor.kt | Turns an `ActionStep` into a real action and returns a `StepResult` (ok / user-readable error). Screen actions via the AccessibilityService (waits up to 4 s for nodes, then for the screen to settle); shortcut actions via Android intents (`call` — direct with the Phone permission, else dialer + its Call button; `dial` only opens the dialer; `sms`, `alarm`, `timer`, `open_link`, generic `intent` on an allow-list — `isAllowedIntent`); `media` keys; `now_playing` / `reply` / `read_notifications` via `notifications/NotificationStore`; `verify` checks the right app/text is on screen. |
| ContactResolver.kt | Spoken name → exact contact + `{contact_number}` / `{contact_number_digits}`; asks when two people match. Needs READ_CONTACTS (optional). |
| TimeParser.kt | "7:30 pm" / "5 minutes" → numbers for alarms and timers (pure, tested). |
| SkillRouter.kt | `findSkill(goal)` → `SkillMatch` (variant + filled steps). L1 RAM → L2 disk → L3 Room → L4 cloud; only variants compatible with this phone whose phrase pattern matches; the most specific phrase wins, then the most specific variant (shortcut steps get a small bonus). `null` → LLM plans. |
| TaskState.kt | Sealed states: Created / Planning / Executing / WaitingForUser / Verifying / Completed / Failed / Cancelled |

## Key flow in TaskManager
1. Sanitize goal; stop early with a spoken reason if `setupProblem()` is not null
2. Stored skill (`SkillRouter.findSkill`, contacts resolved inside)? use its filled steps. Else LLM plan (Groq → Gemini) → `Plan`; contacts resolved; filled; a `verify` step added if missing (`withVerify`)
3. `runWithRecovery`: `runStepsInternal` runs steps (permission → policy → approval for HIGH/CRITICAL); on a failed step it asks the LLM for the **remaining** steps from the current screen (`PlannerPrompt.continueMessage`), up to `MAX_RECOVERIES` = 2
4. Success (incl. the final `verify`) → `finishSuccess`; stored skill unchanged → `SkillUpdater.recordSuccess`; new or recovered path → `SkillLearner.learn` with the steps that actually worked (templatized)
5. Failure → spoken reason; a stored skill also gets `SkillUpdater.handleFailure`

## Rules
- New action type → constant in `ActionStep` companion + branch in `ActionExecutor.execute()` returning a `StepResult` + line in `llm/PlannerPrompt.SYSTEM` + risk in `policy/PolicyEngine` (unknown = HIGH) + any new field in `SkillMatcher.fill/templatize` + add it to `ActionStep.KNOWN_ACTIONS` (older apps skip shortcuts using actions they lack).
- Never decide risk here; ask `PolicyEngine.classify()`.
- The Accessibility service keeps the process alive; there is no separate keep-alive service.
