package com.aura.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * MacroEntity — a saved multi-step recipe triggered by a single command.
 *
 * Macros are user-defined workflows that chain multiple tasks.
 * Example: "Morning Routine" → check WhatsApp, read top Gmail, check calendar.
 *
 * Phase 8 differentiator — no other mobile AI agent supports this.
 */
@Entity(tableName = "macros")
data class MacroEntity(
    @PrimaryKey val macroId: String = UUID.randomUUID().toString(),
    val name: String,                        // "Morning Routine"
    val description: String,                 // "Checks messages, email, weather"
    val emoji: String = "⚡",
    val triggerPhrase: String,               // "run morning routine"
    val goalSequence: String,                // JSON: ["check whatsapp", "open gmail", ...]
    val isScheduled: Boolean = false,
    val scheduleTime: String = "",           // "07:30" (HH:mm format)
    val scheduleDays: String = "",           // JSON: ["MON","TUE","WED","THU","FRI"]
    val usageCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
