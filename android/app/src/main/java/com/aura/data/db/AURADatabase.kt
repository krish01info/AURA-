package com.aura.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aura.data.model.TaskLogEntity

/**
 * AURA's Room database.
 *
 * Contains:
 * - [TaskLogEntity] — audit log of every task run
 *
 * Future tables (Phase 5-6):
 * - SkillPackEntity  — local skill cache
 * - MemoryEntry      — long-term memory embeddings
 */
@Database(
    entities = [TaskLogEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AURADatabase : RoomDatabase() {
    abstract fun taskLogDao(): TaskLogDao
}
