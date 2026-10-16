package com.aura.skills

import org.junit.Assert.assertEquals
import org.junit.Test

class SkillIdentityTest {

    private fun intent(goal: String, app: String) = SkillIdentity.inferIntent(goal, app)

    @Test fun `a call is a call even when it mentions WhatsApp`() {
        assertEquals("messaging" to "make_call", intent("call mom on whatsapp", "com.whatsapp"))
    }

    @Test fun `messages stay messages, even with words like bring`() {
        assertEquals("messaging" to "send_message", intent("message rahul on whatsapp", "com.whatsapp"))
        assertEquals("messaging" to "send_message", intent("text priya to bring milk", "com.whatsapp"))
    }

    @Test fun `a message that mentions calling is still a message`() {
        assertEquals("messaging" to "send_message", intent("text mom to call me back", "com.whatsapp"))
        assertEquals("messaging" to "send_message", intent("message rahul i'll call you later", "com.whatsapp"))
        assertEquals("messaging" to "make_call", intent("whatsapp call rahul", "com.whatsapp"))
        assertEquals("messaging" to "make_call", intent("call mom and text her the address", "com.whatsapp"))
    }

    @Test fun `sending an email is compose, not the inbox`() {
        assertEquals("email" to "compose_send", intent("send an email to priya", "com.google.android.gm"))
        assertEquals("email" to "read_inbox", intent("open my gmail inbox", "com.google.android.gm"))
    }
}
