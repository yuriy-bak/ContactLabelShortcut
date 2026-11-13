package io.github.yuriybak.contactlabelshortcut

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView

/** Fallback: the contacts of a label, opened one by one in any contacts app. */
class LabelContactsActivity : Activity() {

    private data class Item(val contactId: Long, val lookupKey: String, val name: String) {
        override fun toString() = name
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val groupId = intent.getLongExtra(Label.EXTRA_GROUP_ID, -1L)
        title = intent.getStringExtra(Label.EXTRA_TITLE) ?: getString(R.string.app_name)

        val items = if (groupId >= 0 && Labels.hasPermission(this)) load(groupId) else emptyList()

        if (items.isEmpty()) {
            setContentView(TextView(this).apply {
                setText(R.string.no_contacts)
                val pad = (24 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                fitsSystemWindows = true
            })
            return
        }

        val list = ListView(this).apply {
            adapter = ArrayAdapter(this@LabelContactsActivity, android.R.layout.simple_list_item_1, items)
            setOnItemClickListener { _, _, position, _ ->
                val item = items[position]
                val uri = ContactsContract.Contacts.getLookupUri(item.contactId, item.lookupKey)
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            }
            fitsSystemWindows = true
        }
        setContentView(list)
    }

    private fun load(groupId: Long): List<Item> {
        val seen = HashSet<Long>()
        val result = mutableListOf<Item>()
        contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.LOOKUP_KEY,
                ContactsContract.Data.DISPLAY_NAME_PRIMARY,
            ),
            "${ContactsContract.Data.MIMETYPE}=? AND ${GroupMembership.GROUP_ROW_ID}=?",
            arrayOf(GroupMembership.CONTENT_ITEM_TYPE, groupId.toString()),
            "${ContactsContract.Data.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (!seen.add(id)) continue
                result += Item(id, c.getString(1) ?: continue, c.getString(2) ?: "—")
            }
        }
        return result
    }
}
