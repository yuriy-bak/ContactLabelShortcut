package io.github.yuriybak.contactlabelshortcut

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Bundle
import android.widget.Toast

/**
 * Lets the user pick a label and creates a home screen shortcut for it.
 *
 * Started either by the launcher (ACTION_CREATE_SHORTCUT, the result is returned
 * to the launcher) or from [MainActivity] (the shortcut is pinned with
 * ShortcutManager.requestPinShortcut).
 */
class PickLabelActivity : Activity() {

    private val fromLauncher get() = intent?.action == Intent.ACTION_CREATE_SHORTCUT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Labels.hasPermission(this)) {
            showPicker()
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), REQUEST_CONTACTS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CONTACTS) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            showPicker()
        } else {
            toast(R.string.no_permission)
            cancel()
        }
    }

    private fun showPicker() {
        val labels = Labels.all(this)
        if (labels.isEmpty()) {
            toast(R.string.no_labels)
            cancel()
            return
        }

        // Show the account only when the same title exists in several accounts
        val duplicates = labels.groupingBy { it.title }.eachCount().filterValues { it > 1 }.keys
        val items = labels.map { l ->
            if (l.title in duplicates && l.accountName != null) "${l.title} (${l.accountName})" else l.title
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.pick_label)
            .setItems(items) { _, which -> chooseIcon(labels[which]) }
            .setOnCancelListener { cancel() }
            .show()
    }

    private fun chooseIcon(label: Label) {
        val id = shortcutId(label)
        val initial = ShortcutIcon.saved(this, id) ?: ShortcutIcon.default(label.title)
        IconEditor.show(this, label.title, initial, R.string.icon_add, onCancel = ::cancel) { icon ->
            ShortcutIcon.save(this, id, icon)
            createShortcut(label, icon)
        }
    }

    private fun createShortcut(label: Label, icon: ShortcutIcon) {
        val info = buildShortcut(this, label, icon)
        val shortcuts = getSystemService(ShortcutManager::class.java)

        if (fromLauncher) {
            setResult(RESULT_OK, shortcuts.createShortcutResultIntent(info))
        } else if (shortcuts.isRequestPinShortcutSupported) {
            shortcuts.requestPinShortcut(info, null)
        } else {
            toast(R.string.pin_not_supported)
        }
        finish()
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQUEST_CONTACTS = 1

        /** Stable while the label exists: it is how a pinned shortcut is found again. */
        fun shortcutId(label: Label): String =
            "label_${label.accountName}_${label.sourceId ?: label.id.toString()}"

        fun buildShortcut(context: Context, label: Label, icon: ShortcutIcon): ShortcutInfo {
            val launch = label.putInto(
                Intent(context, OpenLabelActivity::class.java).setAction(Intent.ACTION_VIEW)
            )
            return ShortcutInfo.Builder(context, shortcutId(label))
                .setShortLabel(label.title)
                .setLongLabel(label.title)
                .setIcon(icon.toIcon(context))
                .setIntent(launch)
                .build()
        }
    }
}
