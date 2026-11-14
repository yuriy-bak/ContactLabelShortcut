package io.github.yuriybak.contactlabelshortcut

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * A dialog to choose a shortcut icon: a preview, the letter or emoji and a color.
 * Starts from [initial]; an empty text falls back to the label's first letter.
 */
object IconEditor {

    fun show(
        activity: Activity,
        title: String,
        initial: ShortcutIcon,
        positiveText: Int,
        onCancel: () -> Unit,
        onDone: (ShortcutIcon) -> Unit,
    ) {
        fun dp(v: Int) = activity.dp(v)

        val fallback = ShortcutIcon.default(title)
        var text = initial.text
        var color = initial.color
        fun current() = ShortcutIcon(text.ifBlank { fallback.text }, color)

        val preview = ImageView(activity).apply {
            clipToCircle()
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        // The visible part of an adaptive icon is its middle 72 of 108dp
        fun refresh() {
            val bitmap = current().bitmap(activity)
            val crop = bitmap.width * 18 / 108
            preview.setImageBitmap(
                Bitmap.createBitmap(bitmap, crop, crop, bitmap.width - 2 * crop, bitmap.height - 2 * crop)
            )
        }

        val input = EditText(activity).apply {
            setText(text)
            setSelection(this.text.length)
            hint = activity.getString(R.string.icon_text_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            isSingleLine = true
            gravity = Gravity.CENTER
            textSize = 22f
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable) {
                    val clipped = ShortcutIcon.clip(s.toString())
                    if (clipped != s.toString()) {
                        s.replace(0, s.length, clipped)
                        return
                    }
                    text = clipped.trim()
                    refresh()
                }
            })
        }

        val ring = activity.themeColor(android.R.attr.textColorPrimary)?.defaultColor ?: 0xFF202124.toInt()
        val outline = activity.themeColor(android.R.attr.textColorSecondary)?.defaultColor ?: 0xFF5F6368.toInt()
        val swatches = ArrayList<View>()
        fun paintSwatches() {
            ShortcutIcon.PALETTE.forEachIndexed { i, c ->
                swatches[i].background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    when {
                        c == color -> setStroke(dp(3), ring)
                        // "No background" is an empty dashed circle
                        c == ShortcutIcon.TRANSPARENT -> setStroke(dp(2), outline, dp(4).toFloat(), dp(3).toFloat())
                    }
                }
                // For TalkBack: "Color 3 of 12", selected or not
                val name = if (c == ShortcutIcon.TRANSPARENT) activity.getString(R.string.icon_no_background)
                    else activity.getString(R.string.icon_color, i + 1, ShortcutIcon.COLORS.size)
                swatches[i].contentDescription = name
                swatches[i].isSelected = c == color
            }
        }
        val grid = GridLayout(activity).apply { columnCount = 6 }
        for (c in ShortcutIcon.PALETTE) {
            val swatch = View(activity).apply {
                setOnClickListener {
                    color = c
                    paintSwatches()
                    refresh()
                }
            }
            swatches += swatch
            grid.addView(swatch, GridLayout.LayoutParams().apply {
                width = dp(36)
                height = dp(36)
                setMargins(dp(5), dp(5), dp(5), dp(5))
            })
        }
        paintSwatches()

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(16), dp(24), 0)
            addView(preview, LinearLayout.LayoutParams(dp(88), dp(88)))
            addView(input, LinearLayout.LayoutParams(dp(160), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            })
            addView(grid, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) })
        }
        refresh()

        var done = false
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton(positiveText) { _, _ ->
                done = true
                onDone(current())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.icon_reset, null)
            .setOnDismissListener { if (!done) onCancel() }
            .create()
        dialog.setOnShowListener {
            // Reset keeps the dialog open
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                color = fallback.color
                input.setText(fallback.text)
                input.setSelection(input.text.length)
                paintSwatches()
                refresh()
            }
        }
        dialog.show()
    }
}
