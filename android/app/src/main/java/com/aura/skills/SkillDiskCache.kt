package com.aura.skills

import android.content.Context
import android.util.Log
import com.aura.data.model.SkillPackEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SkillDiskCache"

/**
 * SkillDiskCache — L2 disk cache layer for the 3-layer skill lookup.
 *
 * Sits between L1 RAM (SkillCache) and L3 Room DB (SkillPackDao).
 *
 * Lookup chain:
 *   L1: RAM  (<1 ms)   — top 20 most-used, LRU evicted
 *   L2: Disk (1–5 ms)  — all skills promoted from DB, serialized as JSON files
 *   L3: Room (5–15 ms) — source of truth; hit here → write-through to L2
 *
 * Storage layout:
 *   <filesDir>/skill_disk_cache/
 *       <skillId>.json         — one file per skill (SkillPackEntity serialized)
 *       _keyword_index.json    — keyword → [skillId, ...] inverted index
 *
 * The keyword index makes lookup O(1) without scanning every file.
 * The index is rebuilt from disk on first access and kept in memory.
 */
@Singleton
class SkillDiskCache @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    private val cacheDir: File by lazy {
        File(context.filesDir, "skill_disk_cache").also { it.mkdirs() }
    }
    private val indexFile: File by lazy {
        File(cacheDir, "_keyword_index.json")
    }

    // In-memory keyword index: keyword (lowercase) → set of skillIds
    // Populated lazily on first read and kept in sync on every write.
    private val keywordIndex = mutableMapOf<String, MutableSet<String>>()
    private var indexLoaded = false

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Write a skill to disk. Overwrites any existing entry with the same skillId.
     * Also updates the in-memory keyword index and persists the index file.
     */
    suspend fun put(skill: SkillPackEntity) = withContext(Dispatchers.IO) {
        try {
            val file = skillFile(skill.skillId)
            file.writeText(json.encodeToString(skill))
            addToIndex(skill)
            persistIndex()
            Log.d(TAG, "Wrote to disk: ${skill.skillId}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write skill ${skill.skillId} to disk: ${e.message}")
        }
    }

    /**
     * Find all skills on disk matching any of the given keywords.
     * Uses the in-memory keyword index — no full-directory scan needed.
     *
     * @return List of non-stale matching skills, sorted by usageCount descending.
     */
    suspend fun findByKeywords(keywords: List<String>): List<SkillPackEntity> =
        withContext(Dispatchers.IO) {
            ensureIndexLoaded()
            val matchingIds = keywords
                .flatMap { kw -> keywordIndex[kw.lowercase()] ?: emptySet() }
                .toSet()

            matchingIds.mapNotNull { readSkill(it) }
                .filter { !it.isStale }
                .sortedByDescending { it.usageCount }
        }

    /**
     * Read a specific skill from disk by its canonical ID.
     * Returns null if the file doesn't exist or is corrupt.
     */
    suspend fun get(skillId: String): SkillPackEntity? = withContext(Dispatchers.IO) {
        readSkill(skillId)
    }

    /**
     * Remove a skill from disk and the index.
     * Called when a skill is marked stale and evicted from all layers.
     */
    suspend fun remove(skillId: String) = withContext(Dispatchers.IO) {
        try {
            skillFile(skillId).delete()
            removeFromIndex(skillId)
            persistIndex()
            Log.d(TAG, "Removed from disk: $skillId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove skill $skillId from disk: ${e.message}")
        }
    }

    /**
     * Update a skill on disk in-place (e.g. after usageCount / successRate change).
     * No-op if the skill isn't already on disk.
     */
    suspend fun update(skill: SkillPackEntity) = withContext(Dispatchers.IO) {
        if (skillFile(skill.skillId).exists()) {
            put(skill)
        }
    }

    /**
     * Returns all skillIds currently stored on disk.
     */
    suspend fun allIds(): Set<String> = withContext(Dispatchers.IO) {
        cacheDir.listFiles { f -> f.name.endsWith(".json") && f.name != "_keyword_index.json" }
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            ?: emptySet()
    }

    /**
     * Returns how many skills are stored on disk.
     */
    suspend fun size(): Int = withContext(Dispatchers.IO) {
        cacheDir.listFiles { f -> f.name.endsWith(".json") && f.name != "_keyword_index.json" }
            ?.size ?: 0
    }

    /**
     * Wipe the entire disk cache (used in tests or on full reset).
     */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()
        synchronized(keywordIndex) { keywordIndex.clear() }
        indexLoaded = false
        Log.i(TAG, "Disk cache cleared")
    }

    // ─────────────────────────────────────────────────────────────
    // Index management
    // ─────────────────────────────────────────────────────────────

    /**
     * Load the keyword index from disk into memory.
     * Called once lazily before the first read operation.
     */
    private fun ensureIndexLoaded() {
        if (indexLoaded) return
        synchronized(keywordIndex) {
            if (indexLoaded) return
            try {
                if (indexFile.exists()) {
                    val raw = indexFile.readText()
                    val parsed: Map<String, List<String>> = json.decodeFromString(raw)
                    parsed.forEach { (kw, ids) ->
                        keywordIndex[kw] = ids.toMutableSet()
                    }
                    Log.d(TAG, "Index loaded: ${keywordIndex.size} keywords")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load keyword index — rebuilding: ${e.message}")
                rebuildIndex()
            }
            indexLoaded = true
        }
    }

    /**
     * Add a skill's keywords to the in-memory index.
     */
    private fun addToIndex(skill: SkillPackEntity) {
        val keywords = parseKeywords(skill.triggerKeywords) +
                listOf(skill.appPackage.substringAfterLast('.').lowercase(),
                       skill.intentCategory.lowercase(),
                       skill.intentAction.lowercase())
        synchronized(keywordIndex) {
            keywords.filter { it.length > 2 }.forEach { kw ->
                keywordIndex.getOrPut(kw) { mutableSetOf() }.add(skill.skillId)
            }
        }
    }

    /**
     * Remove a skill from the in-memory index across all keywords.
     */
    private fun removeFromIndex(skillId: String) {
        synchronized(keywordIndex) {
            keywordIndex.values.forEach { ids -> ids.remove(skillId) }
            keywordIndex.entries.removeAll { it.value.isEmpty() }
        }
    }

    /**
     * Persist the in-memory keyword index to disk as JSON.
     */
    private fun persistIndex() {
        try {
            val snapshot: Map<String, List<String>> = synchronized(keywordIndex) {
                keywordIndex.mapValues { it.value.toList() }
            }
            indexFile.writeText(json.encodeToString(snapshot))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist keyword index: ${e.message}")
        }
    }

    /**
     * Rebuild the keyword index from scratch by reading every skill file.
     * Fallback used when the index file is corrupt or missing.
     */
    private fun rebuildIndex() {
        Log.i(TAG, "Rebuilding keyword index from disk...")
        keywordIndex.clear()
        val files = cacheDir.listFiles { f ->
            f.name.endsWith(".json") && f.name != "_keyword_index.json"
        } ?: return

        var rebuilt = 0
        files.forEach { file ->
            try {
                val skill: SkillPackEntity = json.decodeFromString(file.readText())
                addToIndex(skill)
                rebuilt++
            } catch (e: Exception) {
                Log.w(TAG, "Skipped corrupt cache file: ${file.name}")
            }
        }
        Log.i(TAG, "Rebuilt index: $rebuilt skills, ${keywordIndex.size} keywords")
        persistIndex()
    }

    // ─────────────────────────────────────────────────────────────
    // File helpers
    // ─────────────────────────────────────────────────────────────

    private fun skillFile(skillId: String): File =
        File(cacheDir, "${skillId.replace("/", "_")}.json")

    private fun readSkill(skillId: String): SkillPackEntity? {
        val file = skillFile(skillId)
        if (!file.exists()) return null
        return try {
            json.decodeFromString<SkillPackEntity>(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "Corrupt cache file for $skillId — deleting: ${e.message}")
            file.delete()
            null
        }
    }

    private fun parseKeywords(json: String): List<String> =
        try {
            json.removePrefix("[").removeSuffix("]")
                .split(",")
                .map { it.trim().removeSurrounding("\"").lowercase() }
                .filter { it.isNotBlank() }
        } catch (_: Exception) { emptyList() }
}
