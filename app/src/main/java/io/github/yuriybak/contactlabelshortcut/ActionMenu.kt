package io.github.yuriybak.contactlabelshortcut

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** The menus of a contact (all actions, a messenger, "more"): dialogs with icons. */
class ActionMenu(
    private val activity: Activity,
    private val style: ListStyle,
    private val actions: ContactActions,
) {
    private val iconTint = activity.themeColor(android.R.attr.textColorSecondary)

    /**
     * A line of a menu: [icon] (our own, or the app's), [kind] (chat, call or video next to an
     * app icon) and a text.
     */
    private class Entry(val icon: Drawable?, val kind: Drawable?, val text: CharSequence, val run: () -> Unit) {
        val appIcon get() = kind != null
    }

    /** Everything that can be done with the contact, in one list. */
    fun showAll(contact: LabelContact) {
        val entries = mutableListOf<Entry>()
        val details = contact.details
        for (phone in details.phones) entries += Entry(ownIcon(R.drawable.ic_call), null, phone.toString()) { actions.dial(phone.number) }
        for (phone in details.phones) entries += Entry(ownIcon(R.drawable.ic_sms), null, phone.toString()) { actions.sms(phone.number) }
        for (action in details.messengers.flatMap { it.all } + details.other) entries += appEntry(action)
        if (details.phones.isNotEmpty()) {
            entries += Entry(ownIcon(R.drawable.ic_copy), null, activity.getString(R.string.ga_copy)) {
                actions.withPhone(contact, false) { actions.copyNumber(contact, it) }
            }
        }
        entries += Entry(ownIcon(R.drawable.ic_person), null, activity.getString(R.string.ga_open)) { actions.open(contact) }
        entries += Entry(ownIcon(R.drawable.ic_edit), null, activity.getString(R.string.ga_edit)) { actions.edit(contact) }
        entries += Entry(ownIcon(R.drawable.ic_share), null, activity.getString(R.string.ga_share)) { actions.share(contact) }
        show(contact.name, entries)
    }

    /** Long press on a messenger button: its chat, calls and other rows. */
    fun showMessenger(contact: LabelContact, messenger: Messenger) = show(contact.name, messenger.all.map(::appEntry))

    /** The "more" button: actions of other apps. */
    fun showOther(contact: LabelContact) = show(contact.name, contact.details.other.map(::appEntry))

    private fun ownIcon(res: Int) = activity.getDrawable(res)?.mutate()?.apply { setTintList(iconTint) }

    /** An app action: the app icon, what it does as an icon, and the number from its summary. */
    private fun appEntry(action: AppAction): Entry {
        val mime = action.mimeType
        val kind = when {
            mime in ContactDetailsLoader.CHAT_MIME_TYPES -> R.drawable.ic_chat
            "video" in mime -> R.drawable.ic_videocam
            "call" in mime || "voip" in mime -> R.drawable.ic_call
            else -> null
        }
        // "Voice call +1 555 …" → "+1 555 …": the icon already says what it is
        val summary = action.summary?.takeIf { it.isNotBlank() }
        val start = summary?.indexOfFirst { it == '+' || it.isDigit() } ?: -1
        val text = when {
            kind != null && summary != null && start >= 0 -> summary.substring(start)
            summary != null -> summary
            else -> action.handler.appName
        }
        val icon = action.handler.icon.let { it.constantState?.newDrawable(activity.resources) ?: it }
        return Entry(icon, kind?.let(::ownIcon) ?: ColorDrawable(0), text) { actions.launch(action.intent()) }
    }

    private fun show(title: CharSequence, entries: List<Entry>) {
        val pad = activity.dp(24)
        val appBox = activity.dp(style.appIconSize)
        val ownSize = activity.dp(style.actionIconSize)
        val gap = activity.dp(12)
        val textColor = activity.themeColor(android.R.attr.textColorPrimary)
        val adapter = object : BaseAdapter() {
            override fun getCount() = entries.size
            override fun getItem(position: Int) = entries[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView as? LinearLayout ?: LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = activity.dp(52)
                    setPaddingRelative(pad, 0, pad, 0)
                    addView(ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_INSIDE },
                        LinearLayout.LayoutParams(appBox, appBox))
                    addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER },
                        LinearLayout.LayoutParams(ownSize, ownSize).apply { marginStart = gap })
                    addView(TextView(context).apply {
                        textSize = 16f
                        if (textColor != null) setTextColor(textColor)
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = gap })
                }
                val entry = entries[position]
                val first = row.getChildAt(0) as ImageView
                val second = row.getChildAt(1) as ImageView
                // Columns stay aligned: our own icons are drawn at their size inside the app icon box
                first.setImageDrawable(entry.icon)
                val inset = if (entry.appIcon) 0 else (appBox - ownSize) / 2
                first.setPadding(inset, inset, inset, inset)
                second.setImageDrawable(entry.kind)
                second.visibility = if (entry.kind != null) View.VISIBLE else View.INVISIBLE
                (row.getChildAt(2) as TextView).text = entry.text
                return row
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setAdapter(adapter) { _, which -> entries[which].run() }
            .show()
    }
}
