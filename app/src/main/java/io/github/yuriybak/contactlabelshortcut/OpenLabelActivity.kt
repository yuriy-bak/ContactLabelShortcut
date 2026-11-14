package io.github.yuriybak.contactlabelshortcut

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract.Groups
import android.widget.Toast

/**
 * Started by a home screen shortcut. Finds the label's current id and hands the
 * VIEW intent to the system: the default app, or the chooser. Apps are not
 * filtered; [LabelContactsActivity] is one of the handlers and is also used
 * directly if nothing can be started.
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

        try {
            startActivity(view)
        } catch (e: ActivityNotFoundException) {
            startActivity(
                label.putInto(Intent(this, LabelContactsActivity::class.java))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: SecurityException) {
            startActivity(
                label.putInto(Intent(this, LabelContactsActivity::class.java))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        finish()
    }
}
