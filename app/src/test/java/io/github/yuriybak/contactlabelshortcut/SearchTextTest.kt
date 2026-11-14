package io.github.yuriybak.contactlabelshortcut

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTextTest {
    private fun phone(number: String) = PhoneNumber(number, "Mobile", false)

    @Test
    fun normalizeIgnoresCaseAccentsAndYo() {
        assertEquals("елкин", SearchText.normalize("Ёлкин"))
        assertEquals("jose", SearchText.normalize("José"))
        assertEquals("anna", SearchText.normalize("ANNA"))
    }

    @Test
    fun matchesPartOfName() {
        val name = SearchText.normalize("Пётр Ёлкин")
        assertTrue(SearchText("елк").matches(name, emptyList()))
        assertTrue(SearchText("ПЕТР").matches(name, emptyList()))
        assertFalse(SearchText("иван").matches(name, emptyList()))
    }

    @Test
    fun matchesDigitsOfNumber() {
        val phones = listOf(phone("+7 (916) 123-45-67"))
        assertTrue(SearchText("916 12").matches("anna", phones))
        assertTrue(SearchText("+7916").matches("anna", phones))
        assertFalse(SearchText("917").matches("anna", phones))
    }

    @Test
    fun textWithLettersIsNotANumber() {
        assertFalse(SearchText("a916").matches("anna", listOf(phone("916"))))
    }

    @Test
    fun blankIsEmpty() {
        assertTrue(SearchText("   ").isEmpty)
        assertFalse(SearchText("a").isEmpty)
    }
}
