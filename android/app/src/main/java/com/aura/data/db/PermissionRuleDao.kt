package com.aura.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aura.data.model.AppPermission
import com.aura.data.model.BlockedApp
import com.aura.data.model.PermissionRule
import kotlinx.coroutines.flow.Flow

@Dao
interface PermissionRuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rule: PermissionRule)

    @Query("DELETE FROM permission_rules WHERE ruleId = :ruleId")
    suspend fun delete(ruleId: String)

    /** All rules ordered by priority desc (highest first). */
    @Query("SELECT * FROM permission_rules ORDER BY priority DESC, createdAt DESC")
    fun getAll(): Flow<List<PermissionRule>>

    /**
     * Find rules matching a specific app + action (or wildcards).
     * Returns rules sorted by priority DESC so the most specific wins.
     */
    @Query("""
        SELECT * FROM permission_rules
        WHERE (appPackage = :appPackage OR appPackage IS NULL)
          AND (intentAction = :intentAction OR intentAction IS NULL)
        ORDER BY priority DESC
    """)
    suspend fun findRules(appPackage: String, intentAction: String): List<PermissionRule>

    /** Rules for a specific app — used by Permissions UI. */
    @Query("SELECT * FROM permission_rules WHERE appPackage = :appPackage ORDER BY priority DESC")
    fun getRulesForApp(appPackage: String): Flow<List<PermissionRule>>

    @Query("SELECT COUNT(*) FROM permission_rules")
    suspend fun count(): Int
}

@Dao
interface BlockedAppDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun block(app: BlockedApp)

    @Query("DELETE FROM blocked_apps WHERE appPackage = :appPackage")
    suspend fun unblock(appPackage: String)

    @Query("SELECT COUNT(*) > 0 FROM blocked_apps WHERE appPackage = :appPackage")
    suspend fun isBlocked(appPackage: String): Boolean

    @Query("SELECT * FROM blocked_apps ORDER BY blockedAt DESC")
    fun getAll(): Flow<List<BlockedApp>>
}
