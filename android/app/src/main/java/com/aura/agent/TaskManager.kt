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
import com.aura.llm.Plan
import com.aura.llm.PlannerPrompt
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
import com.aura.skills.SkillMatcher
import com.aura.skills.SkillUpdater
import com.aura.voice.VoiceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
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
    private val permissionManager: AppPermissionManager,
    private val contactResolver: ContactResolver
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val patternJson = Json { ignoreUnknownKeys = true }
    private var currentJob: Job? = null

    /** Observable state — the UI and the assistant popup collect this. */
    private val _state = MutableStateFlow<TaskState>(TaskState.Created)
    val state: StateFlow<TaskState> = _state.asStateFlow()

    /** The current task's DB ID (used for audit logging). */
    private var currentTaskId: String? = null

    /** True while a task is planning, running or waiting for approval. */
    val isBusy: Boolean
        get() = when (_state.value) {
            is TaskState.Planning, is TaskState.Executing, is TaskState.WaitingForUnlock,
            is TaskState.WaitingForUser, is TaskState.Verifying -> true
            else -> false
        }

    /**
     * The last task that finished with "Done", so the user can say it was wrong.
     * [storedSkillId] = stored skill that ran as-is; [learned] = skill being saved from this run.
     */
    private class LastRun(val goal: String, val storedSkillId: String?, val learned: Deferred<String?>?)
    @Volatile private var lastRun: LastRun? = null

    /** Outcome of running a list of steps. */
    private sealed class RunOutcome {
        data object Success : RunOutcome()
        /** Step [index] failed; steps before it worked. */
        data class StepFailed(val reason: String, val index: Int) : RunOutcome()
        data object Stopped : RunOutcome() // denied, cancelled or already failed
    }

    /** Result of a run including recoveries: [executed] = every step that worked, in order. */
    private sealed class FlowResult {
        data class Done(val executed: List<ActionStep>, val recovered: Boolean) : FlowResult()
        data class Failed(val reason: String) : FlowResult()
        data object Stopped : FlowResult()
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
        lastRun = null
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
        lastRun = null
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

    /**
     * The user says the last "Done" task did the wrong thing. The skill that produced it
     * is dropped (a skill learned on this phone is deleted; a built-in or downloaded one is
     * switched off), so the same mistake isn't repeated. Returns false if there's nothing to undo.
     */
    fun reportWrong(): Boolean {
        val run = lastRun ?: return false
        lastRun = null
        scope.launch {
            val learnedId = try { run.learned?.await() } catch (_: Exception) { null }
            listOfNotNull(learnedId, run.storedSkillId).distinct().forEach { id ->
                skillUpdater.markWrong(id, run.goal)
            }
            memoryManager.remember(run.goal, "failure", "unknown")
        }
        Log.w(TAG, "User reported a wrong result for \"${run.goal}\"")
        return true
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
        // No API key is fine here: stored skills run without one. The LLM step
        // explains the missing key only if a new task actually needs planning.
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

            // 1. Stored skill: RAM → disk → Room → cloud. No LLM call when found.
            val match = skillRouter.findSkill(goal)
            match?.question?.let { fail(taskId, goal, it); return }

            val plan: Plan
            val runParams: Map<String, String>
            val firstSteps: List<ActionStep>
            if (match != null) {
                Log.i(TAG, "Using stored skill ${match.skill.skillId} from ${match.layer} (${match.steps.size} steps)")
                plan = Plan(
                    steps = emptyList(),
                    app = match.skill.appPackage,
                    category = match.skill.intentCategory,
                    action = match.skill.intentAction,
                    params = match.spokenParams,
                    patterns = decodePatterns(match.skill.phrasePatterns)
                )
                runParams = match.params
                firstSteps = match.steps
            } else {
                // 2. LLM plan (Groq → Gemini) — only when no stored skill fits
                plan = planWithLlm(goal)
                val clarify = plan.steps.firstOrNull { it.action == ActionStep.CLARIFY }
                if (clarify != null) {
                    fail(taskId, goal, clarify.clarification ?: "Can you say that more specifically?")
                    return
                }
                val resolved = contactResolver.enrich(plan.params)
                resolved.question?.let { fail(taskId, goal, it); return }
                runParams = resolved.params
                val filled = SkillMatcher.fill(plan.steps, runParams)
                if (filled == null) {
                    fail(taskId, goal, "I couldn't plan that properly. Please try saying it another way.")
                    return
                }
                firstSteps = withVerify(filled)
                Log.i(TAG, "Plan ready — ${firstSteps.size} steps: ${firstSteps.map { it.action }}")
            }

            // 3. Run, recovering from failed steps by re-planning from the current screen.
            when (val result = runWithRecovery(taskId, goal, firstSteps, runParams)) {
                is FlowResult.Done -> {
                    if (match != null && !result.recovered) {
                        lastRun = LastRun(goal, match.skill.skillId, null)
                        scope.launch { skillUpdater.recordSuccess(match.skill.skillId) }
                    } else {
                        // New plan, or a stored skill that needed fixing: save the path that
                        // actually worked as this phone's variant (values → {slots}).
                        if (match != null) scope.launch { skillUpdater.handleFailure(match.skill.skillId, goal) }
                        val template = SkillMatcher.templatize(result.executed, runParams)
                        lastRun = LastRun(goal, null, scope.async { skillLearner.learn(goal, plan, template) })
                    }
                    finishSuccess(taskId, goal, result.executed)
                }
                is FlowResult.Failed -> {
                    if (match != null) scope.launch { skillUpdater.handleFailure(match.skill.skillId, goal) }
                    fail(taskId, goal, result.reason)
                }
                FlowResult.Stopped -> { /* state already set */ }
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
     * Run [steps]; when a step fails, ask the LLM for the remaining steps from the
     * screen the phone is on now (up to [MAX_RECOVERIES] times). Approvals still apply
     * to every recovered step. Needs an API key; without one the first failure is final.
     */
    private suspend fun runWithRecovery(
        taskId: String,
        goal: String,
        steps: List<ActionStep>,
        params: Map<String, String>
    ): FlowResult {
        val executed = mutableListOf<ActionStep>()
        var current = steps
        var recoveries = 0
        while (true) {
            when (val outcome = runStepsInternal(taskId, goal, current)) {
                RunOutcome.Success -> {
                    executed += current
                    return FlowResult.Done(executed, recovered = recoveries > 0)
                }
                RunOutcome.Stopped -> return FlowResult.Stopped
                is RunOutcome.StepFailed -> {
                    executed += current.take(outcome.index)
                    val failedStep = current[outcome.index]
                    if (recoveries >= MAX_RECOVERIES || !apiKeyStore.hasAnyKey()) {
                        return FlowResult.Failed(outcome.reason)
                    }
                    recoveries++
                    Log.w(TAG, "Step failed (${outcome.reason}) — recovery $recoveries/$MAX_RECOVERIES")
                    _state.value = TaskState.Planning
                    voiceManager.speak("That didn't work. Let me try another way.", flush = false)

                    val next = try {
                        continuePlan(goal, executed, failedStep, outcome.reason, params)
                    } catch (e: LlmException) {
                        Log.w(TAG, "Recovery planning failed: ${e.message}")
                        return FlowResult.Failed(outcome.reason)
                    }
                    if (next.isEmpty()) return FlowResult.Failed(outcome.reason)
                    if (next.any { it.action == ActionStep.CLARIFY }) {
                        return FlowResult.Failed(
                            next.first { it.action == ActionStep.CLARIFY }.clarification ?: outcome.reason
                        )
                    }
                    current = next
                }
            }
        }
    }

    /** Ask the LLM for the remaining steps after [failed], filled with [params]. */
    private suspend fun continuePlan(
        goal: String,
        done: List<ActionStep>,
        failed: ActionStep,
        error: String,
        params: Map<String, String>
    ): List<ActionStep> {
        val userContent = PlannerPrompt.continueMessage(
            goal, done, failed, error, params, actionExecutor.getUiSnapshot()
        )
        val plan = completeWithLlm(userContent)
        // The model may use {slots} or literal values — fill whatever slots it used.
        return SkillMatcher.fill(plan.steps, params + plan.params) ?: plan.steps.filter {
            SkillMatcher.slotsInSteps(listOf(it)).isEmpty()
        }
    }

    /**
     * If the plan opens one app but never verifies anything, add a check that the
     * app is in front at the end — so "Done" means something.
     */
    private fun withVerify(steps: List<ActionStep>): List<ActionStep> {
        if (steps.any { it.action == ActionStep.VERIFY }) return steps
        if (steps.any { it.action in NO_AUTO_VERIFY }) {
            return steps
        }
        val apps = steps.filter { it.action == ActionStep.OPEN_APP }.mapNotNull { it.pkg }.distinct()
        return if (apps.size == 1) steps + ActionStep(action = ActionStep.VERIFY, pkg = apps.first()) else steps
    }

    private fun decodePatterns(raw: String): List<String> = try {
        patternJson.decodeFromString(ListSerializer(String.serializer()), raw)
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Plan with Groq; if it fails (or has no key) and a Gemini key exists, use Gemini.
     * @throws LlmException with the most useful user-readable reason.
     */
    private suspend fun planWithLlm(goal: String): Plan {
        val memoryContext = memoryManager.buildContextString(goal)
        val uiSnapshot = actionExecutor.getUiSnapshot()
        return completeWithLlm(PlannerPrompt.userMessage(goal, uiSnapshot, memoryContext))
    }

    /** Send a planner request to Groq, falling back to Gemini. */
    private suspend fun completeWithLlm(userContent: String): Plan {
        var groqError: LlmException? = null
        if (apiKeyStore.groqKey().isNotBlank()) {
            try {
                return groqClient.complete(userContent)
            } catch (e: LlmException) {
                Log.w(TAG, "Groq failed: ${e.message}")
                groqError = e
            }
        }
        if (geminiClient.isConfigured()) {
            try {
                Log.i(TAG, "Falling back to Gemini")
                return geminiClient.complete(userContent)
            } catch (e: LlmException) {
                Log.w(TAG, "Gemini failed: ${e.message}")
                throw groqError ?: e
            }
        }
        throw groqError ?: LlmException(
            "I don't know how to do that yet, and I need an AI key to learn it. Add a Groq or Gemini key in Settings."
        )
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
            when (val outcome = runStepsInternal(taskId, label, steps)) {
                RunOutcome.Success -> {
                    lastRun = LastRun(label, null, null)
                    finishSuccess(taskId, label, steps)
                }
                is RunOutcome.StepFailed -> fail(taskId, label, outcome.reason)
                RunOutcome.Stopped -> {}
            }
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
     * didn't work (the caller decides whether to recover or fail), or
     * [RunOutcome.Stopped] if the user denied / cancelled (state already set).
     * Does NOT announce completion — see [finishSuccess].
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

            _state.value = if (step.action == ActionStep.VERIFY) TaskState.Verifying
                else TaskState.Executing(step, index, steps.size)
            if (step.action != ActionStep.VERIFY) voiceManager.narrateStep(step)
            var result = actionExecutor.execute(step)
            // The app (or phone) is asking for its lock: wait for the user, then try again.
            var unlockWaits = 0
            while (!result.ok && unlockWaits < MAX_UNLOCK_WAITS && actionExecutor.lockScreenInFront()) {
                unlockWaits++
                if (!waitForUnlock(taskId, goal, primaryApp)) return RunOutcome.Stopped
                _state.value = TaskState.Executing(step, index, steps.size)
                result = actionExecutor.execute(step)
            }
            if (!result.ok) {
                return RunOutcome.StepFailed(result.error ?: "A step didn't work.", index)
            }
            // read_text / now_playing / read_notifications return something to say
            if (!result.output.isNullOrBlank()) {
                voiceManager.speak(result.output.take(600), flush = false)
            }
        }
        return RunOutcome.Success
    }

    /**
     * Show "Unlock the app" and wait (up to [UNLOCK_TIMEOUT_MS]) until no lock screen is in
     * front. AURA never types PINs or passwords itself. Returns false (task failed) on timeout.
     */
    private suspend fun waitForUnlock(taskId: String, goal: String, primaryApp: String): Boolean {
        val appName = primaryApp.takeIf { it != "unknown" }?.substringAfterLast('.')
            ?.replaceFirstChar { it.uppercase() }
        Log.i(TAG, "Lock screen in front — waiting for the user to unlock")
        _state.value = TaskState.WaitingForUnlock(appName)
        voiceManager.speak("Please unlock ${appName ?: "the app"}. I'll continue after that.")
        val deadline = System.currentTimeMillis() + UNLOCK_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            delay(UNLOCK_POLL_MS)
            if (!actionExecutor.lockScreenInFront()) {
                delay(UNLOCK_SETTLE_MS) // the app's own screen is still appearing
                return true
            }
        }
        fail(taskId, goal, "${appName ?: "The app"} stayed locked, so I stopped. Unlock it and ask me again.")
        return false
    }

    /** Announce and record a finished task. */
    private suspend fun finishSuccess(taskId: String, goal: String, steps: List<ActionStep>) {
        val primaryApp = steps.firstOrNull { it.pkg != null }?.pkg ?: "unknown"
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
        /** How many times a task may re-plan from the current screen after a failed step. */
        const val MAX_RECOVERIES = 2
        /** How long AURA waits for the user to unlock an app before giving up. */
        const val UNLOCK_TIMEOUT_MS = 45_000L
        const val UNLOCK_POLL_MS = 700L
        const val UNLOCK_SETTLE_MS = 800L
        const val MAX_UNLOCK_WAITS = 2
        /** Plans with these finish outside any app screen — no automatic "app in front" check. */
        val NO_AUTO_VERIFY = setOf(
            ActionStep.DIAL, ActionStep.CALL, ActionStep.SMS, ActionStep.ALARM, ActionStep.TIMER, ActionStep.INTENT,
            ActionStep.MEDIA, ActionStep.NOW_PLAYING, ActionStep.REPLY, ActionStep.READ_NOTIFICATIONS
        )
        const val ACCESSIBILITY_OFF =
            "Please turn on AURA in your phone's Accessibility settings first, so I can control apps."
    }
}
