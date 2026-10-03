package com.aura.agent

import android.util.Log
import com.aura.data.db.TaskLogDao
import com.aura.data.model.ActionStep
import com.aura.data.model.TaskLogEntity
import com.aura.data.model.TaskStatus
import com.aura.llm.GroqClient
import com.aura.memory.MemoryManager
import com.aura.policy.AppPermissionManager
import com.aura.policy.AnomalyDetector
import com.aura.policy.AnomalyResult
import com.aura.policy.AuditLogger
import com.aura.policy.PermissionDecision
import com.aura.policy.PolicyEngine
import com.aura.policy.PromptInjectionDefender
import com.aura.policy.ValidationResult
import com.aura.skills.SkillLearner
import com.aura.voice.VoiceManager
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
    private val skillLearner: SkillLearner,
    private val taskLogDao: TaskLogDao,
    private val auditLogger: AuditLogger,
    private val voiceManager: VoiceManager,
    private val memoryManager: MemoryManager,
    private val injectionDefender: PromptInjectionDefender,
    private val anomalyDetector: AnomalyDetector,
    private val permissionManager: AppPermissionManager
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
        val sanitized = injectionDefender.sanitize(goal)
        when (val validation = injectionDefender.validateInput(sanitized)) {
            is ValidationResult.Injected -> {
                _state.value = TaskState.Failed("Rejected: ${validation.reason}")
                voiceManager.speak("I can't do that — ${validation.reason}")
                return
            }
            ValidationResult.Safe -> { /* continue */ }
        }
        stopCurrentTask()
        val taskId = UUID.randomUUID().toString()
        currentTaskId = taskId
        currentJob = scope.launch { runTask(taskId, sanitized) }
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
        voiceManager.stopSpeaking()
        voiceManager.speak("Stopped.")
        _state.value = TaskState.Cancelled
        currentTaskId?.let { id ->
            auditLogger.logEmergencyStop(id)
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

            // 1. Check skill cache first (L1 RAM → L2 Room via SkillRouter)
            val cachedSkill = skillRouter.findBestSkill(goal)
            val steps: List<ActionStep> = if (cachedSkill != null) {
                // Deserialize cached skill steps
                val parsed = try {
                    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        .decodeFromString(
                            kotlinx.serialization.builtins.ListSerializer(ActionStep.serializer()),
                            cachedSkill.steps
                        )
                } catch (_: Exception) { emptyList() }
                if (parsed.isNotEmpty()) parsed else null
            } else null
                ?: run {
                    // 2. Recall memory context for richer LLM prompt
                    val memoryContext = memoryManager.buildContextString(goal)

                    // 3. Fall back to Groq LLM with memory context
                    val uiSnapshot = actionExecutor.getUiSnapshot()
                    val llmSteps = groqClient.plan(goal, uiSnapshot, memoryContext = memoryContext)
                    if (llmSteps.isNotEmpty()) {
                        // Auto-learn the new skill (OS-tagged, canonical ID)
                        val appPkg = llmSteps.firstOrNull { it.pkg != null }?.pkg ?: "unknown"
                        scope.launch { skillLearner.learnFromSuccess(goal, llmSteps, appPkg) }
                    }
                    llmSteps
                }

            if (steps.isEmpty()) {
                fail(taskId, goal, "LLM returned no action steps for goal: $goal")
                return
            }

            val clarify = steps.firstOrNull { it.action == ActionStep.CLARIFY }
            if (clarify != null) {
                fail(taskId, goal, "Goal unclear — ${clarify.clarification}")
                return
            }

            Log.i(TAG, "Plan ready — ${steps.size} steps: ${steps.map { it.action }}")
            runStepsInternal(taskId, goal, steps)

        } catch (e: Exception) {
            Log.e(TAG, "Task [$taskId] failed: ${e.message}", e)
            fail(taskId, goal, e.message ?: "Unknown error")
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
            runStepsInternal(taskId, label, steps)
        } catch (e: Exception) {
            Log.e(TAG, "Direct task [$taskId] failed: ${e.message}", e)
            fail(taskId, label, e.message ?: "Unknown error")
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Shared step execution loop
    // ──────────────────────────────────────────────────────────────

    private suspend fun runStepsInternal(taskId: String, goal: String, steps: List<ActionStep>) {
        val primaryApp = steps.firstOrNull { it.pkg != null }?.pkg ?: "unknown"
        for ((index, step) in steps.withIndex()) {
            if (_state.value == TaskState.Cancelled) return

            // ── AppPermissionManager — user rules override PolicyEngine ──
            val userDecision = permissionManager.evaluate(
                action = step,
                appPackage = primaryApp
            )
            when (userDecision) {
                is PermissionDecision.Deny -> {
                    auditLogger.logUserDenied(taskId, step, RiskLevel.HIGH)
                    fail(taskId, goal, userDecision.reason)
                    return
                }
                is PermissionDecision.Allow -> {
                    // User has explicitly allowed — skip PolicyEngine dialog
                    _state.value = TaskState.Executing(step, index, steps.size)
                    voiceManager.narrateStep(step)
                    auditLogger.logAutoExecuted(taskId, step, RiskLevel.LOW)
                    actionExecutor.execute(step)
                    continue
                }
                else -> { /* Default or Confirm — fall through to PolicyEngine */ }
            }

            // ── PolicyEngine — global risk classification ─────────────
            val risk = policyEngine.classify(step)

            // Force confirm if user rule says ALWAYS_CONFIRM (override LOW risk)
            val effectiveRisk = if (userDecision is PermissionDecision.Confirm &&
                (risk == RiskLevel.LOW || risk == RiskLevel.MEDIUM)) {
                RiskLevel.HIGH
            } else risk

            when (effectiveRisk) {
                RiskLevel.LOW, RiskLevel.MEDIUM -> {
                    _state.value = TaskState.Executing(step, index, steps.size)
                    voiceManager.narrateStep(step)
                    auditLogger.logAutoExecuted(taskId, step, risk)
                    actionExecutor.execute(step)
                }
                RiskLevel.HIGH, RiskLevel.CRITICAL -> {
                    // Phase 7: anomaly check before asking user
                    val anomaly = anomalyDetector.check(step)
                    val anomalyWarning = if (anomaly is AnomalyResult.Suspicious)
                        anomaly.warningMessage else null

                    voiceManager.announceConfirmation(step, risk)
                    val approved = suspendForApproval(step, risk, anomalyWarning)
                    if (!approved) {
                        auditLogger.logUserDenied(taskId, step, risk)
                        fail(taskId, goal, "User denied action: ${step.action}")
                        return
                    }
                    auditLogger.logUserApproved(taskId, step, risk)
                    _state.value = TaskState.Executing(step, index, steps.size)
                    voiceManager.narrateStep(step)
                    actionExecutor.execute(step)
                }
            }
        }

        _state.value = TaskState.Verifying
        _state.value = TaskState.Completed("Done!")
        voiceManager.announceCompletion("Task completed successfully")

        // Remember this successful task in long-term memory
        scope.launch {
            memoryManager.remember(
                goal = goal,
                outcome = "success",
                appUsed = primaryApp
            )
        }

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
    private suspend fun suspendForApproval(
        step: ActionStep,
        risk: RiskLevel,
        anomalyWarning: String? = null
    ): Boolean {
        var result = false
        val latch = java.util.concurrent.CountDownLatch(1)

        _state.value = TaskState.WaitingForUser(
            riskAction = step,
            riskLevel = risk,
            anomalyWarning = anomalyWarning,
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

    private fun fail(taskId: String, goal: String = "", reason: String) {
        _state.value = TaskState.Failed(reason)
        voiceManager.announceFailure(reason)
        scope.launch {
            if (goal.isNotBlank()) {
                memoryManager.remember(goal, "failure", "unknown")
            }
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
