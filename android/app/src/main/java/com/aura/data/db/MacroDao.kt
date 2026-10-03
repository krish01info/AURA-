package com.aura.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aura.data.model.MacroEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MacroDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(macro: MacroEntity)

    @Query("SELECT * FROM macros ORDER BY usageCount DESC")
    fun getAll(): Flow<List<MacroEntity>>

    @Query("SELECT * FROM macros ORDER BY usageCount DESC")
    suspend fun getAllList(): List<MacroEntity>

    @Query("SELECT * FROM macros WHERE macroId = :id")
    suspend fun getById(id: String): MacroEntity?

    @Query("SELECT * FROM macros WHERE triggerPhrase LIKE '%' || :keyword || '%'")
    suspend fun findByTrigger(keyword: String): List<MacroEntity>

    @Query("SELECT * FROM macros WHERE isScheduled = 1")
    suspend fun getScheduled(): List<MacroEntity>

    @Query("UPDATE macros SET usageCount = usageCount + 1 WHERE macroId = :id")
    suspend fun incrementUsage(id: String)

    @Query("DELETE FROM macros WHERE macroId = :id")
    suspend fun delete(id: String)
}
