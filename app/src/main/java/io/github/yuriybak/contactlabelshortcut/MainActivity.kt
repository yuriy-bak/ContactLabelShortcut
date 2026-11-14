package io.github.yuriybak.contactlabelshortcut

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Short instructions, a button that pins a label shortcut and the settings. */
class MainActivity : Activity() {

    private lateinit var openWithButton: Button

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

        layout.addView(TextView(this).apply {
            setText(R.string.open_with_title)
            textSize = 16f
        }, wrap().apply { topMargin = pad * 3 / 2 })

        openWithButton = Button(this).apply { setOnClickListener { chooseOpenWith() } }
        layout.addView(openWithButton, wrap())

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(layout)
        })
    }

    override fun onResume() {
        super.onResume()
        openWithButton.text = Settings.openWithName(this)
    }

    private fun chooseOpenWith() {
        val values = mutableListOf(Settings.OPEN_WITH_SYSTEM, Settings.OPEN_WITH_BUILTIN)
        val names = mutableListOf<CharSequence>(
            getString(R.string.open_with_system),
            getString(R.string.label_list_entry),
        )
        for ((component, name) in Settings.labelApps(this)) {
            values += component
            names += name
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

    private fun wrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )
}
