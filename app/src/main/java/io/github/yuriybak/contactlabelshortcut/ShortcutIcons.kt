package io.github.yuriybak.contactlabelshortcut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.icu.text.BreakIterator

/**
 * What a label shortcut's icon shows: a letter or an emoji on a colored background,
 * or on none ([TRANSPARENT]): then the text is drawn larger, and a letter gets a shadow
 * to stay readable on any wallpaper.
 */
data class ShortcutIcon(val text: String, val color: Int) {

    /** An adaptive icon: the launcher masks it into its own shape. */
    fun toIcon(context: Context): Icon = Icon.createWithAdaptiveBitmap(bitmap(context))

    /** The full 108dp adaptive layer; the launcher shows only the middle ~72dp of it. */
    fun bitmap(context: Context): Bitmap {
        val density = context.resources.displayMetrics.density
        val size = (108 * density).toInt()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val transparent = color == TRANSPARENT
        if (!transparent) canvas.drawColor(color)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = if (!transparent && isLight(color)) 0xDE000000.toInt() else Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = (if (transparent) 56 else 40) * density
            if (transparent) setShadowLayer(3 * density, 0f, density, 0x99000000.toInt())
        }
        // Keep the text inside the safe zone (a 66dp circle) whatever its length;
        // without a background nothing frames it, so it may fill more of the visible 72dp
        val maxWidth = (if (transparent) 62 else 50) * density
        val width = paint.measureText(text)
        if (width > maxWidth) paint.textSize *= maxWidth / width

        val y = size / 2f - (paint.descent() + paint.ascent()) / 2
        canvas.drawText(text, size / 2f, y, paint)
        return bitmap
    }

    companion object {
        /** At most this many characters (grapheme clusters: an emoji with modifiers is one). */
        const val MAX_LENGTH = 2

        val COLORS = intArrayOf(
            0xFF1A73E8.toInt(), // blue
            0xFF0B57D0.toInt(), // dark blue
            0xFF007B83.toInt(), // teal
            0xFF188038.toInt(), // green
            0xFF7CB342.toInt(), // light green
            0xFFF9AB00.toInt(), // yellow
            0xFFE37400.toInt(), // orange
            0xFFD93025.toInt(), // red
            0xFFD01884.toInt(), // pink
            0xFF9334E6.toInt(), // purple
            0xFF795548.toInt(), // brown
            0xFF5F6368.toInt(), // grey
        )

        /** No background: only the letter or emoji. */
        const val TRANSPARENT = 0

        /** The choices in the editor: the colors, then no background. */
        val PALETTE = COLORS + TRANSPARENT

        /** The first letter of the title on a color picked from the title. */
        fun default(title: String): ShortcutIcon {
            return ShortcutIcon(initialOf(title), colorFor(title, COLORS))
        }

        /** Cuts [text] to [MAX_LENGTH] characters without splitting an emoji. */
        fun clip(text: String): String {
            val chars = BreakIterator.getCharacterInstance()
            chars.setText(text)
            var end = 0
            repeat(MAX_LENGTH) {
                val next = chars.next()
                if (next == BreakIterator.DONE) return text
                end = next
            }
            return text.substring(0, end)
        }

        fun isLight(color: Int): Boolean =
            0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color) > 170

        private const val PREFS = "shortcut_icons"

        /** The icon saved for a shortcut, or null when it was never chosen. */
        fun saved(context: Context, shortcutId: String): ShortcutIcon? {
            val value = prefs(context).getString(shortcutId, null) ?: return null
            val sep = value.indexOf('|')
            if (sep < 0) return null
            val color = value.substring(0, sep).toLongOrNull(16)?.toInt() ?: return null
            return ShortcutIcon(value.substring(sep + 1), color)
        }

        fun save(context: Context, shortcutId: String, icon: ShortcutIcon) {
            val color = Integer.toHexString(icon.color)
            prefs(context).edit().putString(shortcutId, "$color|${icon.text}").apply()
        }

        /** Forgets the icons of shortcuts that are no longer pinned (the user removed them). */
        fun retainOnly(context: Context, shortcutIds: Set<String>) {
            val prefs = prefs(context)
            val stale = prefs.all.keys - shortcutIds
            if (stale.isEmpty()) return
            prefs.edit().apply { stale.forEach(::remove) }.apply()
        }

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
