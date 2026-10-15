package com.aura.policy

import com.aura.data.db.BlockedAppDao
import com.aura.data.db.PermissionRuleDao
import com.aura.data.model.ActionStep
import com.aura.data.model.AppPermission
import com.aura.data.model.BlockedApp
import com.aura.data.model.PermissionRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AppPermissionManagerTest {

    private class FakeRules(private val rules: List<PermissionRule>) : PermissionRuleDao {
        override suspend fun upsert(rule: PermissionRule) {}
        override suspend fun delete(ruleId: String) {}
        override fun getAll(): Flow<List<PermissionRule>> = flowOf(rules)
        override suspend fun findRules(appPackage: String, stepAction: String, goalIntent: String) =
            rules.filter {
                (it.appPackage == null || it.appPackage == appPackage) &&
                    (it.intentAction == null || it.intentAction == stepAction || it.intentAction == goalIntent)
            }.sortedByDescending { it.priority }
        override fun getRulesForApp(appPackage: String): Flow<List<PermissionRule>> =
            flowOf(rules.filter { it.appPackage == appPackage })
        override suspend fun count() = rules.size
    }

    private class NoBlockedApps : BlockedAppDao {
        override suspend fun block(app: BlockedApp) {}
        override suspend fun unblock(appPackage: String) {}
        override suspend fun isBlocked(appPackage: String) = false
        override fun getAll(): Flow<List<BlockedApp>> = flowOf(emptyList<BlockedApp>())
    }

    private val gpay = "com.google.android.apps.nbu.paisa.user"
    private val allowUpTo500 = PermissionRule(
        appPackage = gpay, intentAction = "send_money",
        permission = AppPermission.ALWAYS_ALLOW, maxAmount = 500.0
    )
    private val payStep = ActionStep(action = ActionStep.CONFIRM_SEND, message = "Pay ₹300 to Rahul?", risk = "CRITICAL")

    private fun manager(vararg rules: PermissionRule) = AppPermissionManager(FakeRules(rules.toList()), NoBlockedApps())

    @Test fun `allow rule with a limit fires for a small payment`() = runBlocking {
        val d = manager(allowUpTo500).evaluate(payStep, gpay, goal = "pay rahul 300", goalIntent = "send_money")
        assertEquals(PermissionDecision.Allow, d)
    }

    @Test fun `allow rule with a limit does not fire above the limit`() = runBlocking {
        val step = payStep.copy(message = "Pay ₹5,000 to Rahul?")
        val d = manager(allowUpTo500).evaluate(step, gpay, goal = "pay rahul 5000", goalIntent = "send_money")
        assertEquals(PermissionDecision.Default, d)
    }

    @Test fun `allow rule with a limit does not fire when the amount is unknown`() = runBlocking {
        val step = payStep.copy(message = "Pay Rahul?")
        val d = manager(allowUpTo500).evaluate(step, gpay, goal = "pay rahul", goalIntent = "send_money")
        assertEquals(PermissionDecision.Default, d)
    }

    @Test fun `rules can name the whole intent, not just the step`() = runBlocking {
        val deny = PermissionRule(appPackage = gpay, intentAction = "send_money", permission = AppPermission.ALWAYS_DENY)
        val tap = ActionStep(action = ActionStep.TAP, text = "Pay")
        val d = manager(deny).evaluate(tap, gpay, goal = "pay rahul 300", goalIntent = "send_money")
        assertEquals(true, d is PermissionDecision.Deny)
    }

    @Test fun `contact allowlist needs the name in the request`() = runBlocking {
        val rule = PermissionRule(
            appPackage = "com.whatsapp", intentAction = null,
            permission = AppPermission.ALWAYS_ALLOW, allowedContacts = "[\"Mom\"]"
        )
        val tap = ActionStep(action = ActionStep.TAP, text = "Send")
        assertEquals(PermissionDecision.Allow, manager(rule).evaluate(tap, "com.whatsapp", goal = "message mom hi"))
        assertEquals(PermissionDecision.Default, manager(rule).evaluate(tap, "com.whatsapp", goal = "message rahul hi"))
    }
}
