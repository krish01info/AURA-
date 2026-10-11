# agent/ package

Owns the task execution lifecycle.

| File | Role |
|---|---|
| TaskManager.kt | Entry point. `execute(goal)` / `executeDirectSteps()`. Orchestrates everything; `setupProblem()` explains what's missing (Accessibility off, no API key). (~460 lines — open the function you need) |
| ActionExecutor.kt | Turns an `ActionStep` into a real action via the AccessibilityService and returns a `StepResult` (ok / user-readable error). Waits up to 4 s for nodes; opens apps by package or by app name. |
| SkillRouter.kt | `findSkill(goal)` → `SkillMatch` (variant + filled steps). L1 RAM → L2 disk → L3 Room → L4 cloud; only variants compatible with this phone whose phrase pattern matches; most specific wins. `null` → LLM plans. |
| TaskState.kt | Sealed states: Created / Planning / Executing / WaitingForUser / Verifying / Completed / Failed / Cancelled |

## Key flow in TaskManager
1. Sanitize goal; stop early with a spoken reason if `setupProblem()` is not null
2. Stored skill (`SkillRouter.findSkill`)? run its filled steps → success: `SkillUpdater.recordSuccess` / a step fails: `SkillUpdater.handleFailure`, then re-plan with the LLM once
3. LLM plan: Groq → Gemini fallback → `Plan` (steps + intent + params + patterns); steps are filled with the params to run, and templatized to save
4. `runStepsInternal`: per step `AppPermissionManager` → `PolicyEngine` → LOW/MEDIUM run, HIGH/CRITICAL wait for approval; **stops at the first failed step**
5. Success → `SkillLearner.learn(goal, plan, template)` (only after the plan worked) + `MemoryManager.remember`

## Rules
- New action type → constant in `ActionStep` companion + branch in `ActionExecutor.execute()` returning a `StepResult` + line in `llm/PlannerPrompt.SYSTEM`.
- Never decide risk here; ask `PolicyEngine.classify()`.
- The Accessibility service keeps the process alive; there is no separate keep-alive service.
