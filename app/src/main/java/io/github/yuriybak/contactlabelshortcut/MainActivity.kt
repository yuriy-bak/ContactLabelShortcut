package io.github.yuriybak.contactlabelshortcut

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Short instructions and a button that pins a label shortcut. */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            fitsSystemWindows = true
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
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = pad })

        setContentView(layout)
    }
}
