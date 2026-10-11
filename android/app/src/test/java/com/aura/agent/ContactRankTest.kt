package com.aura.agent

import com.aura.agent.ContactResolver.Contact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactRankTest {

    private val contacts = listOf(
        Contact("Mom", "+911111111111"),
        Contact("Rahul Kumar", "+912222222222"),
        Contact("Rahul Sharma", "+913333333333"),
        Contact("Priya", "+914444444444"),
        Contact("Momo Shop", "+915555555555")
    )

    @Test fun `exact name wins`() {
        val ranked = ContactResolver.rank("mom", contacts)
        assertEquals("Mom", ranked.first().first.name)
        assertEquals(ContactResolver.EXACT, ranked.first().second)
    }

    @Test fun `first name shared by two people is a tie`() {
        val ranked = ContactResolver.rank("rahul", contacts)
        assertEquals(ranked[0].second, ranked[1].second)
        assertTrue(ranked.take(2).map { it.first.name }.containsAll(listOf("Rahul Kumar", "Rahul Sharma")))
    }

    @Test fun `full name picks one`() {
        assertEquals("Rahul Sharma", ContactResolver.rank("rahul sharma", contacts).first().first.name)
    }

    @Test fun `small misspelling still matches`() {
        assertEquals("Priya", ContactResolver.rank("priyaa", contacts).first().first.name)
    }

    @Test fun `unknown names give nothing`() {
        assertTrue(ContactResolver.rank("zubin", contacts).isEmpty())
    }

    @Test fun `derived slots are never treated as the spoken contact`() {
        assertTrue(ContactResolver.isContactSlot("contact_name"))
        assertTrue(!ContactResolver.isContactSlot("contact_number"))
        assertTrue(!ContactResolver.isContactSlot("message_text"))
    }
}
