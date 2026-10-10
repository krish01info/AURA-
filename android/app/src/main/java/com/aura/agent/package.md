# agent/ package

Owns the entire task execution lifecycle.

| File | Role |
|---|---|
| TaskManager.kt | Entry point. execute(goal) / executeDirectSteps(). Orchestrates everything. |
| ActionExecutor.kt | Converts ActionStep objects to real Android gestures. Bound to AccessibilityService. |
| SkillRouter.kt | L1 RAM → L2 Room skill lookup. Returns null → LLM fallback. |
| TaskState.kt | Sealed state machine: Created/Planning/Executing/WaitingForUser/Verifying/Completed/Failed/Cancelled |
| AURAForegroundService.kt | Keeps the agent alive as a foreground service. |
| BootReceiver.kt | Restarts service on device boot. |

## Key flow in TaskManager
1. sanitize goal → 2. skill cache hit? yes→run / no→memory+ui+LLM → 3. runStepsInternal loop
4. Per step: AppPermissionManager → PolicyEngine → LOW/MED auto-exec / HIGH/CRIT gate user
5. On success: MemoryManager.remember + SkillLearner.learnFromSuccess
