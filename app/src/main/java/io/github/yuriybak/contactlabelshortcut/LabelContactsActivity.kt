package io.github.yuriybak.contactlabelshortcut

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Our own list of a label's contacts: tap a contact to open it in a contacts app,
 * or use the buttons to call, send an SMS or open a chat in a messenger.
 *
 * The names are shown at once; phones, messengers and photos are loaded in the background.
 */
class LabelContactsActivity : Activity() {

    private class Item(val contactId: Long, val lookupKey: String, val name: String, val photoUri: String?) {
        var details: ContactDetails = ContactDetails.EMPTY
    }

    private val style = ListStyle.DEFAULT
    private lateinit var executor: ExecutorService
    private lateinit var avatars: AvatarLoader
    private lateinit var iconTint: ColorStateList

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // From our shortcut: extras; from the system chooser: content://com.android.contacts/groups/<id>
        val groupId = intent.getLongExtra(Label.EXTRA_GROUP_ID, -1L).takeIf { it >= 0 }
            ?: intent.data?.lastPathSegment?.toLongOrNull()
            ?: -1L
        val permitted = Labels.hasPermission(this)
        title = intent.getStringExtra(Label.EXTRA_TITLE)
            ?: (if (permitted && groupId >= 0) loadTitle(groupId) else null)
            ?: getString(R.string.app_name)

        val items = if (groupId >= 0 && permitted) load(groupId) else emptyList()

        if (items.isEmpty()) {
            setContentView(TextView(this).apply {
                setText(R.string.no_contacts)
                val pad = dp(24)
                setPadding(pad, pad, pad, pad)
                fitsSystemWindows = true
            })
            return
        }

        executor = Executors.newFixedThreadPool(2)
        avatars = AvatarLoader(this, executor)
        iconTint = obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary)).run {
            val tint = getColorStateList(0) ?: ColorStateList.valueOf(0xFF757575.toInt())
            recycle()
            tint
        }

        val adapter = Adapter(items)
        setContentView(ListView(this).apply {
            this.adapter = adapter
            divider = null
            fitsSystemWindows = true
        })

        executor.execute {
            val details = ContactDetailsLoader(this).load(items.map { it.contactId })
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                for (item in items) item.details = details[item.contactId] ?: ContactDetails.EMPTY
                adapter.notifyDataSetChanged()
            }
        }
    }

    override fun onDestroy() {
        if (::executor.isInitialized) executor.shutdownNow()
        super.onDestroy()
    }

    private inner class Adapter(private val items: List<Item>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].contactId
        override fun hasStableIds() = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? ContactRow ?: ContactRow(this@LabelContactsActivity, style)
            bind(row, items[position])
            return row
        }
    }

    private fun bind(row: ContactRow, item: Item) {
        row.name.text = item.name
        if (style.showAvatars) avatars.bind(row.avatar, item.name, item.photoUri)
        row.setOnClickListener { openContact(item) }

        val actions = row.actions
        actions.removeAllViews()
        val details = item.details
        if (details.phones.isNotEmpty()) {
            if (style.showCall) {
                actions.addView(button(R.drawable.ic_call, R.string.action_call,
                    onClick = { withPhone(item, false) { dial(it) } },
                    onLongClick = { withPhone(item, true) { dial(it) } }))
            }
            if (style.showSms) {
                actions.addView(button(R.drawable.ic_sms, R.string.action_sms,
                    onClick = { withPhone(item, false) { sms(it) } },
                    onLongClick = { withPhone(item, true) { sms(it) } }))
            }
        }
        if (style.showChats) {
            for (chat in details.chats) {
                actions.addView(appButton(chat.handler.icon, chat.title()) { launch(chat.intent()) })
            }
        }
        if (details.other.isNotEmpty()) {
            actions.addView(button(R.drawable.ic_more, R.string.action_more,
                onClick = { showMore(it, details.other) }, onLongClick = null))
        }
    }

    private fun button(icon: Int, description: Int, onClick: (View) -> Unit, onLongClick: ((View) -> Unit)?) =
        actionView(dp(style.actionIconSize)).apply {
            setImageResource(icon)
            imageTintList = iconTint
            contentDescription = getString(description)
            setOnClickListener { onClick(it) }
            if (onLongClick != null) setOnLongClickListener { onLongClick(it); true }
        }

    private fun appButton(icon: Drawable, description: CharSequence, onClick: () -> Unit) =
        actionView(dp(style.appIconSize)).apply {
            // One drawable can't be shown by several views at once
            setImageDrawable(icon.constantState?.newDrawable(resources) ?: icon)
            contentDescription = description
            setOnClickListener { onClick() }
        }

    private fun actionView(iconSize: Int): ImageView {
        val size = dp(style.actionSize)
        val pad = ((size - iconSize) / 2).coerceAtLeast(0)
        return ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            setPadding(pad, pad, pad, pad)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
            background = attrs.getDrawable(0)
            attrs.recycle()
        }
    }

    /** Runs [action] with the default number, or asks which one (always, if [ask]). */
    private fun withPhone(item: Item, ask: Boolean, action: (String) -> Unit) {
        val phones = item.details.phones
        val default = item.details.defaultPhone
        if (phones.size == 1 || (!ask && default != null)) {
            action((default ?: phones.first()).number)
            return
        }
        val names = phones.map { "${it.label}: ${it.number}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(names) { _, which -> action(phones[which].number) }
            .show()
    }

    private fun dial(number: String) =
        launch(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))

    private fun sms(number: String) =
        launch(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))

    private fun showMore(anchor: View, actions: List<AppAction>) {
        val menu = PopupMenu(this, anchor)
        actions.forEachIndexed { index, action -> menu.menu.add(0, index, index, action.title()) }
        menu.setOnMenuItemClickListener { launch(actions[it.itemId].intent()); true }
        menu.show()
    }

    private fun openContact(item: Item) {
        val uri = ContactsContract.Contacts.getLookupUri(item.contactId, item.lookupKey)
        launch(Intent(Intent.ACTION_VIEW, uri))
    }

    private fun launch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadTitle(groupId: Long): String? =
        contentResolver.query(
            ContentUris.withAppendedId(ContactsContract.Groups.CONTENT_URI, groupId),
            arrayOf(ContactsContract.Groups.TITLE), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    private fun load(groupId: Long): List<Item> {
        val seen = HashSet<Long>()
        val result = mutableListOf<Item>()
        contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.LOOKUP_KEY,
                ContactsContract.Data.DISPLAY_NAME_PRIMARY,
                ContactsContract.Data.PHOTO_THUMBNAIL_URI,
            ),
            "${ContactsContract.Data.MIMETYPE}=? AND ${GroupMembership.GROUP_ROW_ID}=?",
            arrayOf(GroupMembership.CONTENT_ITEM_TYPE, groupId.toString()),
            "${ContactsContract.Data.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (!seen.add(id)) continue
                result += Item(id, c.getString(1) ?: continue, c.getString(2) ?: "—", c.getString(3))
            }
        }
        return result
    }
}
