package io.github.yuriybak.contactlabelshortcut

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract.Groups
import android.widget.Toast

/**
 * Started by a home screen shortcut. Finds the label's current id and opens it
 * in the default contacts app; if no app can open a label, shows the label's
 * contacts in [LabelContactsActivity].
 */
class OpenLabelActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!Labels.hasPermission(this)) {
            Toast.makeText(this, R.string.no_permission, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }

        val label = Labels.resolve(this, intent)
        if (label == null) {
            Toast.makeText(this, R.string.label_not_found, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(ContentUris.withAppendedId(Groups.CONTENT_URI, label.id), Groups.CONTENT_ITEM_TYPE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val target = chooseTarget(view)
        if (target == null) {
            openFallback(label)
        } else {
            try {
                startActivity(target)
            } catch (e: ActivityNotFoundException) {
                openFallback(label)
            } catch (e: SecurityException) {
                openFallback(label)
            }
        }
        finish()
    }

    /**
     * Returns the intent to start, or null to show our own list of the label's contacts.
     * Apps from [BROKEN_PACKAGES] declare the group MIME type but cannot show a group,
     * so they are skipped.
     */
    private fun chooseTarget(view: Intent): Intent? {
        val pm = packageManager
        val handlers = pm.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo }
        // TEST BUILD ONLY: always open the patched Fossify debug build when it is installed.
        handlers.firstOrNull { it.packageName == "org.fossify.contacts.debug" }?.let {
            return Intent(view).setComponent(ComponentName(it.packageName, it.name))
        }
        val good = handlers.filter { it.packageName !in BROKEN_PACKAGES }
        if (good.isEmpty()) return null

        val default = pm.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        // The default contacts app cannot open labels: show our own list, its items open in that app.
        if (default != null && default.packageName in BROKEN_PACKAGES) return null
        if (default != null && good.any { it.packageName == default.packageName && it.name == default.name }) {
            // A working app is set as default: let the system open it.
            return view
        }
        if (good.size == 1) {
            return Intent(view).setComponent(ComponentName(good[0].packageName, good[0].name))
        }
        // Several working apps and no usable default: show the chooser without broken apps.
        val excluded = handlers.filter { it.packageName in BROKEN_PACKAGES }
            .map { ComponentName(it.packageName, it.name) }
        return Intent.createChooser(view, null)
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, excluded.toTypedArray())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun openFallback(label: Label) {
        startActivity(
            label.putInto(Intent(this, LabelContactsActivity::class.java))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    companion object {
        /** Contacts apps that accept the group intent but treat the group id as a contact id. */
        private val BROKEN_PACKAGES = setOf(
            "org.fossify.contacts",
            "com.simplemobiletools.contacts.pro",
            "com.simplemobiletools.contacts.pro.debug",
            "com.simplemobiletools.contacts",
        )
    }
}
