package io.github.yuriybak.contactlabelshortcut

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.SearchView
import android.widget.TextView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Our own list of a label's contacts: tap a contact to open it in a contacts app,
 * or use the buttons to call, send an SMS or open a chat in a messenger.
 *
 * The names are shown at once; phones, messengers and photos are loaded in the background.
 * The search in the action bar filters by name or number; it costs nothing until it is used.
 *
 * Loading is [LabelContactsRepository]'s and [ContactDetailsLoader]'s, the actions are
 * [ContactActions], the menus [ActionMenu] and the gestures [RowGestures].
 */
class LabelContactsActivity : Activity() {

    private lateinit var style: ListStyle
    private lateinit var executor: ExecutorService
    private lateinit var avatars: AvatarLoader
    private lateinit var adapter: Adapter
    private val actions = ContactActions(this)
    private lateinit var menu: ActionMenu
    private lateinit var gestures: RowGestures
    private var query = ""
    private var detailsLoaded = false
    private val callText by lazy(LazyThreadSafetyMode.NONE) { getString(R.string.action_call) }
    private val smsText by lazy(LazyThreadSafetyMode.NONE) { getString(R.string.action_sms) }
    private val moreText by lazy(LazyThreadSafetyMode.NONE) { getString(R.string.action_more) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // From our shortcut: extras; from the system chooser: content://com.android.contacts/groups/<id>
        val groupId = intent.getLongExtra(Label.EXTRA_GROUP_ID, -1L).takeIf { it >= 0 }
            ?: intent.data?.lastPathSegment?.toLongOrNull()
            ?: -1L
        val permitted = Labels.hasPermission(this)
        title = intent.getStringExtra(Label.EXTRA_TITLE)
            ?: (if (permitted && groupId >= 0) LabelContactsRepository.loadTitle(this, groupId) else null)
            ?: getString(R.string.app_name)
        // Settings in the place of the "up" button, left of the title
        actionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(tinted(R.drawable.ic_settings))
            setHomeActionContentDescription(R.string.action_settings)
        }

        style = Settings.listStyle(this)
        // On the main thread on purpose: the names are in the first frame (one light query)
        val contacts = if (groupId >= 0 && permitted) {
            LabelContactsRepository.load(this, groupId, Settings.listOrder(this))
        } else {
            emptyList()
        }

        if (contacts.isEmpty()) {
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
        menu = ActionMenu(this, style, actions)
        gestures = RowGestures(this, style, actions, menu)

        adapter = Adapter(contacts)
        val list = ListView(this).apply {
            this.adapter = this@LabelContactsActivity.adapter
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
            val details = ContactDetailsLoader(this).load(contacts.map { it.contactId })
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                for (contact in contacts) contact.details = details[contact.contactId] ?: ContactDetails.EMPTY
                detailsLoaded = true
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
        addSearch(menu, tinted(R.drawable.ic_search))
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            startActivityForResult(Intent(this, MainActivity::class.java), REQUEST_SETTINGS)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** An icon in the color of the action bar icons. */
    private fun tinted(res: Int): Drawable? {
        val tint = (actionBar?.themedContext ?: this).themeColor(android.R.attr.colorControlNormal)
        return getDrawable(res)?.mutate()?.apply { if (tint != null) setTintList(tint) }
    }

    /** Back from the settings: if something changed, show the list as they say now. */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SETTINGS && resultCode == RESULT_OK) recreate()
    }

    private fun addSearch(menu: Menu, searchIcon: Drawable?) {
        val item = menu.add(Menu.NONE, R.id.action_search, Menu.NONE, R.string.action_search)
        item.icon = searchIcon
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

    private inner class Adapter(private val contacts: List<LabelContact>) : BaseAdapter() {
        private var shown = contacts

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
            shown = if (search.isEmpty) contacts else contacts.filter { search.matches(it.searchName, it.details.phones) }
            notifyDataSetChanged()
        }
    }

    private fun bind(row: ContactRow, contact: LabelContact) {
        row.resetSwipe()
        row.buttonsKnown = detailsLoaded
        row.nameText = contact.name
        if (style.showAvatars) avatars.bind(row.avatar, contact.name, contact.photoUri)
        row.setOnClickListener { actions.open(contact) }
        row.setOnLongClickListener { gestures.run(contact, Gesture.LONG_PRESS) }
        row.swipe = { toRight -> gestures.swipe(contact, if (toRight) Gesture.SWIPE_RIGHT else Gesture.SWIPE_LEFT) }
        row.setActions(rowActions(contact), moreAction(contact))
    }

    /** The buttons of a row: call, SMS and the messengers' chats, as the settings say. */
    private fun rowActions(contact: LabelContact): List<RowAction> {
        val details = contact.details
        val list = ArrayList<RowAction>(details.messengers.size + 2)
        if (details.phones.isNotEmpty()) {
            if (style.showCall) {
                list += RowAction(R.drawable.ic_call, null, callText,
                    onClick = { actions.withPhone(contact, false, actions::dial) },
                    onLongClick = { actions.withPhone(contact, true, actions::dial) })
            }
            if (style.showSms) {
                list += RowAction(R.drawable.ic_sms, null, smsText,
                    onClick = { actions.withPhone(contact, false, actions::sms) },
                    onLongClick = { actions.withPhone(contact, true, actions::sms) })
            }
        }
        for (messenger in details.messengers) {
            if (style.showsChat(messenger.chat.mimeType)) {
                val chat = messenger.chat
                list += RowAction(0, chat.handler.icon, chat.title(),
                    onClick = { actions.launch(chat.intent()) },
                    onLongClick = { menu.showMessenger(contact, messenger) })
            }
        }
        return list
    }

    /** "More": all actions if some buttons are hidden, else the actions of other apps (if any). */
    private fun moreAction(contact: LabelContact): RowAction? = when {
        // Hidden buttons are in the menu of all actions, so it is always there
        style.hidesActions -> RowAction(R.drawable.ic_more, null, moreText,
            onClick = { menu.showAll(contact) }, onLongClick = null)
        contact.details.other.isNotEmpty() -> RowAction(R.drawable.ic_more, null, moreText,
            onClick = { menu.showOther(contact) }, onLongClick = null)
        else -> null
    }

    private companion object {
        const val REQUEST_SETTINGS = 1
    }
}
