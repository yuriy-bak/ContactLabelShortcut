package io.github.yuriybak.contactlabelshortcut

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
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

    private lateinit var style: ListStyle
    private lateinit var executor: ExecutorService
    private lateinit var avatars: AvatarLoader
    private lateinit var iconTint: ColorStateList
    private lateinit var adapter: Adapter
    private var query = ""
    private var detailsLoaded = false

    /** Read on the first gesture, not when the list opens. */
    private val gestureActions: Map<Gesture, String> by lazy(LazyThreadSafetyMode.NONE) {
        Gesture.values().associateWith { Settings.gestureAction(this, it) }
    }
    private val swipeIcons = HashMap<Int, Drawable>()

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
        style = Settings.listStyle(this)

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
            val details = ContactDetailsLoader(this).load(items.map { it.contactId })
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                for (item in items) item.details = details[item.contactId] ?: ContactDetails.EMPTY
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
        val tint = (actionBar?.themedContext ?: this)
            .obtainStyledAttributes(intArrayOf(android.R.attr.colorControlNormal)).run {
                val color = getColorStateList(0)
                recycle()
                color
            }
        fun tinted(res: Int) = getDrawable(res)?.mutate()?.apply { if (tint != null) setTintList(tint) }

        if (::adapter.isInitialized) addSearch(menu, tinted(R.drawable.ic_search))

        menu.add(Menu.NONE, R.id.action_settings, Menu.NONE, R.string.action_settings).apply {
            icon = tinted(R.drawable.ic_settings)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            setOnMenuItemClickListener {
                startActivityForResult(Intent(this@LabelContactsActivity, MainActivity::class.java), REQUEST_SETTINGS)
                true
            }
        }
        return true
    }

    /** Back from the settings: show the list as they say now (buttons, gestures). */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SETTINGS) recreate()
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
        row.resetSwipe()
        row.buttonsKnown = detailsLoaded
        row.nameText = item.name
        if (style.showAvatars) avatars.bind(row.avatar, item.name, item.photoUri)
        row.setOnClickListener { openContact(item) }
        row.setOnLongClickListener { runGesture(item, Gesture.LONG_PRESS) }
        row.swipe = { toRight -> swipeTarget(item, if (toRight) Gesture.SWIPE_RIGHT else Gesture.SWIPE_LEFT) }

        val actions = row.actions
        actions.removeAllViews()
        row.trailing.removeAllViews()
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
        for (messenger in details.messengers) {
            if (style.showsChat(messenger.chat.mimeType)) {
                val chat = messenger.chat
                actions.addView(appButton(chat.handler.icon, chat.title(),
                    onClick = { launch(chat.intent()) },
                    onLongClick = { showMessenger(it, messenger) }))
            }
        }
        if (style.hidesActions) {
            // Hidden buttons are in the menu of all actions, so it is always there
            row.trailing.addView(button(R.drawable.ic_more, R.string.action_more,
                onClick = { showAllActions(item) }, onLongClick = null))
        } else if (details.other.isNotEmpty()) {
            row.trailing.addView(button(R.drawable.ic_more, R.string.action_more,
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

    private fun appButton(icon: Drawable, description: CharSequence, onClick: () -> Unit, onLongClick: (View) -> Unit) =
        actionView(dp(style.appIconSize)).apply {
            // One drawable can't be shown by several views at once
            setImageDrawable(copy(icon))
            contentDescription = description
            setOnClickListener { onClick() }
            setOnLongClickListener { onLongClick(it); true }
        }

    private fun copy(icon: Drawable): Drawable = icon.constantState?.newDrawable(resources) ?: icon

    /** Long press on a messenger button: its chat, calls and other rows. */
    private fun showMessenger(anchor: View, messenger: Messenger) {
        val actions = messenger.all
        val menu = PopupMenu(this, anchor)
        actions.forEachIndexed { index, action -> menu.menu.add(0, index, index, action.shortTitle()) }
        menu.setOnMenuItemClickListener { launch(actions[it.itemId].intent()); true }
        menu.show()
    }

    /** What a gesture does for this contact: icon, color and the action; null if nothing is set. */
    private class GestureTarget(val icon: Drawable?, val iconSize: Int, val color: Int, val run: (() -> Unit)?)

    private fun gestureTarget(item: Item, value: String): GestureTarget? {
        val details = item.details
        val phones = details.phones.isNotEmpty()
        fun own(icon: Int, color: Int, available: Boolean, run: () -> Unit) =
            GestureTarget(swipeIcon(icon), dp(style.actionIconSize), color, if (available) run else null)
        fun app(action: AppAction?) =
            GestureTarget(action?.handler?.icon?.let(::copy), dp(style.appIconSize), COLOR_MESSENGER,
                action?.let { a -> { launch(a.intent()) } })

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
            GestureActions.CALL -> own(R.drawable.ic_call, COLOR_CALL, phones) { withPhone(item, false) { dial(it) } }
            GestureActions.SMS -> own(R.drawable.ic_sms, COLOR_SMS, phones) { withPhone(item, false) { sms(it) } }
            GestureActions.CHAT -> app(details.messengers.firstOrNull()?.chat)
            GestureActions.VOICE -> app(details.messengers.firstNotNullOfOrNull { it.voice })
            GestureActions.VIDEO -> app(details.messengers.firstNotNullOfOrNull { it.video })
            GestureActions.MENU -> own(R.drawable.ic_more, COLOR_OTHER, true) { showAllActions(item) }
            GestureActions.OPEN -> own(R.drawable.ic_person, COLOR_OTHER, true) { openContact(item) }
            GestureActions.EDIT -> own(R.drawable.ic_edit, COLOR_OTHER, true) { editContact(item) }
            GestureActions.COPY -> own(R.drawable.ic_copy, COLOR_OTHER, phones) { withPhone(item, false) { copyNumber(item, it) } }
            GestureActions.SHARE -> own(R.drawable.ic_share, COLOR_OTHER, true) { shareContact(item) }
            else -> null
        }
    }

    private fun swipeTarget(item: Item, gesture: Gesture): SwipeTarget? {
        val target = gestureTarget(item, gestureActions.getValue(gesture)) ?: return null
        val run = target.run
        return if (run != null) {
            SwipeTarget(target.icon, target.iconSize, target.color, run)
        } else {
            SwipeTarget(target.icon, target.iconSize, COLOR_UNAVAILABLE) { notAvailable() }
        }
    }

    /** Returns false if nothing is set for the gesture, so that the press is not used up. */
    private fun runGesture(item: Item, gesture: Gesture): Boolean {
        val target = gestureTarget(item, gestureActions.getValue(gesture)) ?: return false
        target.run?.invoke() ?: notAvailable()
        return true
    }

    private fun notAvailable() = Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show()

    /** Our icons in white, to be drawn on the colored swipe background. */
    private fun swipeIcon(res: Int): Drawable? =
        swipeIcons[res] ?: getDrawable(res)?.mutate()?.also {
            it.setTint(0xFFFFFFFF.toInt())
            swipeIcons[res] = it
        }

    /** Everything that can be done with the contact, in one list. */
    private fun showAllActions(item: Item) {
        val titles = mutableListOf<CharSequence>()
        val actions = mutableListOf<() -> Unit>()
        fun add(title: CharSequence, action: () -> Unit) {
            titles += title
            actions += action
        }
        val details = item.details
        for (phone in details.phones) add(getString(R.string.menu_call, "${phone.label}: ${phone.number}")) { dial(phone.number) }
        for (phone in details.phones) add(getString(R.string.menu_sms, "${phone.label}: ${phone.number}")) { sms(phone.number) }
        for (messenger in details.messengers) for (action in messenger.all) add(action.title()) { launch(action.intent()) }
        for (action in details.other) add(action.title()) { launch(action.intent()) }
        if (details.phones.isNotEmpty()) add(getString(R.string.ga_copy)) { withPhone(item, false) { copyNumber(item, it) } }
        add(getString(R.string.ga_open)) { openContact(item) }
        add(getString(R.string.ga_edit)) { editContact(item) }
        add(getString(R.string.ga_share)) { shareContact(item) }
        AlertDialog.Builder(this)
            .setTitle(item.name)
            .setItems(titles.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun lookupUri(item: Item) = ContactsContract.Contacts.getLookupUri(item.contactId, item.lookupKey)

    private fun editContact(item: Item) =
        launch(Intent(Intent.ACTION_EDIT, lookupUri(item)).putExtra("finishActivityOnSaveCompleted", true))

    private fun shareContact(item: Item) {
        val vcard = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, item.lookupKey)
        val send = Intent(Intent.ACTION_SEND)
            .setType(ContactsContract.Contacts.CONTENT_VCARD_TYPE)
            .putExtra(Intent.EXTRA_STREAM, vcard)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launch(Intent.createChooser(send, item.name))
    }

    private fun copyNumber(item: Item, number: String) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(item.name, number))
        // Android 13+ shows its own confirmation
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.number_copied, Toast.LENGTH_SHORT).show()
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

    private fun openContact(item: Item) = launch(Intent(Intent.ACTION_VIEW, lookupUri(item)))

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

    private companion object {
        const val REQUEST_SETTINGS = 1
        const val COLOR_CALL = 0xFF2E7D32.toInt()
        const val COLOR_SMS = 0xFF1565C0.toInt()
        const val COLOR_MESSENGER = 0xFF00796B.toInt()
        const val COLOR_OTHER = 0xFF546E7A.toInt()
        const val COLOR_UNAVAILABLE = 0xFF757575.toInt()
    }
}
