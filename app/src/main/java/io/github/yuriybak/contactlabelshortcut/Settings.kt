package io.github.yuriybak.contactlabelshortcut

import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract.Groups

/** User settings, kept in SharedPreferences. */
object Settings {
    private const val FILE = "settings"
    private const val KEY_OPEN_WITH = "open_with"

    /** Let Android decide: the default app or its chooser. */
    const val OPEN_WITH_SYSTEM = "system"

    /** Always our own [LabelContactsActivity]. */
    const val OPEN_WITH_BUILTIN = "builtin"

    /** One of [OPEN_WITH_SYSTEM], [OPEN_WITH_BUILTIN] or a flattened ComponentName. */
    fun openWith(context: Context): String =
        prefs(context).getString(KEY_OPEN_WITH, null) ?: OPEN_WITH_SYSTEM

    fun setOpenWith(context: Context, value: String) {
        prefs(context).edit().putString(KEY_OPEN_WITH, value).apply()
    }

    /** The intent that opens a label in a contacts app. */
    fun groupIntent(groupId: Long): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(ContentUris.withAppendedId(Groups.CONTENT_URI, groupId), Groups.CONTENT_ITEM_TYPE)

    /** Other apps that accept the label intent: component (flattened) to app name. */
    fun labelApps(context: Context): List<Pair<String, CharSequence>> {
        val pm = context.packageManager
        return pm.queryIntentActivities(groupIntent(1), 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .map {
                val component = ComponentName(it.activityInfo.packageName, it.activityInfo.name)
                component.flattenToString() to it.loadLabel(pm)
            }
            .sortedBy { it.second.toString().lowercase() }
    }

    /** Human readable name of the current [openWith] value. */
    fun openWithName(context: Context): CharSequence = when (val value = openWith(context)) {
        OPEN_WITH_SYSTEM -> context.getString(R.string.open_with_system)
        OPEN_WITH_BUILTIN -> context.getString(R.string.label_list_entry)
        else -> labelApps(context).firstOrNull { it.first == value }?.second
            ?: context.getString(R.string.open_with_system)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
