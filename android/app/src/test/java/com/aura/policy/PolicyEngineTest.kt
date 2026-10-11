package com.aura.policy

import com.aura.agent.RiskLevel
import com.aura.data.model.ActionStep
import org.junit.Assert.assertEquals
import org.junit.Test

class PolicyEngineTest {

    private val p = PolicyEngine()
    private fun risk(action: String, uri: String? = null, risk: String? = null) =
        p.classify(ActionStep(action = action, uri = uri, risk = risk))

    @Test fun `navigation and reading are low`() {
        for (a in listOf(ActionStep.OPEN_APP, ActionStep.BACK, ActionStep.WAIT, ActionStep.VERIFY,
            ActionStep.DIAL, ActionStep.SMS, ActionStep.ALARM, ActionStep.MEDIA)) {
            assertEquals(a, RiskLevel.LOW, risk(a))
        }
    }

    @Test fun `calls and taps run without asking`() {
        assertEquals(RiskLevel.MEDIUM, risk(ActionStep.CALL))
        assertEquals(RiskLevel.MEDIUM, risk(ActionStep.TAP))
        assertEquals(RiskLevel.MEDIUM, risk(ActionStep.OPEN_LINK, uri = "https://youtube.com"))
    }

    @Test fun `sending, replying and payments ask first`() {
        assertEquals(RiskLevel.HIGH, risk(ActionStep.CONFIRM_SEND))
        assertEquals(RiskLevel.CRITICAL, risk(ActionStep.CONFIRM_SEND, risk = "CRITICAL"))
        assertEquals(RiskLevel.HIGH, risk(ActionStep.REPLY))
        assertEquals(RiskLevel.HIGH, risk(ActionStep.OPEN_LINK, uri = "upi://pay?pa=x@y&am=10"))
        assertEquals(RiskLevel.HIGH, risk(ActionStep.INTENT, uri = "UPI://pay?pa=x@y"))
    }

    @Test fun `a step's own risk can raise but never lower`() {
        assertEquals(RiskLevel.CRITICAL, risk(ActionStep.TAP, risk = "CRITICAL"))
        assertEquals(RiskLevel.HIGH, risk(ActionStep.REPLY, risk = "LOW"))
        assertEquals(RiskLevel.MEDIUM, risk(ActionStep.TAP, risk = "nonsense"))
    }

    @Test fun `unknown actions are high`() {
        assertEquals(RiskLevel.HIGH, risk("format_phone"))
    }
}
