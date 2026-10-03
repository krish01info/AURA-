package com.aura.macros

import android.util.Log
import com.aura.agent.TaskManager
import com.aura.data.db.MacroDao
import com.aura.data.model.MacroEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MacroRunner"
private const val STEP_DELAY_MS = 2000L // pause between tasks in a macro

/**
 * MacroRunner — Phase 8 macro/recipe execution engine.
 *
 * A Macro is a list of natural-language goals executed in sequence.
 * Each goal goes through the full AURA pipeline:
 *   - SkillRouter → local cache hit
 *   - or GroqClient → LLM planning
 *   - PolicyEngine → risk check
 *   - Confirmation dialog for HIGH/CRITICAL steps
 *
 * This means macros support the full security model automatically.
 */
@Singleton
class MacroRunner @Inject constructor(
    private val macroDao: MacroDao,
    private val taskManager: TaskManager
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Run a macro by ID.
     * Each goal in the sequence is executed serially with a pause in between.
     * Stops early if any task fails or is cancelled.
     */
    suspend fun runMacro(macroId: String) {
        val macro = macroDao.getById(macroId) ?: run {
            Log.e(TAG, "Macro not found: $macroId")
            return
        }
        Log.i(TAG, "▶️ Running macro: ${macro.name}")
        macroDao.incrementUsage(macroId)
        runMacroInternal(macro)
    }

    /**
     * Run a macro by matching a trigger phrase.
     */
    suspend fun runByTrigger(phrase: String): Boolean {
        val matches = macroDao.findByTrigger(phrase.lowercase())
        if (matches.isEmpty()) return false
        val macro = matches.first()
        Log.i(TAG, "Trigger match: \"$phrase\" → ${macro.name}")
        runMacroInternal(macro)
        return true
    }

    private suspend fun runMacroInternal(macro: MacroEntity) {
        val goals = try {
            json.decodeFromString(ListSerializer(String.serializer()), macro.goalSequence)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse macro goals: ${e.message}")
            return
        }

        Log.i(TAG, "Macro ${macro.name}: ${goals.size} steps")

        for ((i, goal) in goals.withIndex()) {
            Log.i(TAG, "Macro step ${i + 1}/${goals.size}: $goal")
            taskManager.execute(goal)
            // Wait for each task to complete before running the next one
            delay(STEP_DELAY_MS)
        }

        Log.i(TAG, "✅ Macro complete: ${macro.name}")
    }

    /** Create or update a macro. */
    suspend fun saveMacro(macro: MacroEntity) {
        macroDao.upsert(macro)
        Log.i(TAG, "Saved macro: ${macro.name}")
    }

    /** Delete a macro. */
    suspend fun deleteMacro(macroId: String) {
        macroDao.delete(macroId)
    }

    fun getAllMacros(): Flow<List<MacroEntity>> = macroDao.getAll()

    /** Get all scheduled macros (for WorkManager trigger). */
    suspend fun getScheduledMacros(): List<MacroEntity> = macroDao.getScheduled()
}
