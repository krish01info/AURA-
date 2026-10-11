package com.aura.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aura.data.model.AuditLogEntity
import com.aura.data.model.BlockedApp
import com.aura.data.model.MacroEntity
import com.aura.data.model.MemoryEntry
import com.aura.data.model.PermissionRule
import com.aura.data.model.SkillPackEntity
import com.aura.data.model.TaskLogEntity

@Database(
    entities = [
        TaskLogEntity::class,
        AuditLogEntity::class,
        MemoryEntry::class,
        SkillPackEntity::class,
        MacroEntity::class,
        PermissionRule::class,
        BlockedApp::class
    ],
    version = 7,
    exportSchema = true,
    autoMigrations = [
        // 6 → 7: skill variants (intentId, phrasePatterns, appMajorVersion). Other tables keep their data.
        AutoMigration(from = 6, to = 7, spec = AURADatabase.SkillVariants6To7::class)
    ]
)
abstract class AURADatabase : RoomDatabase() {

    /**
     * Old (v6) skill rows have no phrase patterns and replay literal values
     * (e.g. re-send an old message to an old contact), so they're unsafe to run.
     * Clear them; built-in seeds are re-inserted at startup and real skills are
     * re-learned (or downloaded) the first time each task is done.
     */
    class SkillVariants6To7 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("DELETE FROM skill_packs")
        }
    }

    abstract fun taskLogDao(): TaskLogDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun memoryDao(): MemoryDao
    abstract fun skillPackDao(): SkillPackDao
    abstract fun macroDao(): MacroDao
    abstract fun permissionRuleDao(): PermissionRuleDao
    abstract fun blockedAppDao(): BlockedAppDao
}
