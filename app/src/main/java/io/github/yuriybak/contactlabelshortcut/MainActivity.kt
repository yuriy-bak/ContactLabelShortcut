package io.github.yuriybak.contactlabelshortcut

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Short instructions, a button that pins a label shortcut and the settings. */
class MainActivity : Activity() {

    private lateinit var openWithButton: Button
    private val gestureButtons = HashMap<Gesture, Button>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        layout.addView(TextView(this).apply {
            setText(R.string.instructions)
            textSize = 16f
            setLineSpacing(0f, 1.2f)
        })

        layout.addView(Button(this).apply {
            setText(R.string.create_shortcut)
            setOnClickListener {
                startActivity(Intent(this@MainActivity, PickLabelActivity::class.java))
            }
        }, wrap().apply { topMargin = pad })

        layout.addView(Button(this).apply {
            setText(R.string.change_icon)
            setOnClickListener { chooseShortcutToRestyle() }
        }, wrap())

        layout.addView(TextView(this).apply {
            setText(R.string.open_with_title)
            textSize = 16f
        }, wrap().apply { topMargin = pad * 3 / 2 })

        openWithButton = Button(this).apply {
            isAllCaps = false
            setOnClickListener { chooseOpenWith() }
        }
        layout.addView(openWithButton, wrap())

        layout.addView(TextView(this).apply {
            setText(R.string.gestures_title)
            textSize = 16f
        }, wrap().apply { topMargin = pad * 3 / 2 })

        for (gesture in Gesture.values()) {
            val button = Button(this).apply {
                isAllCaps = false
                setOnClickListener { chooseGestureAction(gesture) }
            }
            gestureButtons[gesture] = button
            layout.addView(button, wrap())
        }

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(layout)
        })
    }

    override fun onResume() {
        super.onResume()
        openWithButton.text = Settings.openWithName(this)
        for ((gesture, button) in gestureButtons) {
            button.text = gestureText(gesture, GestureActions.name(this, Settings.gestureAction(this, gesture)))
        }
    }

    private fun gestureText(gesture: Gesture, action: CharSequence) = "${getString(gesture.title)}: $action"

    private fun chooseGestureAction(gesture: Gesture) {
        val choices = GestureActions.choices(this)
        val current = choices.indexOfFirst { it.first == Settings.gestureAction(this, gesture) }
        AlertDialog.Builder(this)
            .setTitle(gesture.title)
            .setSingleChoiceItems(choices.map { it.second }.toTypedArray(), current) { dialog, which ->
                Settings.setGestureAction(this, gesture, choices[which].first)
                gestureButtons[gesture]?.text = gestureText(gesture, choices[which].second)
                dialog.dismiss()
            }
            .show()
    }

    private fun chooseOpenWith() {
        val values = mutableListOf(Settings.OPEN_WITH_SYSTEM, Settings.OPEN_WITH_BUILTIN)
        val names = mutableListOf<CharSequence>(
            getString(R.string.open_with_system),
            getString(R.string.label_list_entry),
        )
        for (app in Settings.labelApps(this)) {
            values += app.component
            names += Settings.describe(app)
        }
        val current = values.indexOf(Settings.openWith(this)).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.open_with_title)
            .setSingleChoiceItems(names.toTypedArray(), current) { dialog, which ->
                Settings.setOpenWith(this, values[which])
                openWithButton.text = names[which]
                dialog.dismiss()
            }
            .show()
    }

    /** Pinned label shortcuts get a new icon in place: same id, so the launcher keeps them. */
    private fun chooseShortcutToRestyle() {
        val manager = getSystemService(ShortcutManager::class.java)
        val pinned = manager.pinnedShortcuts
            .filter { it.id.startsWith("label_") }
            .sortedBy { it.shortLabel?.toString()?.lowercase() }
        if (pinned.isEmpty()) {
            Toast.makeText(this, R.string.no_pinned_shortcuts, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.change_icon)
            .setItems(pinned.map { it.shortLabel ?: it.id }.toTypedArray()) { _, which ->
                val shortcut = pinned[which]
                val title = shortcut.shortLabel?.toString() ?: ""
                val initial = ShortcutIcon.saved(this, shortcut.id) ?: ShortcutIcon.default(title)
                IconEditor.show(this, title, initial, R.string.icon_save, onCancel = {}) { icon ->
                    ShortcutIcon.save(this, shortcut.id, icon)
                    val update = ShortcutInfo.Builder(this, shortcut.id).setIcon(icon.toIcon(this)).build()
                    if (!manager.updateShortcuts(listOf(update))) {
                        Toast.makeText(this, R.string.icon_update_failed, Toast.LENGTH_LONG).show()
                    }
                }
            }
            .show()
    }

    private fun wrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )
}
