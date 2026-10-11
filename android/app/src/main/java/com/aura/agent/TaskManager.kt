package com.aura.agent

import android.util.Log
import com.aura.data.db.TaskLogDao
import com.aura.data.model.ActionStep
import com.aura.data.model.TaskLogEntity
import com.aura.data.model.TaskStatus
import com.aura.llm.ApiKeyStore
import com.aura.llm.GeminiClient
import com.aura.llm.GroqClient
import com.aura.llm.LlmException
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
import com.aura.skills.SkillUpdater
import com.aura.voice.VoiceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TaskManager"

/**
 * TaskManager — the central orchestrator of the AURA agent.
 *
 * Responsibilities:
 *  - Accepts goals (typed, voice, assistant popup, macros)
 *  - Checks [SkillRouter] for a stored plan before calling an LLM
 *  - Plans with [GroqClient]; falls back to [GeminiClient] if Groq fails or has no key
 *  - Runs steps one by one and STOPS at the first failed step, with a spoken reason
 *  - Gates HIGH / CRITICAL risk steps — waits for user approval
 *  - Saves a new skill only after its plan ran successfully
 *  - Reports stored-skill outcomes to [SkillUpdater]; a failing stored skill is
 *    re-planned once with the LLM (self-healing)
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
    private val geminiClient: GeminiClient,
    private val apiKeyStore: ApiKeyStore,
    private val actionExecutor: ActionExecutor,
    private val policyEngine: PolicyEngine,
    private val skillRouter: SkillRouter,
    private val skillLearner: SkillLearner,
    private val skillUpdater: SkillUpdater,
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
    private val json = Json { ignoreUnknownKeys = true }

    /** Observable state — the UI and the assistant popup collect this. */
    private val _state = MutableStateFlow<TaskState>(TaskState.Created)
    val state: StateFlow<TaskState> = _state.asStateFlow()

    /** The current task's DB ID (used for audit logging). */
    private var currentTaskId: String? = null

    /** True while a task is planning, running or waiting for approval. */
    val isBusy: Boolean
        get() = when (_state.value) {
            is TaskState.Planning, is TaskState.Executing,
            is TaskState.WaitingForUser, is TaskState.Verifying -> true
            else -> false
        }

    /** Outcome of running a list of steps. */
    private sealed class RunOutcome {
        data object Success : RunOutcome()
        data class StepFailed(val reason: String) : RunOutcome()
        data object Stopped : RunOutcome() // denied, cancelled or already failed
    }

    // ──────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────

    /**
     * Start executing a natural-language goal. Cancels any running task first.
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

        // Fail fast with a clear reason instead of a vague error later.
        setupProblem()?.let { problem ->
            _state.value = TaskState.Failed(problem)
            voiceManager.speak(problem)
            Log.w(TAG, "Not starting task: $problem")
            return
        }

        stopCurrentTask()
        val taskId = UUID.randomUUID().toString()
        currentTaskId = taskId
        currentJob = scope.launch { runTask(taskId, sanitized) }
    }

    /**
     * Execute a pre-built list of [ActionStep]s directly — NO LLM call.
     * Used for the built-in test buttons and macro replay.
     */
    fun executeDirectSteps(label: String, steps: List<ActionStep>) {
        if (!actionExecutor.isBound) {
            val problem = ACCESSIBILITY_OFF
            _state.value = TaskState.Failed(problem)
            voiceManager.speak(problem)
            return
        }
        stopCurrentTask()
        val taskId = UUID.randomUUID().toString()
        currentTaskId = taskId
        currentJob = scope.launch { runSteps(taskId, label, steps) }
    }

    /** Emergency stop — halts all execution instantly. */
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
     * Soft pause — called from [AURAAccessibilityService.onInterrupt].
     * Currently cancels the task but keeps the last state visible.
     */
    fun pause() {
        Log.w(TAG, "⏸ Task paused (system interrupt)")
        stopCurrentTask()
    }

    /** Reset state to Created (ready for next command). */
    fun reset() {
        _state.value = TaskState.Created
        currentTaskId = null
    }

    /**
     * Returns a user-readable reason why tasks can't run right now, or null if
     * everything needed is set up.
     */
    fun setupProblem(): String? = when {
        !actionExecutor.isBound -> ACCESSIBILITY_OFF
        !apiKeyStore.hasAnyKey() -> "I need an AI key first. Add a Groq or Gemini API key in AURA's Settings."
        else -> null
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

            // 1. Stored skill (L1 RAM → L2 disk → L3 Room) — no LLM call needed
            val cachedSkill = skillRouter.findBestSkill(goal)
            val cachedSteps = cachedSkill?.let { skill ->
                try {
                    json.decodeFromString(ListSerializer(ActionStep.serializer()), skill.steps)
                } catch (_: Exception) { emptyList() }
            }?.takeIf { it.isNotEmpty() }

            if (cachedSkill != null && cachedSteps != null) {
                Log.i(TAG, "Using stored skill ${cachedSkill.skillId} (${cachedSteps.size} steps)")
                when (val outcome = runStepsInternal(taskId, goal, cachedSteps)) {
                    RunOutcome.Success -> {
                        scope.launch { skillUpdater.recordSuccess(cachedSkill.skillId) }
                        return
                    }
                    RunOutcome.Stopped -> return
                    is RunOutcome.StepFailed -> {
                        // Self-heal: record the failure, then re-plan with the LLM once.
                        Log.w(TAG, "Stored skill failed (${outcome.reason}) — re-planning with LLM")
                        scope.launch { skillUpdater.handleFailure(cachedSkill.skillId, goal) }
                        voiceManager.speak("That didn't work. Let me try another way.", flush = false)
                        _state.value = TaskState.Planning
                    }
                }
            }

            // 2. LLM plan (Groq → Gemini)
            val steps = planWithLlm(goal)

            val clarify = steps.firstOrNull { it.action == ActionStep.CLARIFY }
            if (clarify != null) {
                fail(taskId, goal, clarify.clarification ?: "Can you say that more specifically?")
                return
            }

            Log.i(TAG, "Plan ready — ${steps.size} steps: ${steps.map { it.action }}")
            when (val outcome = runStepsInternal(taskId, goal, steps)) {
                RunOutcome.Success -> {
                    // Only now is the plan known to work — save it as a skill.
                    val appPkg = steps.firstOrNull { it.pkg != null }?.pkg ?: "unknown"
                    scope.launch { skillLearner.learnFromSuccess(goal, steps, appPkg) }
                }
                is RunOutcome.StepFailed -> fail(taskId, goal, outcome.reason)
                RunOutcome.Stopped -> { /* state already set */ }
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            fail(taskId, goal, e.message ?: "I couldn't plan that.")
        } catch (e: Exception) {
            Log.e(TAG, "Task [$taskId] failed: ${e.message}", e)
            fail(taskId, goal, "Something went wrong: ${e.message ?: "unknown error"}")
        }
    }

    /**
     * Plan with Groq; if it fails (or has no key) and a Gemini key exists, use Gemini.
     * @throws LlmException with the most useful user-readable reason.
     */
    private suspend fun planWithLlm(goal: String): List<ActionStep> {
        val memoryContext = memoryManager.buildContextString(goal)
        val uiSnapshot = actionExecutor.getUiSnapshot()

        var groqError: LlmException? = null
        if (apiKeyStore.groqKey().isNotBlank()) {
            try {
                return groqClient.plan(goal, uiSnapshot, memoryContext = memoryContext)
            } catch (e: LlmException) {
                Log.w(TAG, "Groq failed: ${e.message}")
                groqError = e
            }
        }
        if (geminiClient.isConfigured()) {
            try {
                Log.i(TAG, "Falling back to Gemini")
                return geminiClient.plan(goal, uiSnapshot, memoryContext)
            } catch (e: LlmException) {
                Log.w(TAG, "Gemini failed: ${e.message}")
                throw groqError ?: e
            }
        }
        throw groqError ?: LlmException("No AI key set. Add a Groq or Gemini key in Settings.")
    }

    // ──────────────────────────────────────────────────────────────
    // Direct step execution (no LLM)
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
            val outcome = runStepsInternal(taskId, label, steps)
            if (outcome is RunOutcome.StepFailed) fail(taskId, label, outcome.reason)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Direct task [$taskId] failed: ${e.message}", e)
            fail(taskId, label, e.message ?: "Unknown error")
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Shared step execution loop
    // ──────────────────────────────────────────────────────────────

    /**
     * Run [steps] in order. Returns [RunOutcome.StepFailed] at the first step that
     * didn't work (the caller decides whether to re-plan or fail), or
     * [RunOutcome.Stopped] if the user denied / cancelled (state already set).
     * On success it sets Completed, announces it and records memory + log.
     */
    private suspend fun runStepsInternal(
        taskId: String,
        goal: String,
        steps: List<ActionStep>
    ): RunOutcome {
        val primaryApp = steps.firstOrNull { it.pkg != null }?.pkg ?: "unknown"

        for ((index, step) in steps.withIndex()) {
            if (_state.value == TaskState.Cancelled) return RunOutcome.Stopped

            // ── AppPermissionManager — user rules override PolicyEngine ──
            val userDecision = permissionManager.evaluate(action = step, appPackage = primaryApp)
            if (userDecision is PermissionDecision.Deny) {
                auditLogger.logUserDenied(taskId, step, RiskLevel.HIGH)
                fail(taskId, goal, userDecision.reason)
                return RunOutcome.Stopped
            }

            val effectiveRisk = if (userDecision is PermissionDecision.Allow) {
                RiskLevel.LOW
            } else {
                val risk = policyEngine.classify(step)
                // ALWAYS_CONFIRM rule upgrades LOW/MEDIUM to a confirmation
                if (userDecision is PermissionDecision.Confirm &&
                    (risk == RiskLevel.LOW || risk == RiskLevel.MEDIUM)) RiskLevel.HIGH else risk
            }

            if (effectiveRisk == RiskLevel.HIGH || effectiveRisk == RiskLevel.CRITICAL) {
                val anomaly = anomalyDetector.check(step)
                val anomalyWarning = (anomaly as? AnomalyResult.Suspicious)?.warningMessage

                voiceManager.announceConfirmation(step, effectiveRisk)
                val approved = suspendForApproval(step, effectiveRisk, anomalyWarning)
                if (!approved) {
                    auditLogger.logUserDenied(taskId, step, effectiveRisk)
                    fail(taskId, goal, "Okay, I won't do that.")
                    return RunOutcome.Stopped
                }
                auditLogger.logUserApproved(taskId, step, effectiveRisk)
            } else {
                auditLogger.logAutoExecuted(taskId, step, effectiveRisk)
            }

            _state.value = TaskState.Executing(step, index, steps.size)
            voiceManager.narrateStep(step)
            val result = actionExecutor.execute(step)
            if (!result.ok) {
                return RunOutcome.StepFailed(result.error ?: "A step didn't work.")
            }
            if (step.action == ActionStep.READ_TEXT && !result.output.isNullOrBlank()) {
                voiceManager.speak(result.output.take(400), flush = false)
            }
        }

        _state.value = TaskState.Verifying
        _state.value = TaskState.Completed("Done!")
        voiceManager.announceCompletion("Done")

        scope.launch {
            memoryManager.remember(goal = goal, outcome = "success", appUsed = primaryApp)
        }
        taskLogDao.updateStatus(
            taskId = taskId,
            status = TaskStatus.COMPLETED,
            finishedAt = System.currentTimeMillis(),
            error = null
        )
        Log.i(TAG, "✅ Task [$taskId] completed")
        return RunOutcome.Success
    }

    // ──────────────────────────────────────────────────────────────
    // Approval gate
    // ──────────────────────────────────────────────────────────────

    /**
     * Suspends execution and transitions to [TaskState.WaitingForUser].
     * Returns true if the user approved. Cancellable (emergency stop).
     */
    private suspend fun suspendForApproval(
        step: ActionStep,
        risk: RiskLevel,
        anomalyWarning: String? = null
    ): Boolean {
        val decision = CompletableDeferred<Boolean>()
        _state.value = TaskState.WaitingForUser(
            riskAction = step,
            riskLevel = risk,
            anomalyWarning = anomalyWarning,
            onAllow = { decision.complete(true) },
            onDeny = { decision.complete(false) }
        )
        return decision.await()
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    private fun fail(taskId: String, goal: String = "", reason: String) {
        _state.value = TaskState.Failed(reason)
        voiceManager.speak(reason)
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

    private companion object {
        const val ACCESSIBILITY_OFF =
            "Please turn on AURA in your phone's Accessibility settings first, so I can control apps."
    }
}
