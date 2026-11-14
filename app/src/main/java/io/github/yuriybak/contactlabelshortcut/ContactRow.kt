package io.github.yuriybak.contactlabelshortcut

import android.content.Context
import android.graphics.Outline
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

/**
 * One contact in the label list: avatar, then the name with the action buttons on a line
 * under it. Every row has the same shape, whatever the length of the name:
 * - the name is never cut (a very long one wraps);
 * - the buttons start under the name, so call and SMS line up in one column through the list;
 * - the "more" button is always at the end of the button line;
 * - the button line is reserved even before the buttons are loaded, so rows do not jump.
 */
class ContactRow(context: Context, private val style: ListStyle) : ViewGroup(context) {

    val avatar = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        clipToOutline = true
        visibility = if (style.showAvatars) View.VISIBLE else View.GONE
    }

    val name = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, style.nameTextSize)
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        setTextColor(attrs.getColorStateList(0))
        attrs.recycle()
    }

    /** Call, SMS and messenger buttons, from the start of the line. */
    val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    /** The "more" button, at the end of the line. */
    val trailing = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    private val avatarSize = context.dp(style.avatarSize)
    private val avatarGap = context.dp(style.avatarGap)
    private val actionLine = context.dp(style.actionSize)
    /** Buttons have padding around the icon; shifting them by it puts the first icon under the name. */
    private val actionsShift = (actionLine - context.dp(style.actionIconSize)) / 2
    private val minRowHeight = context.dp(style.rowMinHeight)

    init {
        val h = context.dp(style.rowPaddingHorizontal)
        val v = context.dp(style.rowPaddingVertical)
        setPaddingRelative(h, v, h / 2, v)
        addView(avatar)
        addView(name)
        addView(actions)
        addView(trailing)
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        background = attrs.getDrawable(0)
        attrs.recycle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)

        var textAvailable = width - paddingLeft - paddingRight
        var avatarHeight = 0
        if (avatar.visibility != View.GONE) {
            val exact = MeasureSpec.makeMeasureSpec(avatarSize, MeasureSpec.EXACTLY)
            avatar.measure(exact, exact)
            avatarHeight = avatarSize
            textAvailable -= avatarSize + avatarGap
        }
        textAvailable = max(textAvailable, 0)

        name.measure(MeasureSpec.makeMeasureSpec(textAvailable, MeasureSpec.AT_MOST), unspecified)
        actions.measure(unspecified, unspecified)
        trailing.measure(unspecified, unspecified)

        val content = max(avatarHeight, name.measuredHeight + actionLine)
        setMeasuredDimension(width, max(content + paddingTop + paddingBottom, minRowHeight))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l
        val height = b - t
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        val startPad = if (rtl) paddingRight else paddingLeft
        val endPad = if (rtl) paddingLeft else paddingRight
        val centerY = (paddingTop + height - paddingBottom) / 2

        // Places a child at a start-relative x, mirroring it for right-to-left languages
        fun place(child: View, x: Int, y: Int) {
            val left = if (rtl) width - x - child.measuredWidth else x
            child.layout(left, y, left + child.measuredWidth, y + child.measuredHeight)
        }

        var textStart = startPad
        if (avatar.visibility != View.GONE) {
            place(avatar, startPad, centerY - avatarSize / 2)
            textStart += avatarSize + avatarGap
        }

        val top = centerY - (name.measuredHeight + actionLine) / 2
        place(name, textStart, top)
        val lineTop = top + name.measuredHeight
        place(actions, textStart - actionsShift, lineTop + (actionLine - actions.measuredHeight) / 2)
        place(trailing, width - endPad - trailing.measuredWidth, lineTop + (actionLine - trailing.measuredHeight) / 2)
    }
}
