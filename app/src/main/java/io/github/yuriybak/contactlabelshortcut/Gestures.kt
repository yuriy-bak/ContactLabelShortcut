package io.github.yuriybak.contactlabelshortcut

import android.content.Context
import android.graphics.drawable.Drawable

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

    /** All choices for the settings, with the messengers that are installed ([loader] finds them). */
    fun choices(context: Context, loader: ContactDetailsLoader): List<Pair<String, CharSequence>> {
        val result = FIXED.mapTo(mutableListOf<Pair<String, CharSequence>>()) { (code, title) -> code to context.getString(title) }
        for (mime in ContactDetailsLoader.CHAT_MIME_TYPES) {
            val app = loader.handler(mime)?.appName ?: continue
            for ((kind, title) in MESSENGER_KINDS) result += "$kind|$mime" to context.getString(title, app)
        }
        return result
    }

    fun name(context: Context, value: String, loader: ContactDetailsLoader): CharSequence {
        FIXED.firstOrNull { it.first == value }?.let { return context.getString(it.second) }
        val (kind, mime) = parse(value)
        val title = MESSENGER_KINDS.firstOrNull { it.first == kind }?.second
        val app = mime?.let { loader.handler(it)?.appName }
        return if (title != null && app != null) context.getString(title, app) else context.getString(R.string.ga_none)
    }
}

/**
 * What the gestures do on a row of the label list, for a contact: the action, and how a
 * swipe shows it (icon and color). The settings are read on the first gesture.
 */
class RowGestures(
    private val context: Context,
    private val style: ListStyle,
    private val actions: ContactActions,
    private val menu: ActionMenu,
) {
    private val settings: Map<Gesture, String> by lazy(LazyThreadSafetyMode.NONE) {
        Gesture.entries.associateWith { Settings.gestureAction(context, it) }
    }
    private val swipeIcons = HashMap<Int, Drawable>()

    /** What a gesture does for this contact: icon, color and the action; null run: not available. */
    private class Target(val icon: Drawable?, val iconSize: Int, val color: Int, val run: (() -> Unit)?)

    /** What a swipe shows and does; null if nothing is set for it. */
    fun swipe(contact: LabelContact, gesture: Gesture): SwipeTarget? {
        val target = target(contact, settings.getValue(gesture)) ?: return null
        val run = target.run
        return if (run != null) {
            SwipeTarget(target.icon, target.iconSize, target.color, run)
        } else {
            SwipeTarget(target.icon, target.iconSize, COLOR_UNAVAILABLE) { actions.notAvailable() }
        }
    }

    /** Runs the gesture; returns false if nothing is set for it, so that the press is not used up. */
    fun run(contact: LabelContact, gesture: Gesture): Boolean {
        val target = target(contact, settings.getValue(gesture)) ?: return false
        target.run?.invoke() ?: actions.notAvailable()
        return true
    }

    private fun target(contact: LabelContact, value: String): Target? {
        val details = contact.details
        val phones = details.phones.isNotEmpty()
        fun own(icon: Int, color: Int, available: Boolean, run: () -> Unit) =
            Target(swipeIcon(icon), context.dp(style.actionIconSize), color, if (available) run else null)
        fun app(action: AppAction?) =
            Target(action?.handler?.icon?.let { it.constantState?.newDrawable(context.resources) ?: it },
                context.dp(style.appIconSize), COLOR_MESSENGER, action?.let { a -> { actions.launch(a.intent()) } })

        val (kind, mime) = GestureActions.parse(value)
        if (mime != null) {
            val messenger = details.messengers.firstOrNull { it.chat.mimeType == mime }
            return when (kind) {
                GestureActions.CHAT -> app(messenger?.chat)
                GestureActions.VOICE -> app(messenger?.voice)
                GestureActions.VIDEO -> app(messenger?.video)
                else -> null
            }
        }
        return when (kind) {
            GestureActions.CALL -> own(R.drawable.ic_call, COLOR_CALL, phones) { actions.withPhone(contact, false, actions::dial) }
            GestureActions.SMS -> own(R.drawable.ic_sms, COLOR_SMS, phones) { actions.withPhone(contact, false, actions::sms) }
            GestureActions.CHAT -> app(details.messengers.firstOrNull()?.chat)
            GestureActions.VOICE -> app(details.messengers.firstNotNullOfOrNull { it.voice })
            GestureActions.VIDEO -> app(details.messengers.firstNotNullOfOrNull { it.video })
            GestureActions.MENU -> own(R.drawable.ic_more, COLOR_OTHER, true) { menu.showAll(contact) }
            GestureActions.OPEN -> own(R.drawable.ic_person, COLOR_OTHER, true) { actions.open(contact) }
            GestureActions.EDIT -> own(R.drawable.ic_edit, COLOR_OTHER, true) { actions.edit(contact) }
            GestureActions.COPY -> own(R.drawable.ic_copy, COLOR_OTHER, phones) {
                actions.withPhone(contact, false) { actions.copyNumber(contact, it) }
            }
            GestureActions.SHARE -> own(R.drawable.ic_share, COLOR_OTHER, true) { actions.share(contact) }
            else -> null
        }
    }

    /** Our icons in white, to be drawn on the colored swipe background. */
    private fun swipeIcon(res: Int): Drawable? =
        swipeIcons[res] ?: context.getDrawable(res)?.mutate()?.also {
            it.setTint(0xFFFFFFFF.toInt())
            swipeIcons[res] = it
        }

    private companion object {
        const val COLOR_CALL = 0xFF2E7D32.toInt()
        const val COLOR_SMS = 0xFF1565C0.toInt()
        const val COLOR_MESSENGER = 0xFF00796B.toInt()
        const val COLOR_OTHER = 0xFF546E7A.toInt()
        const val COLOR_UNAVAILABLE = 0xFF757575.toInt()
    }
}
