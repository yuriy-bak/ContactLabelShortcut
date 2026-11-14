package io.github.yuriybak.contactlabelshortcut

import android.content.Context

/** Gestures on a row of the label list. */
enum class Gesture(val key: String, val title: Int, val default: String) {
    SWIPE_RIGHT("gesture_swipe_right", R.string.gesture_swipe_right, GestureActions.CALL),
    SWIPE_LEFT("gesture_swipe_left", R.string.gesture_swipe_left, GestureActions.SMS),
    LONG_PRESS("gesture_long_press", R.string.gesture_long_press, GestureActions.MENU),
}

/**
 * Actions that can be put on a [Gesture], stored in the settings as strings:
 * a fixed code, or "chat|<MIME>", "voice|<MIME>", "video|<MIME>" for one messenger
 * (the MIME type of its chat row, see [ContactDetailsLoader.CHAT_MIME_TYPES]).
 */
object GestureActions {
    const val NONE = "none"
    const val MENU = "menu"
    const val OPEN = "open"
    const val EDIT = "edit"
    const val CALL = "call"
    const val SMS = "sms"
    const val COPY = "copy"
    const val SHARE = "share"
    /** Without a MIME type: the first messenger of the contact that has it. */
    const val CHAT = "chat"
    const val VOICE = "voice"
    const val VIDEO = "video"

    private val FIXED = listOf(
        NONE to R.string.ga_none,
        MENU to R.string.ga_menu,
        CALL to R.string.action_call,
        SMS to R.string.action_sms,
        CHAT to R.string.ga_chat,
        VOICE to R.string.ga_voice,
        VIDEO to R.string.ga_video,
        OPEN to R.string.ga_open,
        EDIT to R.string.ga_edit,
        COPY to R.string.ga_copy,
        SHARE to R.string.ga_share,
    )

    private val MESSENGER_KINDS = listOf(
        CHAT to R.string.ga_chat_in,
        VOICE to R.string.ga_voice_in,
        VIDEO to R.string.ga_video_in,
    )

    /** "chat" and null for "chat"; "voice" and the MIME type for "voice|<MIME>". */
    fun parse(value: String): Pair<String, String?> {
        val bar = value.indexOf('|')
        return if (bar < 0) value to null else value.substring(0, bar) to value.substring(bar + 1)
    }

    /** All choices for the settings, with the messengers that are installed. */
    fun choices(context: Context): List<Pair<String, CharSequence>> {
        val result = FIXED.mapTo(mutableListOf<Pair<String, CharSequence>>()) { (code, title) -> code to context.getString(title) }
        val loader = ContactDetailsLoader(context)
        for (mime in ContactDetailsLoader.CHAT_MIME_TYPES) {
            val app = loader.handler(mime)?.appName ?: continue
            for ((kind, title) in MESSENGER_KINDS) result += "$kind|$mime" to context.getString(title, app)
        }
        return result
    }

    fun name(context: Context, value: String): CharSequence {
        FIXED.firstOrNull { it.first == value }?.let { return context.getString(it.second) }
        val (kind, mime) = parse(value)
        val title = MESSENGER_KINDS.firstOrNull { it.first == kind }?.second
        val app = mime?.let { ContactDetailsLoader(context).handler(it)?.appName }
        return if (title != null && app != null) context.getString(title, app) else context.getString(R.string.ga_none)
    }
}
