package com.aura.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockDetectorTest {

    @Test fun `app lock and fingerprint prompts are detected`() {
        assertTrue(LockDetector.looksLocked("WhatsApp locked\nUse fingerprint to unlock"))
        assertTrue(LockDetector.looksLocked("Unlock WhatsApp\nTouch the fingerprint sensor"))
        assertTrue(LockDetector.looksLocked("App lock\nEnter PIN"))
        assertTrue(LockDetector.looksLocked("Verify it’s you\nUse your fingerprint"))
        assertTrue(LockDetector.looksLocked("Draw pattern"))
        assertTrue(LockDetector.looksLocked("Paytm is locked"))
    }

    @Test fun `ordinary screens are not locks`() {
        assertFalse(LockDetector.looksLocked("Chats\nLocked chats\nRahul\nHi there"))
        assertFalse(LockDetector.looksLocked("Search YouTube\nlofi hip hop radio"))
        assertFalse(LockDetector.looksLocked("Unlock premium features for ₹99 a month"))
        assertFalse(LockDetector.looksLocked(""))
    }
}
