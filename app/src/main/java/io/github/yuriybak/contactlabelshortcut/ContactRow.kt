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
import kotlin.math.ceil
import kotlin.math.max

/**
 * One contact in the label list: avatar, name and action buttons.
 *
 * The name is never cut: if the name and the buttons do not fit on one line,
 * the buttons move to a line of their own under the name, aligned to the end.
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

    val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    private val avatarSize = context.dp(style.avatarSize)
    private val avatarGap = context.dp(style.avatarGap)
    private val nameActionsGap = context.dp(style.nameActionsGap)
    private val minRowHeight = context.dp(style.rowMinHeight)
    private var actionsInline = true

    init {
        val h = context.dp(style.rowPaddingHorizontal)
        val v = context.dp(style.rowPaddingVertical)
        setPaddingRelative(h, v, h / 2, v)
        addView(avatar)
        addView(name)
        addView(actions)
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

        val hasActions = actions.childCount > 0
        val actionsWidth: Int
        val actionsHeight: Int
        if (hasActions) {
            actions.measure(unspecified, unspecified)
            actionsWidth = actions.measuredWidth
            actionsHeight = actions.measuredHeight
        } else {
            actions.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY),
            )
            actionsWidth = 0
            actionsHeight = 0
        }

        val nameWidth = ceil(name.paint.measureText(name.text.toString())).toInt() +
            name.paddingLeft + name.paddingRight
        val inlineRoom = textAvailable - (if (hasActions) actionsWidth + nameActionsGap else 0)
        actionsInline = !hasActions || nameWidth <= inlineRoom

        val nameRoom = if (actionsInline) max(inlineRoom, 0) else textAvailable
        name.measure(MeasureSpec.makeMeasureSpec(nameRoom, MeasureSpec.AT_MOST), unspecified)

        val content = if (actionsInline) {
            maxOf(avatarHeight, name.measuredHeight, actionsHeight)
        } else {
            max(avatarHeight, name.measuredHeight + actionsHeight)
        }
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
        val actionsStart = width - endPad - actions.measuredWidth

        if (actionsInline) {
            place(name, textStart, centerY - name.measuredHeight / 2)
            place(actions, actionsStart, centerY - actions.measuredHeight / 2)
        } else {
            val top = centerY - (name.measuredHeight + actions.measuredHeight) / 2
            place(name, textStart, top)
            place(actions, actionsStart, top + name.measuredHeight)
        }
    }
}
