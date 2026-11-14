package io.github.yuriybak.contactlabelshortcut

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureActionsTest {
    @Test
    fun parseFixedAndMessengerValues() {
        assertEquals("call" to null, GestureActions.parse("call"))
        assertEquals("voice" to "vnd.android.cursor.item/vnd.com.whatsapp.profile",
            GestureActions.parse("voice|vnd.android.cursor.item/vnd.com.whatsapp.profile"))
    }
}
