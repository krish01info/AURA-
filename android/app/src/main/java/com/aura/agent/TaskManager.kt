package com.aura.agent

import android.util.Log
import com.aura.data.db.TaskLogDao
import com.aura.data.model.ActionStep
import com.aura.data.model.TaskLogEntity
import com.aura.data.model.TaskStatus
import com.aura.llm.GroqClient
import com.aura.policy.PolicyEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TaskManager"

/**
 * TaskManager — the central orchestrator of the AURA agent.
 *
 * Responsibilities:
 *  - Accepts goals from the UI (typed or voice)
 *  - Checks [SkillRouter] for a cached execution plan before calling the LLM
 *  - Triggers LLM planning via [GroqClient] when no skill is cached
 *  - Runs the observe-plan-act execution loop step by step
 *  - Gates HIGH / CRITICAL risk steps — waits for user approval
 *  - Handles emergency stop and soft pause
 *  - Persists every task to the Room audit log via [TaskLogDao]
 *
 * State machine:
 *   Created → Planning → Executing → [WaitingForUser →] Verifying → Completed
 *   Any state → Cancelled  (emergency stop)
 *   Any state → Failed      (error / user denied)
 */
@Singleton
class TaskManager @Inject constructor(
    private val groqClient: GroqClient,
    private val actionExecutor: ActionExecutor,
    private val policyEngine: PolicyEngine,
    private val skillRouter: SkillRouter,
    private val taskLogDao: TaskLogDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    /** Observable state — the UI collects this to render current status. */
    private val _state = MutableStateFlow<TaskState>(TaskState.Created)
    val state: StateFlow<TaskState> = _state.asStateFlow()

    /** The current task's DB ID (used for audit logging). */
    private var currentTaskId: String? = null

    // ──────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────

    /**
     * Start executing a natural-language goal.
     * Checks [SkillRouter] for a cached plan first, then falls back to Groq LLM.
     * Cancels any currently running task first.
     */
    fun execute(goal: String) {
        stopCurrentTask()
        val taskId = UUID.randomUUID().toString()
        currentTaskId = taskId
        currentJob = scope.launch { runTask(taskId, goal) }
    }

    /**
     * Execute a pre-built list of [ActionStep]s directly — NO LLM call.
     *
     * Used for:
     *  - Phase 1 hard-coded "Open WhatsApp" end-to-end test
     *  - Future: skill cache replay
     *  - Future: macro / recipe execution
     *
     * @param label  Human-readable name shown in the task log (e.g. "Test: Open WhatsApp")
     * @param steps  The exact action sequence to run
     */
    fun executeDirectSteps(label: String, steps: List<ActionStep>) {
        stopCurrentTask()
        val taskId = UUID.randomUUID().toString()
        currentTaskId = taskId
        currentJob = scope.launch { runSteps(taskId, label, steps) }
    }

    /**
     * Emergency stop — halts all execution instantly.
     * Called by the EmergencyStopButton or future voice command "Hey AURA stop".
     */
    fun emergencyStop() {
        Log.w(TAG, "🛑 Emergency stop triggered!")
        stopCurrentTask()
        _state.value = TaskState.Cancelled
        currentTaskId?.let { id ->
            scope.launch {
                taskLogDao.updateStatus(
                    taskId = id,
                    status = TaskStatus.CANCELLED,
                    finishedAt = System.currentTimeMillis(),
                    error = "User cancelled"
                )
            }
        }
    }

    /**
     * Soft pause — suspend current execution without destroying the task.
     * Called from [AURAAccessibilityService.onInterrupt] when the system
     * temporarily interrupts the service (e.g. incoming call).
     *
     * Current implementation cancels like emergency stop, but is kept separate
     * so Phase 4+ can implement true suspend/resume semantics.
     */
    fun pause() {
        Log.w(TAG, "⏸ Task paused (system interrupt)")
        stopCurrentTask()
        // Keep the last state visible so the user can see what was interrupted.
        // Do NOT transition to Cancelled — that is only for user-initiated stops.
    }

    /** Reset state to Created (ready for next command). */
    fun reset() {
        _state.value = TaskState.Created
        currentTaskId = null
    }

    // ──────────────────────────────────────────────────────────────
    // LLM-backed task execution
    // ──────────────────────────────────────────────────────────────

    private suspend fun runTask(taskId: String, goal: String) {
        taskLogDao.insert(
            TaskLogEntity(
                taskId = taskId,
                goal = goal,
                status = TaskStatus.PLANNING,
                startedAt = System.currentTimeMillis()
            )
        )

        try {
            _state.value = TaskState.Planning
            Log.i(TAG, "Planning task [$taskId]: $goal")

            // 1. Check skill cache first (Phase 2b will populate this)
            val steps: List<ActionStep> = skillRouter.findSkill(goal)
                ?: run {
                    // 2. Fall back to Groq LLM
                    val uiSnapshot = actionExecutor.getUiSnapshot()
                    val llmSteps = groqClient.plan(goal, uiSnapshot)
                    if (llmSteps.isNotEmpty()) {
                        // Auto-save for future use
                        scope.launch { skillRouter.learnSkill(goal, llmSteps) }
                    }
                    llmSteps
                }

            if (steps.isEmpty()) {
                fail(taskId, "LLM returned no action steps for goal: $goal")
                return
            }

            val clarify = steps.firstOrNull { it.action == ActionStep.CLARIFY }
            if (clarify != null) {
                fail(taskId, "Goal unclear — ${clarify.clarification}")
                return
            }

            Log.i(TAG, "Plan ready — ${steps.size} steps: ${steps.map { it.action }}")
            runStepsInternal(taskId, steps)

        } catch (e: Exception) {
            Log.e(TAG, "Task [$taskId] failed: ${e.message}", e)
            fail(taskId, e.message ?: "Unknown error")
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Direct step execution (no LLM — Phase 1 test path)
    // ──────────────────────────────────────────────────────────────

    private suspend fun runSteps(taskId: String, label: String, steps: List<ActionStep>) {
        taskLogDao.insert(
            TaskLogEntity(
                taskId = taskId,
                goal = label,
                status = TaskStatus.EXECUTING,
                startedAt = System.currentTimeMillis()
            )
        )
        try {
            Log.i(TAG, "Direct execution [$taskId]: $label (${steps.size} steps)")
            runStepsInternal(taskId, steps)
        } catch (e: Exception) {
            Log.e(TAG, "Direct task [$taskId] failed: ${e.message}", e)
            fail(taskId, e.message ?: "Unknown error")
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Shared step execution loop
    // ──────────────────────────────────────────────────────────────

    private suspend fun runStepsInternal(taskId: String, steps: List<ActionStep>) {
        for ((index, step) in steps.withIndex()) {
            if (_state.value == TaskState.Cancelled) return

            val risk = policyEngine.classify(step)

            when (risk) {
                RiskLevel.LOW, RiskLevel.MEDIUM -> {
                    _state.value = TaskState.Executing(step, index, steps.size)
                    actionExecutor.execute(step)
                }
                RiskLevel.HIGH, RiskLevel.CRITICAL -> {
                    val approved = suspendForApproval(step, risk)
                    if (!approved) {
                        fail(taskId, "User denied action: ${step.action}")
                        return
                    }
                    _state.value = TaskState.Executing(step, index, steps.size)
                    actionExecutor.execute(step)
                }
            }
        }

        _state.value = TaskState.Verifying
        _state.value = TaskState.Completed("Done!")
        taskLogDao.updateStatus(
            taskId = taskId,
            status = TaskStatus.COMPLETED,
            finishedAt = System.currentTimeMillis(),
            error = null
        )
        Log.i(TAG, "✅ Task [$taskId] completed")
    }

    // ──────────────────────────────────────────────────────────────
    // Approval gate
    // ──────────────────────────────────────────────────────────────

    /**
     * Suspends execution and transitions to [TaskState.WaitingForUser].
     * Returns true if the user approved, false if denied.
     */
    private suspend fun suspendForApproval(step: ActionStep, risk: RiskLevel): Boolean {
        var result = false
        val latch = java.util.concurrent.CountDownLatch(1)

        _state.value = TaskState.WaitingForUser(
            riskAction = step,
            riskLevel = risk,
            onAllow = {
                result = true
                latch.countDown()
            },
            onDeny = {
                result = false
                latch.countDown()
            }
        )

        kotlinx.coroutines.withContext(Dispatchers.IO) { latch.await() }
        return result
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    private fun fail(taskId: String, reason: String) {
        _state.value = TaskState.Failed(reason)
        scope.launch {
            taskLogDao.updateStatus(
                taskId = taskId,
                status = TaskStatus.FAILED,
                finishedAt = System.currentTimeMillis(),
                error = reason
            )
        }
        Log.e(TAG, "❌ Task [$taskId] failed: $reason")
    }

    private fun stopCurrentTask() {
        currentJob?.cancel()
        currentJob = null
    }
}
