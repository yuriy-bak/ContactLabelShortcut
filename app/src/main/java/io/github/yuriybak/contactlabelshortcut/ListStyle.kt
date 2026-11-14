package io.github.yuriybak.contactlabelshortcut

import android.content.Context

/**
 * Sizes and visibility of the label list elements, in dp/sp.
 * Kept in one place so that list styles can later be chosen in the settings.
 */
data class ListStyle(
    val rowPaddingHorizontal: Int = 16,
    val rowPaddingVertical: Int = 4,
    val rowMinHeight: Int = 64,
    val avatarSize: Int = 40,
    val avatarGap: Int = 16,
    val nameTextSize: Float = 17f,
    /** Touch target of an action button. */
    val actionSize: Int = 44,
    /** Our own icons (call, SMS, menu). */
    val actionIconSize: Int = 24,
    /** Messenger app icons; app icons have built-in padding, so they are drawn larger. */
    val appIconSize: Int = 32,
    val showAvatars: Boolean = true,
    val showCall: Boolean = true,
    val showSms: Boolean = true,
    val showWhatsApp: Boolean = true,
    val showTelegram: Boolean = true,
    val showSignal: Boolean = true,
    /** Viber, Threema and other known messengers. */
    val showOtherChats: Boolean = true,
    /** A row with at most this many buttons (with "more") is one line; 0: buttons always on the second line. */
    val inlineButtons: Int = 0,
    /** Order by the last name (the provider's alternative sort key) instead of the first name. */
    val sortByLastName: Boolean = false,
    /** Starred contacts first, each part in the chosen order. */
    val starredFirst: Boolean = false,
) {
    private val shown get() = listOf(showCall, showSms, showWhatsApp, showTelegram, showSignal, showOtherChats)

    /** Some buttons are hidden: the "more" button then opens the menu of all actions. */
    val hidesActions: Boolean get() = !shown.all { it }

    /** No buttons but "more": it goes on the name line, rows are one line high. */
    val moreOnly: Boolean get() = shown.none { it }

    /** Whether the button of the messenger with this chat MIME type is shown. */
    fun showsChat(mimeType: String): Boolean = when {
        "whatsapp" in mimeType -> showWhatsApp
        "telegram" in mimeType -> showTelegram
        "thoughtcrime.securesms" in mimeType -> showSignal
        else -> showOtherChats
    }

    companion object {
        val DEFAULT = ListStyle()
    }
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
