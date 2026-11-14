package io.github.yuriybak.contactlabelshortcut

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds
import android.provider.ContactsContract.Data

/** A phone number of a contact. */
class PhoneNumber(val number: String, val label: CharSequence, val superPrimary: Boolean) {
    /** "Mobile: +1 555 …", for menus. */
    override fun toString() = "$label: $number"
}

/**
 * The app that handles a data row MIME type (a messenger and the like).
 * The icon is loaded on first use: the settings need only the name.
 */
class DataHandler(val packageName: String, val appName: CharSequence, loadIcon: () -> Drawable) {
    val icon: Drawable by lazy(loadIcon)
}

/** A data row of another app (a chat, a call in a messenger, …) that has a handler. */
class AppAction(val dataId: Long, val mimeType: String, val summary: String?, val handler: DataHandler) {
    fun intent(): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(ContentUris.withAppendedId(Data.CONTENT_URI, dataId), mimeType)
            .setPackage(handler.packageName)

    /** Text for a menu: "WhatsApp: Voice call +1 555…". */
    fun title(): CharSequence =
        if (summary.isNullOrBlank()) handler.appName else "${handler.appName}: $summary"

}

/**
 * A messenger the contact is in: the chat (its button) and the rest of its rows,
 * such as voice and video calls (the long-press menu of the button).
 */
class Messenger(val chat: AppAction, val others: List<AppAction>) {
    /** The chat first, then the other rows. */
    val all: List<AppAction> get() = listOf(chat) + others

    /** By the MIME type: messengers name their call rows "….call", "….voip.call", "….call.video", …. */
    val video: AppAction? get() = others.firstOrNull { "video" in it.mimeType }
    val voice: AppAction? get() = others.firstOrNull { "video" !in it.mimeType && ("call" in it.mimeType || "voip" in it.mimeType) }
}

/** Phones, messenger chats and other app actions of one contact. */
class ContactDetails(
    val phones: List<PhoneNumber>,
    /** Known messengers, one button each, in the order of [ContactDetailsLoader.CHAT_MIME_TYPES]. */
    val messengers: List<Messenger>,
    /** Everything else that some app can open, shown in the "more" menu. */
    val other: List<AppAction>,
) {
    /** The number to use without asking, or null if the user has to choose. */
    val defaultPhone: PhoneNumber?
        get() = phones.singleOrNull() ?: phones.firstOrNull { it.superPrimary }

    companion object {
        val EMPTY = ContactDetails(emptyList(), emptyList(), emptyList())
    }
}

/**
 * Loads [ContactDetails] for many contacts with one query. Meant for a background
 * thread; handlers (and their icons) are looked up once per MIME type and cached.
 *
 * The rows of known messengers open only in that messenger's own app: another app that
 * declares the same MIME type can't take over the button.
 */
class ContactDetailsLoader(private val context: Context) {

    private val pm = context.packageManager
    private val handlers = HashMap<String, DataHandler?>()

