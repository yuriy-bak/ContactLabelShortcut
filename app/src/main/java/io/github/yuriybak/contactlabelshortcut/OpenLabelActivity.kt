package io.github.yuriybak.contactlabelshortcut

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Started by a home screen shortcut. Finds the label's current id and opens it
 * where [Settings.openWith] says: the system choice, our own list or a chosen app.
 * If nothing can open it, shows the label's contacts in [LabelContactsActivity].
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

        val builtin = label.putInto(Intent(this, LabelContactsActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val view = Settings.groupIntent(label.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        when (val openWith = Settings.openWith(this)) {
            Settings.OPEN_WITH_BUILTIN -> startActivity(builtin)
            Settings.OPEN_WITH_SYSTEM -> start(view, builtin)
            else -> {
                val component = ComponentName.unflattenFromString(openWith)
                // The chosen app may have been uninstalled: fall back to the system choice
                if (component == null || !start(Intent(view).setComponent(component), null)) {
                    start(view, builtin)
                }
            }
        }
        finish()
    }

    /** Starts [intent]; on failure starts [fallback] if given. Returns whether [intent] started. */
    private fun start(intent: Intent, fallback: Intent?): Boolean {
        try {
            startActivity(intent)
            return true
        } catch (e: ActivityNotFoundException) {
        } catch (e: SecurityException) {
        }
        fallback?.let { startActivity(it) }
        return false
    }
}
