package com.aura.agent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.aura.skills.SkillMatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ContactResolver"

/**
 * ContactResolver — turns a spoken name ("Mom", "rahul") into the exact contact
 * name and phone number from the phone's contacts, before any step runs.
 *
 * For a param like `contact_name` it adds the derived slots
 *   {contact_number}         "+919876543210"  (dial, sms)
 *   {contact_number_digits}  "919876543210"   (wa.me links)
 * and replaces the spoken name with the contact's exact display name.
 *
 * - No READ_CONTACTS permission or no match → params are returned unchanged
 *   (apps like WhatsApp can still search the spoken name themselves).
 * - Two different contacts match equally well → [Result.question] asks which one,
 *   instead of guessing and messaging the wrong person.
 */
@Singleton
class ContactResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class Contact(val name: String, val number: String)

    data class Result(
        val params: Map<String, String>,
        /** Non-null when the name is ambiguous — ask the user this. */
        val question: String? = null
    )

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** Add contact details for the first contact-like param in [params]. */
    suspend fun enrich(params: Map<String, String>): Result {
        val key = params.keys.firstOrNull { isContactSlot(it) } ?: return Result(params)
        val spoken = params[key]?.trim().orEmpty()
        if (spoken.isEmpty() || !hasPermission()) return Result(params)

        val contacts = withContext(Dispatchers.IO) { query(spoken) }
        val ranked = rank(spoken, contacts)
        if (ranked.isEmpty()) return Result(params)

        val bestScore = ranked.first().second
        val tied = ranked.filter { it.second == bestScore }.map { it.first }.distinctBy { it.name.lowercase() }
        if (tied.size > 1 && bestScore < EXACT) {
            val names = tied.take(3).joinToString(" or ") { it.name }
            return Result(params, "I found $names. Which one did you mean? Say the full name.")
        }
        val best = ranked.first().first
        val e164 = toE164(best.number)
        Log.i(TAG, "Resolved \"$spoken\" → ${best.name}")
        return Result(
            params + mapOf(
                key to best.name,
                "contact_number" to e164,
                "contact_number_digits" to e164.filter { it.isDigit() }
            )
        )
    }

    private fun query(spoken: String): List<Contact> {
        val firstWord = spoken.split(' ').first()
        val out = mutableListOf<Contact>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY
                ),
                // Prefilter on the first word (or its first 3 letters, for small misspellings)
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR " +
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$firstWord%", "%${firstWord.take(3)}%"),
                "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC"
            )?.use { c ->
                while (c.moveToNext() && out.size < 200) {
                    val name = c.getString(0) ?: continue
                    val number = c.getString(1) ?: continue
                    out += Contact(name, number)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Contacts query failed: ${e.message}")
        }
        // One entry per person (first number = primary, because of the sort order)
        return out.distinctBy { it.name.lowercase(Locale.ROOT) }
    }

    private fun toE164(number: String): String {
        val trimmed = number.trim()
        if (trimmed.startsWith("+")) return "+" + trimmed.filter { it.isDigit() }
        val iso = try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            (tm.networkCountryIso.ifBlank { tm.simCountryIso }).uppercase(Locale.ROOT)
        } catch (_: Exception) { "" }.ifBlank { Locale.getDefault().country }
        return PhoneNumberUtils.formatNumberToE164(trimmed, iso)
            ?: trimmed.filter { it.isDigit() || it == '+' }
    }

    companion object {
        /** Slots filled by this class at run time (never spoken by the user). */
        val DERIVED_SLOTS = SkillMatcher.DERIVED_SLOTS

        const val EXACT = 100

        fun isContactSlot(name: String): Boolean =
            (name.contains("contact") || name.contains("recipient") || name == "person") &&
                name !in DERIVED_SLOTS

        /**
         * Score contacts for a spoken name, best first, dropping non-matches.
         * exact 100 · starts with 80 · a whole word matches 70 · contains 50 · 1-letter typo 40
         */
        fun rank(spoken: String, contacts: List<Contact>): List<Pair<Contact, Int>> {
            val s = spoken.lowercase(Locale.ROOT).trim()
            return contacts.map { c ->
                val n = c.name.lowercase(Locale.ROOT).trim()
                val words = n.split(Regex("\\s+"))
                val score = when {
                    n == s -> EXACT
                    n.startsWith("$s ") || n.startsWith(s) && s.length >= 3 -> 80
                    s.split(Regex("\\s+")).all { it in words } -> 70
                    s.length >= 3 && n.contains(s) -> 50
                    s.length >= 4 && words.any { editDistance(it, s) <= 1 } -> 40
                    else -> 0
                }
                c to score
            }.filter { it.second > 0 }.sortedByDescending { it.second }
        }

        fun editDistance(a: String, b: String): Int {
            val dp = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                var prev = dp[0]
                dp[0] = i
                for (j in 1..b.length) {
                    val tmp = dp[j]
                    dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                    prev = tmp
                }
            }
            return dp[b.length]
        }
    }
}
