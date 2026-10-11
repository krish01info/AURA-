# agent/ package

Owns the task execution lifecycle.

| File | Role |
|---|---|
| TaskManager.kt | Entry point. `execute(goal)` / `executeDirectSteps()`. Orchestrates everything. (~400 lines — open the function you need) |
| ActionExecutor.kt | Turns an `ActionStep` into a real gesture via the AccessibilityService. Also `getUiSnapshot()`. |
| SkillRouter.kt | Skill lookup L1 RAM (`SkillCache`) → L2 disk (`SkillDiskCache`) → L3 Room. Scores candidates (keywords, success rate, usage, verified, device). `null` → LLM plans. |
| TaskState.kt | Sealed states: Created / Planning / Executing / WaitingForUser / Verifying / Completed / Failed / Cancelled |
| AURAForegroundService.kt | Foreground service with a notification so Android doesn't kill the agent. |
| BootReceiver.kt | Restarts the service after reboot. |

## Key flow in TaskManager
1. Sanitize goal → 2. `SkillRouter` hit? yes → run stored steps / no → memory + UI snapshot + LLM plan
3. New LLM plan → `SkillLearner.learnFromSuccess()` right away (before the steps run)
4. `runStepsInternal`: per step `AppPermissionManager` → `PolicyEngine` → LOW/MEDIUM auto-run, HIGH/CRITICAL wait for the user
5. End: `MemoryManager.remember(goal, success|failure, app)`

## Rules
- New action type → constant in `ActionStep` companion + branch in `ActionExecutor.execute()`.
- Never decide risk here; ask `PolicyEngine.classify()`.
