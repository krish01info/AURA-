package com.aura.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
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
    version = 6,
    exportSchema = true
)
abstract class AURADatabase : RoomDatabase() {
    abstract fun taskLogDao(): TaskLogDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun memoryDao(): MemoryDao
    abstract fun skillPackDao(): SkillPackDao
    abstract fun macroDao(): MacroDao
    abstract fun permissionRuleDao(): PermissionRuleDao
    abstract fun blockedAppDao(): BlockedAppDao
}
