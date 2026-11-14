package io.github.yuriybak.contactlabelshortcut

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.StaticLayout
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

/** What a swipe does: drawn under the row as it moves, run when the row is let go past the threshold. */
class SwipeTarget(val icon: Drawable?, val iconSize: Int, val color: Int, val run: () -> Unit)

/**
 * One contact in the label list: avatar, the name on the first line, the action buttons on
 * the second line at the end, the "more" button last. Every row has the same shape:
 * - the button line is reserved even before the buttons are loaded, so rows do not jump;
 * - the name is never cut: what does not fit on the first line goes on the second line,
 *   before the buttons, and if it does not fit there either, the row grows.
 * With [ListStyle.moreOnly] there is no button line: the "more" button is on the name line.
 *
 * A horizontal swipe moves the row and shows [SwipeTarget] under it; touch handling costs
 * nothing until a finger moves sideways.
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

    /** The first line of the name. */
    private val name = nameView(context, style.nameTextSize)

    /** The rest of a long name, on the second line before the buttons. */
    private val nameRest = nameView(context, style.nameTextSize)

    /** The contact name; split between [name] and [nameRest] when measured. */
    var nameText: CharSequence = ""
        set(value) {
            if (field.toString() == value.toString()) return
            field = value
            splitWidth = -1
            requestLayout()
        }
    private var splitWidth = -1

    /** Call, SMS and messenger buttons, before [trailing]. */
    val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    /** The "more" button, at the end of the line. */
    val trailing = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    private val avatarSize = context.dp(style.avatarSize)
    private val avatarGap = context.dp(style.avatarGap)
    private val actionLine = context.dp(style.actionSize)
    private val restGap = context.dp(8)
    /** Narrower than this, the rest of the name gets lines of its own above the buttons. */
    private val minRestWidth = context.dp(64)
    private var restBeside = true
    private val minRowHeight = context.dp(style.rowMinHeight)

    /** Asked when a swipe starts (to the right if true); null means this row does not swipe that way. */
    var swipe: ((toRight: Boolean) -> SwipeTarget?)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val iconMargin = context.dp(style.rowPaddingHorizontal + 8)
    private val swipePaint = Paint()
    private var downX = 0f
    private var downY = 0f
    private var tracking = false
    private var swiping = false
    private var toRight = false
    private var armed = false
    private var target: SwipeTarget? = null
    private var offset = 0f
    private var settleAnimator: ValueAnimator? = null

    init {
        val h = context.dp(style.rowPaddingHorizontal)
        val v = context.dp(style.rowPaddingVertical)
        setPaddingRelative(h, v, h / 2, v)
        addView(avatar)
        addView(name)
        addView(nameRest)
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

        actions.measure(unspecified, unspecified)
        trailing.measure(unspecified, unspecified)

        if (style.moreOnly) {
            // One line: the name (wrapping if it must) and the "more" button, whose place is kept
            if (name.text.toString() != nameText.toString()) name.text = nameText
            nameRest.text = ""
            splitWidth = -1
            val nameWidth = max(textAvailable - actionLine - restGap, 0)
            name.measure(MeasureSpec.makeMeasureSpec(nameWidth, MeasureSpec.AT_MOST), unspecified)
            nameRest.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY))
            val content = maxOf(avatarHeight, name.measuredHeight, actionLine)
            setMeasuredDimension(width, max(content + paddingTop + paddingBottom, context.dp(ONE_LINE_MIN_HEIGHT)))
            return
        }

        split(textAvailable)
        name.measure(MeasureSpec.makeMeasureSpec(textAvailable, MeasureSpec.AT_MOST), unspecified)
        val besideWidth = textAvailable - actions.measuredWidth - trailing.measuredWidth - restGap
        restBeside = nameRest.text.isEmpty() || besideWidth >= minRestWidth
        val restWidth = if (restBeside) max(besideWidth, 0) else textAvailable
        nameRest.measure(MeasureSpec.makeMeasureSpec(restWidth, MeasureSpec.AT_MOST), unspecified)

        val rest = if (nameRest.text.isEmpty()) 0 else nameRest.measuredHeight
        val second = if (restBeside) max(actionLine, rest) else rest + actionLine
        val content = max(avatarHeight, name.measuredHeight + second)
        setMeasuredDimension(width, max(content + paddingTop + paddingBottom, minRowHeight))
    }

    /** Puts on the first line as much of the name as fits in [width], the rest into [nameRest]. */
    private fun split(width: Int) {
        if (width == splitWidth) return
        splitWidth = width
        val text = nameText
        val end = if (width <= 0 || name.paint.measureText(text, 0, text.length) <= width) {
            text.length
        } else {
            StaticLayout.Builder.obtain(text, 0, text.length, name.paint, width).build().getLineEnd(0)
        }
        name.text = text.subSequence(0, end)
        nameRest.text = text.subSequence(end, text.length).trim()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = track(ev) || super.onInterceptTouchEvent(ev)

    override fun onTouchEvent(event: MotionEvent): Boolean = track(event) || super.onTouchEvent(event)

    /** Follows a swipe; returns true while it owns the touch. */
    private fun track(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                swiping = false
                tracking = swipe != null && settleAnimator?.isRunning != true
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swiping) {
                    if (!tracking) return false
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    if (abs(dy) > touchSlop) {
                        tracking = false
                        return false
                    }
                    if (abs(dx) <= touchSlop || abs(dx) < abs(dy) * 2) return false
                    val t = swipe?.invoke(dx > 0)
                    if (t == null) {
                        tracking = false
                        return false
                    }
                    target = t
                    toRight = dx > 0
                    swiping = true
                    armed = false
                    downX = ev.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                    // Drop the press state and the pending long press of the row
                    val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
                    super.onTouchEvent(cancel)
                    cancel.recycle()
                }
                val dx = ev.x - downX
                setOffset(if (toRight) max(dx, 0f) else minOf(dx, 0f))
                val nowArmed = abs(offset) >= width * SWIPE_THRESHOLD
                if (nowArmed != armed) {
                    armed = nowArmed
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracking = false
                if (!swiping) return false
                swiping = false
                val run = if (armed && ev.actionMasked == MotionEvent.ACTION_UP) target?.run else null
                settle()
                run?.invoke()
                return true
            }
        }
        return swiping
    }

    private fun setOffset(value: Float) {
        offset = value
        for (i in 0 until childCount) getChildAt(i).translationX = value
        invalidate()
    }

    private fun settle() {
        settleAnimator = ValueAnimator.ofFloat(offset, 0f).apply {
            duration = 180
            addUpdateListener { setOffset(it.animatedValue as Float) }
            start()
        }
    }

    /** For a recycled row: drop a swipe that was in progress. */
    fun resetSwipe() {
        settleAnimator?.cancel()
        settleAnimator = null
        swiping = false
        tracking = false
        if (offset != 0f) setOffset(0f)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val t = target
        if (offset != 0f && t != null) {
            swipePaint.color = t.color
            swipePaint.alpha = if (armed) 255 else 140
            val h = height.toFloat()
            if (offset > 0) canvas.drawRect(0f, 0f, offset, h, swipePaint)
            else canvas.drawRect(width + offset, 0f, width.toFloat(), h, swipePaint)
            val icon = t.icon
            if (icon != null && abs(offset) > iconMargin + t.iconSize) {
                val top = (height - t.iconSize) / 2
                val left = if (offset > 0) iconMargin else width - iconMargin - t.iconSize
                icon.setBounds(left, top, left + t.iconSize, top + t.iconSize)
                icon.draw(canvas)
            }
        }
        super.dispatchDraw(canvas)
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

        if (style.moreOnly) {
            place(name, textStart, centerY - name.measuredHeight / 2)
            place(nameRest, textStart, centerY)
            val trailingStart = width - endPad - trailing.measuredWidth
            place(trailing, trailingStart, centerY - trailing.measuredHeight / 2)
            place(actions, trailingStart - actions.measuredWidth, centerY - actions.measuredHeight / 2)
            return
        }

        val hasRest = nameRest.text.isNotEmpty()
        val rest = if (hasRest) nameRest.measuredHeight else 0
        val second = if (restBeside) max(actionLine, rest) else rest + actionLine
        val top = centerY - (name.measuredHeight + second) / 2
        place(name, textStart, top)
        var lineTop = top + name.measuredHeight
        if (hasRest) {
            // Beside the buttons, one line of the rest is centered on them
            place(nameRest, textStart, if (restBeside) lineTop + max(0, (actionLine - rest) / 2) else lineTop)
        } else {
            place(nameRest, textStart, lineTop)
        }
        if (!restBeside) lineTop += rest
        val trailingStart = width - endPad - trailing.measuredWidth
        place(trailing, trailingStart, lineTop + (actionLine - trailing.measuredHeight) / 2)
        place(actions, trailingStart - actions.measuredWidth, lineTop + (actionLine - actions.measuredHeight) / 2)
    }

    private companion object {
        /** Part of the row width to swipe for the action to run. */
        const val SWIPE_THRESHOLD = 0.3f

        /** Row height without a button line, in dp. */
        const val ONE_LINE_MIN_HEIGHT = 56
    }
}

private fun nameView(context: Context, textSize: Float) = TextView(context).apply {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, textSize)
    val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
    setTextColor(attrs.getColorStateList(0))
    attrs.recycle()
}
