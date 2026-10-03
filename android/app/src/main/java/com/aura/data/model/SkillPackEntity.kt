package com.aura.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * SkillPackEntity — a cached, reusable action plan for a recurring goal.
 *
 * Stored in Room DB as the local skill cache (L2).
 * Every successful LLM-planned task is auto-saved as a skill.
 * Skills are also downloaded from Supabase cloud for community sharing.
 *
 * Lookup chain: L1 RAM cache → L2 Room DB → L3 Supabase cloud → Groq LLM
 *
 * OS Classification fields enable device-specific skill filtering —
 * users only download skills compatible with their Android version and OEM skin.
 */
@Entity(tableName = "skill_packs")
data class SkillPackEntity(
    @PrimaryKey val skillId: String,          // canonical hash — never changes

    // ── Identity ──────────────────────────────────────────────────
    val appPackage: String,                   // "com.whatsapp"
    val intentCategory: String = "unknown",   // "messaging" | "payment" | "food" ...
    val intentAction: String = "unknown",     // "send_message" | "send_money" ...
    val displayName: String = "",             // "WhatsApp Send Message"

    // ── Matching ──────────────────────────────────────────────────
    val triggerKeywords: String,              // JSON: ["whatsapp","message","text"]
    val parameters: String = "[]",           // JSON: param schema

    // ── Execution ─────────────────────────────────────────────────
    val steps: String,                        // JSON: List<ActionStep>
    val riskLevel: String = "MEDIUM",        // LOW | MEDIUM | HIGH | CRITICAL

    // ── OS Classification (SKILL-SYSTEM.md) ───────────────────────
    val minApiLevel: Int = 29,               // minimum Android API supported
    val maxApiLevel: Int? = null,            // null = supports all future versions
    val targetApiLevel: Int = 29,            // the OS this skill was tested on
    val osClassTag: String = "universal",    // "android_12_13"|"android_14_plus"|"universal"
    val osSkin: String? = null,              // "oneui"|"miui"|"stock"|null (any)
    val skinVersion: String? = null,         // "6.1" | "15.0" | null

    // ── Device Scoping ────────────────────────────────────────────
    val deviceModel: String? = null,         // "SM-G991B" | null (any device)
    val appVersion: String? = null,          // "2.24.1.76" | null

    // ── Quality Signals ───────────────────────────────────────────
    val version: Int = 1,
    val usageCount: Int = 0,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val successRate: Float = 1.0f,

    // ── Community ─────────────────────────────────────────────────
    val createdBy: String = "local",         // "local" | "community"
    val isVerified: Boolean = false,         // community-verified (500+ uses, 90%+ success)
    val downloads: Int = 0,
    val description: String = "",

    // ── Health & Sync ─────────────────────────────────────────────
    val isStale: Boolean = false,            // true when skill keeps failing → LLM repairs
    val lastUsedAt: Long = 0L,
    val syncedAt: Long = 0L                  // last cloud sync timestamp
)