    /** Packages of contacts apps: their rows ("view in Contacts" and the like) are not actions. */
    private val contactsApps: Set<String> by lazy {
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, 1), ContactsContract.Contacts.CONTENT_ITEM_TYPE)
        pm.queryIntentActivities(view, 0).mapTo(HashSet()) { it.activityInfo.packageName }
    }

    fun load(contactIds: Collection<Long>): Map<Long, ContactDetails> {
        if (contactIds.isEmpty()) return emptyMap()

        val phones = HashMap<Long, MutableList<PhoneNumber>>()
        val actions = HashMap<Long, MutableList<AppAction>>()

        // The ids are numbers from the provider, so they are safe to inline (and there may be
        // more of them than SQLite allows as arguments)
        val selection = "${Data.CONTACT_ID} IN (${contactIds.joinToString(",")})" +
            " AND ${Data.MIMETYPE} NOT IN (${SKIPPED_MIME_TYPES.joinToString(",") { "?" }})"

        context.contentResolver.query(
            Data.CONTENT_URI,
            arrayOf(
                Data._ID,
                Data.CONTACT_ID,
                Data.MIMETYPE,
                Data.DATA1,
                Data.DATA2,
                Data.DATA3,
                Data.IS_SUPER_PRIMARY,
            ),
            selection,
            SKIPPED_MIME_TYPES,
            "${Data.CONTACT_ID}, ${Data.IS_SUPER_PRIMARY} DESC, ${Data._ID}",
        )?.use { c ->
            while (c.moveToNext()) {
                val contactId = c.getLong(1)
                val mimeType = c.getString(2) ?: continue
                if (mimeType == CommonDataKinds.Phone.CONTENT_ITEM_TYPE) {
                    val number = c.getString(3)?.takeIf { it.isNotBlank() } ?: continue
                    val type = if (c.isNull(4)) CommonDataKinds.Phone.TYPE_OTHER else c.getInt(4)
                    val label = CommonDataKinds.Phone.getTypeLabel(context.resources, type, c.getString(5))
                    phones.getOrPut(contactId) { mutableListOf() } +=
                        PhoneNumber(number, label, c.getInt(6) != 0)
                } else {
                    val handler = handler(mimeType) ?: continue
                    val summary = c.getString(5)?.takeIf { it.isNotBlank() } ?: c.getString(4)
                    actions.getOrPut(contactId) { mutableListOf() } +=
                        AppAction(c.getLong(0), mimeType, summary, handler)
                }
            }
        }

        // Load the icons here, in the background, not when the rows are drawn
        for (handler in handlers.values) handler?.icon

        val result = HashMap<Long, ContactDetails>()
        for (id in contactIds) {
            val numbers = phones[id]?.let(::dedupePhones).orEmpty()
            val all = actions[id].orEmpty()
            val chats = mutableListOf<AppAction>()
            for (action in all) {
                if (action.mimeType in CHAT_MIME_TYPES && chats.none { it.mimeType == action.mimeType }) chats += action
            }
            chats.sortBy { CHAT_MIME_TYPES.indexOf(it.mimeType) }
            // The other rows of a messenger's app (calls, video calls) go to its button
            val messengers = chats.map { chat ->
                Messenger(chat, all.filter { it !== chat && it.mimeType !in CHAT_MIME_TYPES && it.handler.packageName == chat.handler.packageName })
            }
            val used = messengers.flatMapTo(HashSet()) { it.all }
            val other = all.filter { it !in used }
            if (numbers.isNotEmpty() || messengers.isNotEmpty() || other.isNotEmpty()) {
                result[id] = ContactDetails(numbers, messengers, other)
            }
        }
        return result
    }

    /** The app that opens rows of [mimeType], or null; cached. */
    fun handler(mimeType: String): DataHandler? {
        if (handlers.containsKey(mimeType)) return handlers[mimeType]
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(ContentUris.withAppendedId(Data.CONTENT_URI, 1), mimeType)
        val allowed = allowedPackages(mimeType)
        val info = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull {
                val pkg = it.activityInfo.packageName
                if (allowed != null) pkg in allowed else pkg != context.packageName && pkg !in contactsApps
            }
        val handler = info?.let {
            val app = it.activityInfo.applicationInfo
            DataHandler(it.activityInfo.packageName, app.loadLabel(pm)) { app.loadIcon(pm) }
        }
        handlers[mimeType] = handler
        return handler
    }

    companion object {
        /**
         * Known messengers by the package in their MIME types (vnd.<package>.…), with the apps
         * allowed to open their rows: the messenger itself and its official editions.
         * Keep in sync with <queries> in the manifest.
         */
        val MESSENGER_APPS = mapOf(
            "com.whatsapp" to setOf("com.whatsapp"),
            "com.whatsapp.w4b" to setOf("com.whatsapp.w4b"),
            "org.telegram.messenger" to setOf("org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.messenger.beta"),
            "org.thoughtcrime.securesms" to setOf("org.thoughtcrime.securesms"),
            "com.viber.voip" to setOf("com.viber.voip"),
            "ch.threema.app" to setOf("ch.threema.app", "ch.threema.app.libre", "ch.threema.app.work"),
        )

        /** Chats of known messengers, in the order their buttons are shown. */
        val CHAT_MIME_TYPES = listOf(
            "vnd.android.cursor.item/vnd.com.whatsapp.profile",
            "vnd.android.cursor.item/vnd.com.whatsapp.w4b.profile",
            "vnd.android.cursor.item/vnd.org.telegram.messenger.android.profile",
            "vnd.android.cursor.item/vnd.org.thoughtcrime.securesms.contact",
            "vnd.android.cursor.item/vnd.com.viber.voip.viber_number_message",
            "vnd.android.cursor.item/vnd.ch.threema.app.profile",
        )

        /** Standard rows that are not app actions (phones are read, but handled separately). */
        private val SKIPPED_MIME_TYPES = arrayOf(
            CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
            CommonDataKinds.Email.CONTENT_ITEM_TYPE,
            CommonDataKinds.Photo.CONTENT_ITEM_TYPE,
            CommonDataKinds.Organization.CONTENT_ITEM_TYPE,
            CommonDataKinds.Im.CONTENT_ITEM_TYPE,
            CommonDataKinds.Nickname.CONTENT_ITEM_TYPE,
            CommonDataKinds.Note.CONTENT_ITEM_TYPE,
            CommonDataKinds.StructuredPostal.CONTENT_ITEM_TYPE,
            CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE,
            CommonDataKinds.Website.CONTENT_ITEM_TYPE,
            CommonDataKinds.Event.CONTENT_ITEM_TYPE,
            CommonDataKinds.Relation.CONTENT_ITEM_TYPE,
            CommonDataKinds.SipAddress.CONTENT_ITEM_TYPE,
            CommonDataKinds.Identity.CONTENT_ITEM_TYPE,
        )
    }
}

/** The apps that may open rows of [mimeType] if it is a known messenger's; null: any app. */
fun allowedPackages(mimeType: String): Set<String>? {
    val prefix = "vnd.android.cursor.item/vnd."
    if (!mimeType.startsWith(prefix)) return null
    val rest = mimeType.substring(prefix.length)
    // The longest match first: "com.whatsapp.w4b.profile" belongs to com.whatsapp.w4b
    val key = ContactDetailsLoader.MESSENGER_APPS.keys
        .filter { rest.startsWith("$it.") }
        .maxByOrNull { it.length }
    return key?.let { ContactDetailsLoader.MESSENGER_APPS.getValue(it) }
}

/** The same number may be stored several times (by different accounts or formats). */
fun dedupePhones(list: List<PhoneNumber>): List<PhoneNumber> {
    val byKey = LinkedHashMap<String, PhoneNumber>()
    for (phone in list) {
        val key = phone.number.filter { it.isDigit() || it == '+' }.ifEmpty { phone.number }
        val existing = byKey[key]
        if (existing == null) {
            byKey[key] = phone
        } else if (phone.superPrimary && !existing.superPrimary) {
            byKey[key] = PhoneNumber(existing.number, existing.label, true)
        }
    }
    return byKey.values.toList()
}
