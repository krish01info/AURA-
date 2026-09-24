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
 * TaskManager — the orchestrator of the AURA agent.
 *
 * Responsibilities:
 * - Accepts goals from UI (typed or voice)
 * - Triggers LLM planning (Groq)
 * - Runs the observe-plan-act execution loop
 * - Gates execution at HIGH/CRITICAL risk steps
 * - Handles emergency stop
 * - Persists task audit log to Room DB
 */
@Singleton
class TaskManager @Inject constructor(
    private val groqClient: GroqClient,
    private val actionExecutor: ActionExecutor,
    private val policyEngine: PolicyEngine,
    private val taskLogDao: TaskLogDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    /** Observable state — the UI reads this to render current status. */
    private val _state = MutableStateFlow<TaskState>(TaskState.Created)
    val state: StateFlow<TaskState> = _state.asStateFlow()

    /** The current task's ID (used for audit logging). */
    private var currentTaskId: String? = null

    // ──────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────

    /**
     * Start executing a new goal.
     * Cancels any currently running task first.
     */
    fun execute(goal: String) {
        stopCurrentTask()

        val taskId = UUID.randomUUID().toString()
        currentTaskId = taskId

        currentJob = scope.launch {
            runTask(taskId, goal)
        }
    }

    /**
     * Emergency stop — halts all execution instantly.
     * Called by the EmergencyStopButton or voice command "Hey AURA stop".
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

    /** Reset state to Created (ready for next command). */
    fun reset() {
        _state.value = TaskState.Created
        currentTaskId = null
    }

    // ──────────────────────────────────────────────────────────────
    // Internal execution loop
    // ──────────────────────────────────────────────────────────────

    private suspend fun runTask(taskId: String, goal: String) {
        // Log task start
        taskLogDao.insert(
            TaskLogEntity(
                taskId = taskId,
                goal = goal,
                status = TaskStatus.PLANNING,
                startedAt = System.currentTimeMillis()
            )
        )

        try {
            // 1. PLANNING — ask Groq to generate action steps
            _state.value = TaskState.Planning
            Log.i(TAG, "Planning task [$taskId]: $goal")

            val uiSnapshot = actionExecutor.getUiSnapshot()
            val steps = groqClient.plan(goal, uiSnapshot)

            if (steps.isEmpty()) {
                fail(taskId, "LLM returned no action steps")
                return
            }

            // Handle clarification request from LLM
            val clarifyStep = steps.firstOrNull { it.action == ActionStep.CLARIFY }
            if (clarifyStep != null) {
                // TODO (Phase 2): Surface clarification question to user via UI
                fail(taskId, "Goal unclear: ${clarifyStep.clarification}")
                return
            }

            Log.i(TAG, "Plan ready — ${steps.size} steps: ${steps.map { it.action }}")

            // 2. EXECUTING — run steps one at a time
            for ((index, step) in steps.withIndex()) {
                // Check if cancelled
                if (_state.value == TaskState.Cancelled) return

                val risk = policyEngine.classify(step)

                when (risk) {
                    RiskLevel.LOW, RiskLevel.MEDIUM -> {
                        // Auto-execute
                        _state.value = TaskState.Executing(step, index, steps.size)
                        actionExecutor.execute(step)
                    }

                    RiskLevel.HIGH, RiskLevel.CRITICAL -> {
                        // Gate — wait for user approval
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

            // 3. VERIFYING
            _state.value = TaskState.Verifying

            // 4. COMPLETED
            _state.value = TaskState.Completed("Task completed: $goal")
            taskLogDao.updateStatus(
                taskId = taskId,
                status = TaskStatus.COMPLETED,
                finishedAt = System.currentTimeMillis(),
                error = null
            )
            Log.i(TAG, "✅ Task [$taskId] completed")

        } catch (e: Exception) {
            Log.e(TAG, "Task [$taskId] failed: ${e.message}", e)
            fail(taskId, e.message ?: "Unknown error")
        }
    }

    /**
     * Suspends execution and transitions to WaitingForUser state.
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

        // Block the coroutine until user responds
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            latch.await()
        }
        return result
    }

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
