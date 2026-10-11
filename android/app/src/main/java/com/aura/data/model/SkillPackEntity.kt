package com.aura.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * SkillPackEntity — one **variant** of a reusable skill: the steps for one task
 * (intent) in one environment (OEM skin + Android version group + app major version).
 *
 *   intentId  = hash(appPackage, intentCategory, intentAction)        — the task
 *   skillId   = hash(intentId, osSkin, osClassTag, appMajorVersion)   — this variant
 *
 * So "send WhatsApp message" can have a OneUI/Android 14 variant, a MIUI/Android 13
 * variant and a universal one side by side. See `SkillIdentity` for the IDs and
 * `agent/SkillRouter` for how a variant is chosen for this phone.
 *
 * [steps] is a template: values from the goal appear as `{slot}` and are filled
 * from [phrasePatterns] at run time by `SkillMatcher` — no LLM call needed.
 *
 * Lookup chain: L1 RAM → L2 disk → L3 Room → L4 Supabase cloud → LLM creates it.
 * @Serializable because the disk cache stores each variant as a JSON file.
 */
@Serializable
@Entity(tableName = "skill_packs", indices = [Index("intentId")])
data class SkillPackEntity(
    @PrimaryKey val skillId: String,          // variant hash — see class doc

    // ── Task identity (shared by all variants of the same task) ──
    @ColumnInfo(defaultValue = "")
    val intentId: String = "",                // hash(appPackage, intentCategory, intentAction)

    // ── Identity ──────────────────────────────────────────────────
    val appPackage: String,                   // "com.whatsapp"
    val intentCategory: String = "unknown",   // "messaging" | "payment" | "food" ...
    val intentAction: String = "unknown",     // "send_message" | "send_money" ...
    val displayName: String = "",             // "WhatsApp Send Message"

    // ── Matching ──────────────────────────────────────────────────
    val triggerKeywords: String,              // JSON: ["whatsapp","message","text"] — fixed words of the patterns
    val parameters: String = "[]",           // JSON: slot names, e.g. ["contact_name","message_text"]
    @ColumnInfo(defaultValue = "[]")
    val phrasePatterns: String = "[]",       // JSON: ["send {message_text} to {contact_name} on whatsapp", …]

    // ── Execution ─────────────────────────────────────────────────
    val steps: String,                        // JSON: List<ActionStep> template with {slot}s
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
    val appVersion: String? = null,          // "2.24.1.76" | null — full version it was made on
    val appMajorVersion: String? = null,     // "2" | null (any) — part of the variant key

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
