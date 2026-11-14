package io.github.yuriybak.contactlabelshortcut

import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
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

    /** An app that can open labels. */
    class LabelApp(val component: String, val name: CharSequence, val packageName: String)

    /**
     * Other apps that open labels. Only apps that name the label type explicitly:
     * viewers that accept any type (* / *, vnd.android.cursor.item / *) are left out.
     */
    fun labelApps(context: Context): List<LabelApp> {
        val pm = context.packageManager
        return pm.queryIntentActivities(groupIntent(1), PackageManager.GET_RESOLVED_FILTER)
            .filter { it.activityInfo.packageName != context.packageName }
            .filter { info ->
                val filter = info.filter ?: return@filter true
                (0 until filter.countDataTypes()).any { filter.getDataType(it) == Groups.CONTENT_ITEM_TYPE }
            }
            .map {
                val component = ComponentName(it.activityInfo.packageName, it.activityInfo.name)
                LabelApp(component.flattenToString(), it.loadLabel(pm), it.activityInfo.packageName)
            }
            .distinctBy { it.component }
            .sortedBy { it.name.toString().lowercase() }
    }

    /** "Contacts" with the package name on a second, smaller line. */
    fun describe(app: LabelApp): CharSequence {
        val text = SpannableStringBuilder(app.name).append('\n')
        val start = text.length
        text.append(app.packageName)
        text.setSpan(RelativeSizeSpan(0.75f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return text
    }

    /** Human readable name of the current [openWith] value. */
    fun openWithName(context: Context): CharSequence = when (val value = openWith(context)) {
        OPEN_WITH_SYSTEM -> context.getString(R.string.open_with_system)
        OPEN_WITH_BUILTIN -> context.getString(R.string.label_list_entry)
        else -> labelApps(context).firstOrNull { it.component == value }?.let(::describe)
            ?: context.getString(R.string.open_with_system)
    }

    /** One of [GestureActions]. */
    fun gestureAction(context: Context, gesture: Gesture): String =
        prefs(context).getString(gesture.key, null) ?: gesture.default

    fun setGestureAction(context: Context, gesture: Gesture, value: String) {
        prefs(context).edit().putString(gesture.key, value).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
