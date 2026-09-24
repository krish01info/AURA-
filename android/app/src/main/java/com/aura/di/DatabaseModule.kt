package com.aura.di

import android.content.Context
import androidx.room.Room
import com.aura.data.db.AURADatabase
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
}
