package com.aura.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * PermissionRule — user-defined per-app action permission.
 *
 * Your personal firewall for AURA. Controls exactly what actions
 * AURA is allowed to perform, with optional conditions.
 *
 * Examples:
 *   - WhatsApp send_message → ALWAYS_ALLOW
 *   - GPay send_money → ALWAYS_ALLOW (when amount ≤ ₹500, contact = Rahul)
 *   - GPay send_money → ALWAYS_DENY  (when amount > ₹2000)
 *   - ANY app delete_account → ALWAYS_DENY
 */
@Entity(tableName = "permission_rules")
data class PermissionRule(
    @PrimaryKey val ruleId: String = UUID.randomUUID().toString(),

    /** Scope — null means "any". */
    val appPackage: String?,          // "com.whatsapp" | null = any app
    val intentAction: String?,        // "send_message" | null = any action

    /** The decision when rule matches. */
    val permission: AppPermission,

    /** Optional conditions — rule only fires when ALL present conditions match. */
    val maxAmount: Double? = null,               // for payment actions (₹)
    val allowedContacts: String? = null,         // JSON: ["Rahul","Mom","Dad"]
    val allowedTimeStart: String? = null,        // "09:00" (HH:mm, 24h)
    val allowedTimeEnd: String? = null,          // "22:00"

    /** Higher priority is evaluated first. */
    val priority: Int = 0,

    val label: String = "",                      // user-friendly label shown in UI
    val createdAt: Long = System.currentTimeMillis()
)

enum class AppPermission {
    ALWAYS_ALLOW,    // Execute without any dialog
    ALWAYS_CONFIRM,  // Show approval dialog every time
    ALWAYS_DENY      // Blocked — AURA refuses and explains why
}

/**
 * BlockedApp — completely blocks AURA from interacting with an app.
 * Checked before ANY action is executed.
 */
@Entity(tableName = "blocked_apps")
data class BlockedApp(
    @PrimaryKey val appPackage: String,   // "com.sbi.lotusintouch"
    val displayName: String,              // "SBI YONO"
    val reason: String,                  // "Banking — too sensitive"
    val blockedAt: Long = System.currentTimeMillis()
)
