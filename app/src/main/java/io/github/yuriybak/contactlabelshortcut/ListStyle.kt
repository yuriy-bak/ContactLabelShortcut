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
    val showChats: Boolean = true,
) {
    /** Some buttons are hidden: the "more" button then opens the menu of all actions. */
    val hidesActions: Boolean get() = !showCall || !showSms || !showChats

    /** No buttons but "more": it goes on the name line, rows are one line high. */
    val moreOnly: Boolean get() = !showCall && !showSms && !showChats

    companion object {
        val DEFAULT = ListStyle()
    }
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
