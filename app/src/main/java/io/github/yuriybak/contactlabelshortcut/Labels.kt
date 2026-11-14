package io.github.yuriybak.contactlabelshortcut

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract.Groups

/** A contact label (a group of a contacts account). */
data class Label(
    val id: Long,
    val title: String,
    val accountName: String?,
    val accountType: String?,
    val sourceId: String?,
) {
    fun putInto(intent: Intent): Intent = intent.apply {
        putExtra(EXTRA_GROUP_ID, id)
        putExtra(EXTRA_TITLE, title)
        accountName?.let { putExtra(EXTRA_ACCOUNT_NAME, it) }
        accountType?.let { putExtra(EXTRA_ACCOUNT_TYPE, it) }
        sourceId?.let { putExtra(EXTRA_SOURCE_ID, it) }
    }

    companion object {
        const val EXTRA_GROUP_ID = "group_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ACCOUNT_NAME = "account_name"
        const val EXTRA_ACCOUNT_TYPE = "account_type"
        const val EXTRA_SOURCE_ID = "source_id"
    }
}

object Labels {
    private val PROJECTION = arrayOf(
        Groups._ID,
        Groups.TITLE,
        Groups.ACCOUNT_NAME,
        Groups.ACCOUNT_TYPE,
        Groups.SOURCE_ID,
    )

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** User labels of all accounts, without system groups (My Contacts, Starred, …). */
    fun all(context: Context): List<Label> {
        val result = mutableListOf<Label>()
        context.contentResolver.query(
            Groups.CONTENT_URI,
            PROJECTION,
            "${Groups.DELETED}=0 AND ${Groups.SYSTEM_ID} IS NULL",
            null,
            "${Groups.TITLE} COLLATE LOCALIZED ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(1)
                if (title.isNullOrBlank()) continue
                result += Label(c.getLong(0), title, c.getString(2), c.getString(3), c.getString(4))
            }
        }
        return result
    }

    /**
     * Finds the current local id of a label saved in a shortcut.
     * The local id can change after an account resync, so the server id
     * (SOURCE_ID) and then the title are tried first.
     */
    fun resolve(context: Context, intent: Intent): Label? {
        val sourceId = intent.getStringExtra(Label.EXTRA_SOURCE_ID)
        val title = intent.getStringExtra(Label.EXTRA_TITLE)
        val accountName = intent.getStringExtra(Label.EXTRA_ACCOUNT_NAME)
        val accountType = intent.getStringExtra(Label.EXTRA_ACCOUNT_TYPE)
        val legacyId = intent.getLongExtra(Label.EXTRA_GROUP_ID, -1L)

        if (sourceId != null) {
            find(context, Groups.SOURCE_ID, sourceId, accountName, accountType)?.let { return it }
        }
        if (title != null) {
            find(context, Groups.TITLE, title, accountName, accountType)?.let { return it }
            find(context, Groups.TITLE, title, null, null)?.let { return it }
        }
        if (legacyId >= 0) {
            find(context, Groups._ID, legacyId.toString(), null, null)?.let { return it }
        }
        return null
    }

    private fun find(
        context: Context,
        column: String,
        value: String,
        accountName: String?,
        accountType: String?,
    ): Label? {
        // Like all(): system groups (My Contacts, Starred, …) are never a shortcut's label
        val selection = StringBuilder("$column=? AND ${Groups.DELETED}=0 AND ${Groups.SYSTEM_ID} IS NULL")
        val args = mutableListOf(value)
        if (accountName != null) {
            selection.append(" AND ${Groups.ACCOUNT_NAME}=?")
            args += accountName
        }
        if (accountType != null) {
            selection.append(" AND ${Groups.ACCOUNT_TYPE}=?")
            args += accountType
        }
        context.contentResolver.query(
            Groups.CONTENT_URI, PROJECTION, selection.toString(), args.toTypedArray(), null,
        )?.use { c ->
            if (c.moveToFirst()) {
                return Label(
                    c.getLong(0),
                    c.getString(1) ?: value,
                    c.getString(2),
                    c.getString(3),
                    c.getString(4),
                )
            }
        }
        return null
    }
}
