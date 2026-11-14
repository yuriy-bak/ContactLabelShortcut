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
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.SearchView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Our own list of a label's contacts: tap a contact to open it in a contacts app,
 * or use the buttons to call, send an SMS or open a chat in a messenger.
 *
 * The names are shown at once; phones, messengers and photos are loaded in the background.
 * The search in the action bar filters by name or number; it costs nothing until it is used.
 */
class LabelContactsActivity : Activity() {

    private class Item(val contactId: Long, val lookupKey: String, val name: String, val photoUri: String?) {
        var details: ContactDetails = ContactDetails.EMPTY

        /** [name] prepared for search; computed on the first search, not when the list opens. */
        val searchName: String by lazy(LazyThreadSafetyMode.NONE) { SearchText.normalize(name) }
    }

    private val style = ListStyle.DEFAULT
    private lateinit var executor: ExecutorService
    private lateinit var avatars: AvatarLoader
    private lateinit var iconTint: ColorStateList
    private lateinit var adapter: Adapter
    private var query = ""

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

        adapter = Adapter(items)
        val list = ListView(this).apply {
            this.adapter = adapter
            divider = null
        }
        val empty = TextView(this).apply {
            setText(R.string.nothing_found)
            gravity = Gravity.CENTER_HORIZONTAL
            val pad = dp(24)
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
        }
        list.emptyView = empty
        setContentView(FrameLayout(this).apply {
            fitsSystemWindows = true
            addView(list)
            addView(empty, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        })

        executor.execute {
            val details = ContactDetailsLoader(this).load(items.map { it.contactId })
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                for (item in items) item.details = details[item.contactId] ?: ContactDetails.EMPTY
                // A number typed before the phones were loaded can match now
                if (query.isNotEmpty()) adapter.filter(query) else adapter.notifyDataSetChanged()
            }
        }
    }

    override fun onDestroy() {
        if (::executor.isInitialized) executor.shutdownNow()
        super.onDestroy()
    }

    /**
     * The search button is a plain menu item; the [SearchView] is created only when it is
     * tapped, so a list that is not searched pays nothing for it.
     */
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (!::adapter.isInitialized) return false
        val tint = (actionBar?.themedContext ?: this)
            .obtainStyledAttributes(intArrayOf(android.R.attr.colorControlNormal)).run {
                val color = getColorStateList(0)
                recycle()
                color
            }
        val item = menu.add(Menu.NONE, R.id.action_search, Menu.NONE, R.string.action_search)
        item.icon = getDrawable(R.drawable.ic_search)?.mutate()?.apply { if (tint != null) setTintList(tint) }
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
        item.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem) = true
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                (item.actionView as? SearchView)?.setQuery("", false)
                search("")
                return true
            }
        })
        item.setOnMenuItemClickListener {
            if (it.actionView == null) it.actionView = createSearchView()
            it.expandActionView()
            true
        }
        return true
    }

    private fun createSearchView() = SearchView(actionBar?.themedContext ?: this).apply {
        queryHint = getString(R.string.search_hint)
        imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        maxWidth = Int.MAX_VALUE
        setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String): Boolean {
                search(newText)
                return true
            }

            override fun onQueryTextSubmit(text: String): Boolean {
                clearFocus() // hides the keyboard so that the results can be seen
                return true
            }
        })
    }

    private fun search(text: String) {
        if (text == query) return
        query = text
        adapter.filter(text)
    }

    private inner class Adapter(private val items: List<Item>) : BaseAdapter() {
        private var shown = items

        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = shown[position].contactId
        override fun hasStableIds() = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? ContactRow ?: ContactRow(this@LabelContactsActivity, style)
            bind(row, shown[position])
            return row
        }

        fun filter(text: String) {
            val search = SearchText(text)
            shown = if (search.isEmpty) items else items.filter { search.matches(it.searchName, it.details.phones) }
            notifyDataSetChanged()
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
