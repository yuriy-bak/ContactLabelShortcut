package io.github.yuriybak.contactlabelshortcut

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Short instructions, a button that pins a label shortcut and the settings. */
class MainActivity : Activity() {

    private lateinit var openWithButton: Button
    private val gestureButtons = HashMap<Gesture, Button>()
    private lateinit var rowButtonsButton: Button
    private lateinit var inlineButtonsButton: Button
    private lateinit var sortButton: Button

    /** Finds the installed messengers for the gesture names; one for the screen, it caches them. */
    private val loader by lazy(LazyThreadSafetyMode.NONE) { ContactDetailsLoader(this) }
    private val labelApps by lazy(LazyThreadSafetyMode.NONE) { Settings.labelApps(this) }

    /** Tells the label list that opened the settings to show itself anew. */
    private val changes = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> setResult(RESULT_OK) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.registerListener(this, changes)
        val pad = dp(24)

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
            setText(R.string.sort_title)
            textSize = 16f
        }, wrap().apply { topMargin = pad * 3 / 2 })

        sortButton = Button(this).apply {
            isAllCaps = false
            setOnClickListener { chooseSort() }
        }
        layout.addView(sortButton, wrap())

        layout.addView(Switch(this).apply {
            setText(R.string.starred_first)
            textSize = 16f
            isChecked = Settings.starredFirst(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Settings.setStarredFirst(this@MainActivity, checked) }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = pad / 3 })

        layout.addView(TextView(this).apply {
            setText(R.string.buttons_title)
            textSize = 16f
        }, wrap().apply { topMargin = pad * 3 / 2 })

        rowButtonsButton = Button(this).apply {
            isAllCaps = false
            setOnClickListener { chooseRowButtons() }
        }
        layout.addView(rowButtonsButton, wrap())

        inlineButtonsButton = Button(this).apply {
            isAllCaps = false
            setOnClickListener { chooseInlineButtons() }
        }
        layout.addView(inlineButtonsButton, wrap())

        layout.addView(TextView(this).apply {
            setText(R.string.gestures_title)
            textSize = 16f
        }, wrap().apply { topMargin = pad * 3 / 2 })

        for (gesture in Gesture.entries) {
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

    override fun onDestroy() {
        Settings.unregisterListener(this, changes)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        openWithButton.text = Settings.openWithName(this, labelApps)
        rowButtonsButton.text = rowButtonsText()
        inlineButtonsButton.text = inlineButtonsText(Settings.inlineButtons(this))
        sortButton.setText(if (Settings.sortByLastName(this)) R.string.sort_last_name else R.string.sort_first_name)
        for ((gesture, button) in gestureButtons) {
            button.text = gestureText(gesture, GestureActions.name(this, Settings.gestureAction(this, gesture), loader))
        }
    }

    private fun gestureText(gesture: Gesture, action: CharSequence) = "${getString(gesture.title)}: $action"

    private fun chooseGestureAction(gesture: Gesture) {
        val choices = GestureActions.choices(this, loader)
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

    /** "Call, Send SMS, Messengers", or "Only ⋮" when all are hidden. */
    private fun rowButtonsText(): CharSequence {
        val shown = Settings.RowButton.entries.filter { Settings.showButton(this, it) }
        return if (shown.isEmpty()) getString(R.string.buttons_more_only)
            else shown.joinToString(", ") { getString(it.title) }
    }

    private fun chooseSort() {
        val names = arrayOf(getString(R.string.sort_first_name), getString(R.string.sort_last_name))
        AlertDialog.Builder(this)
            .setTitle(R.string.sort_title)
            .setSingleChoiceItems(names, if (Settings.sortByLastName(this)) 1 else 0) { dialog, which ->
                Settings.setSortByLastName(this, which == 1)
                sortButton.text = names[which]
                dialog.dismiss()
            }
            .show()
    }

    private fun inlineButtonsText(count: Int): String =
        if (count == 0) getString(R.string.inline_buttons_never) else getString(R.string.inline_buttons_up_to, count)

    private fun chooseInlineButtons() {
        val values = (0..MAX_INLINE_BUTTONS).toList()
        AlertDialog.Builder(this)
            .setTitle(R.string.inline_buttons_title)
            .setSingleChoiceItems(values.map { inlineButtonsText(it) }.toTypedArray(), Settings.inlineButtons(this)) { dialog, which ->
                Settings.setInlineButtons(this, values[which])
                inlineButtonsButton.text = inlineButtonsText(values[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun chooseRowButtons() {
        val buttons = Settings.RowButton.entries
        val checked = buttons.map { Settings.showButton(this, it) }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.buttons_title)
            .setMultiChoiceItems(buttons.map { getString(it.title) }.toTypedArray(), checked) { _, which, isChecked ->
                Settings.setShowButton(this, buttons[which], isChecked)
                rowButtonsButton.text = rowButtonsText()
            }
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun chooseOpenWith() {
        val values = mutableListOf(Settings.OPEN_WITH_SYSTEM, Settings.OPEN_WITH_BUILTIN)
        val names = mutableListOf<CharSequence>(
            getString(R.string.open_with_system),
            getString(R.string.label_list_entry),
        )
        for (app in labelApps) {
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
        // Some launchers do not report their shortcuts: only clean up when this one does
        ShortcutIcon.retainOnly(this, pinned.mapTo(HashSet()) { it.id })
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

    private companion object {
        const val MAX_INLINE_BUTTONS = 5
    }
}
