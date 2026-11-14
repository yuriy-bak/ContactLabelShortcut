package io.github.yuriybak.contactlabelshortcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactDetailsTest {

    @Test
    fun dedupeKeepsFirstFormatAndSuperPrimary() {
        val list = listOf(
            PhoneNumber("+7 916 123-45-67", "Mobile", false),
            PhoneNumber("+79161234567", "Work", true),
            PhoneNumber("8 800 555", "Other", false),
        )
        val result = dedupePhones(list)
        assertEquals(2, result.size)
        assertEquals("+7 916 123-45-67", result[0].number)
        assertTrue(result[0].superPrimary)
        assertEquals("8 800 555", result[1].number)
    }

    @Test
    fun knownMessengersArePinnedToTheirApps() {
        assertEquals(setOf("com.whatsapp"), allowedPackages("vnd.android.cursor.item/vnd.com.whatsapp.profile"))
        assertEquals(setOf("com.whatsapp"), allowedPackages("vnd.android.cursor.item/vnd.com.whatsapp.voip.call"))
        assertEquals(setOf("com.whatsapp.w4b"), allowedPackages("vnd.android.cursor.item/vnd.com.whatsapp.w4b.profile"))
        assertTrue("org.telegram.messenger.web" in allowedPackages("vnd.android.cursor.item/vnd.org.telegram.messenger.android.profile")!!)
        assertEquals(setOf("org.thoughtcrime.securesms"), allowedPackages("vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.call"))
    }

    @Test
    fun otherMimeTypesAreOpenToAnyApp() {
        assertNull(allowedPackages("vnd.android.cursor.item/vnd.com.example.chat"))
        assertNull(allowedPackages("vnd.android.cursor.item/com.whatsapp.profile"))
        // A package that only starts with a known name is not that messenger
        assertNull(allowedPackages("vnd.android.cursor.item/vnd.com.whatsappx.profile"))
    }

    @Test
    fun everyChatTypeIsAKnownMessenger() {
        for (mime in ContactDetailsLoader.CHAT_MIME_TYPES) {
            assertTrue(mime, allowedPackages(mime) != null)
        }
    }
}
