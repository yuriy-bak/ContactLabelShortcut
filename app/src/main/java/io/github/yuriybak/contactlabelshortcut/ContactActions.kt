package io.github.yuriybak.contactlabelshortcut

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.widget.Toast

/** What can be done with a contact of the list: each action starts another app. */
class ContactActions(private val activity: Activity) {

    fun dial(number: String) = launch(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))

    fun sms(number: String) = launch(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))

    fun open(contact: LabelContact) = launch(Intent(Intent.ACTION_VIEW, contact.lookupUri))

    fun edit(contact: LabelContact) =
        launch(Intent(Intent.ACTION_EDIT, contact.lookupUri).putExtra("finishActivityOnSaveCompleted", true))

    fun share(contact: LabelContact) {
        val vcard = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, contact.lookupKey)
        val send = Intent(Intent.ACTION_SEND)
            .setType(ContactsContract.Contacts.CONTENT_VCARD_TYPE)
            .putExtra(Intent.EXTRA_STREAM, vcard)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        launch(Intent.createChooser(send, contact.name))
    }

    fun copyNumber(contact: LabelContact, number: String) {
        activity.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(contact.name, number))
        // Android 13+ shows its own confirmation
        if (Build.VERSION.SDK_INT < 33) toast(R.string.number_copied)
    }

    /** Runs [action] with the default number, or asks which one (always, if [ask]). */
    fun withPhone(contact: LabelContact, ask: Boolean, action: (String) -> Unit) {
        val phones = contact.details.phones
        val default = contact.details.defaultPhone
        if (phones.size == 1 || (!ask && default != null)) {
            action((default ?: phones.first()).number)
            return
        }
        AlertDialog.Builder(activity)
            .setTitle(contact.name)
            .setItems(phones.map { it.toString() }.toTypedArray()) { _, which -> action(phones[which].number) }
            .show()
    }

    /** Starts [intent]; says so if no app can do it. */
    fun launch(intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(R.string.no_app)
        } catch (e: SecurityException) {
            // The app is there but does not let us start it
            toast(R.string.no_app)
        }
    }

    fun notAvailable() = toast(R.string.not_available)

    private fun toast(res: Int) = Toast.makeText(activity, res, Toast.LENGTH_SHORT).show()
}
