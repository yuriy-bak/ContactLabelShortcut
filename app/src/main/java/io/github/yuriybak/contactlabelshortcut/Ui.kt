package io.github.yuriybak.contactlabelshortcut

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider

/** Small view helpers shared by the screens (the app has no AndroidX). */

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

/** A color of the current theme, such as android.R.attr.textColorPrimary. */
fun Context.themeColor(attr: Int): ColorStateList? {
    val a = obtainStyledAttributes(intArrayOf(attr))
    try {
        return a.getColorStateList(0)
    } finally {
        a.recycle()
    }
}

/** A drawable of the current theme, such as android.R.attr.selectableItemBackground. */
fun Context.themeDrawable(attr: Int): Drawable? {
    val a = obtainStyledAttributes(intArrayOf(attr))
    try {
        return a.getDrawable(0)
    } finally {
        a.recycle()
    }
}

/** Clips a view to a circle: avatars, icon previews. */
object CircleOutline : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setOval(0, 0, view.width, view.height)
    }
}

fun View.clipToCircle() {
    outlineProvider = CircleOutline
    clipToOutline = true
}

/** The first letter (or digit) of a name, upper case, or "?". */
fun initialOf(name: String): String =
    name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "?"

/** A color picked from [palette] by the name, the same every time. */
fun colorFor(name: String, palette: IntArray): Int = palette[(name.hashCode() and Int.MAX_VALUE) % palette.size]
