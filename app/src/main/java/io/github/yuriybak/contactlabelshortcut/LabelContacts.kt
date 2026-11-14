package io.github.yuriybak.contactlabelshortcut

import android.content.ContentUris
import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.GroupMembership

/** A contact of the label list. [details] come later, from a background load. */
class LabelContact(val contactId: Long, val lookupKey: String, val name: String, val photoUri: String?) {
    var details: ContactDetails = ContactDetails.EMPTY

    /** [name] prepared for search; computed on the first search, not when the list opens. */
    val searchName: String by lazy(LazyThreadSafetyMode.NONE) { SearchText.normalize(name) }

    val lookupUri get() = ContactsContract.Contacts.getLookupUri(contactId, lookupKey)
}

/** Reads a label's title and contacts from the contacts provider. */
object LabelContactsRepository {

    fun loadTitle(context: Context, groupId: Long): String? =
        context.contentResolver.query(
            ContentUris.withAppendedId(ContactsContract.Groups.CONTENT_URI, groupId),
            arrayOf(ContactsContract.Groups.TITLE), null, null, null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /**
     * The contacts of the label, in [order]: one query of the names and photos only, so that
     * it is fast enough for the first frame. Phones and messengers are [ContactDetailsLoader]'s.
     */
    fun load(context: Context, groupId: Long, order: ListOrder): List<LabelContact> {
        val seen = HashSet<Long>()
        val result = mutableListOf<LabelContact>()
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.LOOKUP_KEY,
                ContactsContract.Data.DISPLAY_NAME_PRIMARY,
                ContactsContract.Data.PHOTO_THUMBNAIL_URI,
            ),
            "${ContactsContract.Data.MIMETYPE}=? AND ${GroupMembership.GROUP_ROW_ID}=?",
            arrayOf(GroupMembership.CONTENT_ITEM_TYPE, groupId.toString()),
            sortOrder(order),
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (!seen.add(id)) continue
                result += LabelContact(id, c.getString(1) ?: continue, c.getString(2) ?: "—", c.getString(3))
            }
        }
        return result
    }

    private fun sortOrder(order: ListOrder) = listOfNotNull(
        if (order.starredFirst) "${ContactsContract.Data.STARRED} DESC" else null,
        // Sort keys follow the name order: "first last" or "last, first"
        (if (order.sortByLastName) ContactsContract.Data.SORT_KEY_ALTERNATIVE else ContactsContract.Data.SORT_KEY_PRIMARY) +
            " COLLATE LOCALIZED ASC",
        "${ContactsContract.Data.DISPLAY_NAME_PRIMARY} COLLATE LOCALIZED ASC",
    ).joinToString(", ")
}
