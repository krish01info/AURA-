package com.aura.di

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Room
import com.aura.data.db.AURADatabase
import com.aura.data.db.AuditLogDao
import com.aura.data.db.BlockedAppDao
import com.aura.data.db.MacroDao
import com.aura.data.db.MemoryDao
import com.aura.data.db.PermissionRuleDao
import com.aura.data.db.SkillPackDao
import com.aura.data.db.TaskLogDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module providing all database-related dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAURADatabase(@ApplicationContext context: Context): AURADatabase {
        return Room.databaseBuilder(
            context,
            AURADatabase::class.java,
            "aura_database"
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideTaskLogDao(database: AURADatabase): TaskLogDao {
        return database.taskLogDao()
    }

    @Provides
    @Singleton
    fun provideAuditLogDao(database: AURADatabase): AuditLogDao {
        return database.auditLogDao()
    }

    @Provides
    @Singleton
    fun provideMemoryDao(database: AURADatabase): MemoryDao {
        return database.memoryDao()
    }

    @Provides
    @Singleton
    fun provideSkillPackDao(database: AURADatabase): SkillPackDao {
        return database.skillPackDao()
    }

    @Provides
    @Singleton
    fun provideMacroDao(database: AURADatabase): MacroDao {
        return database.macroDao()
    }

    @Provides
    @Singleton
    fun providePermissionRuleDao(database: AURADatabase): PermissionRuleDao {
        return database.permissionRuleDao()
    }

    @Provides
    @Singleton
    fun provideBlockedAppDao(database: AURADatabase): BlockedAppDao {
        return database.blockedAppDao()
    }

    @Provides
    @Singleton
    fun provideSharedPreferences(@ApplicationContext context: Context): SharedPreferences {
        return context.getSharedPreferences("aura_prefs", Context.MODE_PRIVATE)
    }
}
