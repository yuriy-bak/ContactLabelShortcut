package io.github.yuriybak.contactlabelshortcut

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.text.StaticLayout
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.max

/** What a swipe does: drawn under the row as it moves, run when the row is let go past the threshold. */
class SwipeTarget(val icon: Drawable?, val iconSize: Int, val color: Int, val run: () -> Unit)

/**
 * A button of a row: our own icon ([iconRes], tinted) or an app's ([appIcon], as it is).
 * [onLongClick] null: the button has no long press.
 */
class RowAction(
    val iconRes: Int,
    val appIcon: Drawable?,
    val description: CharSequence,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)?,
)

/**
 * One contact in the label list: avatar, the name on the first line, the action buttons on
 * the second line at the end, the "more" button last. Every row has the same shape:
 * - the button line is reserved even before the buttons are loaded, so rows do not jump;
 * - the name is never cut: what does not fit on the first line goes on the second line,
 *   before the buttons, and if it does not fit there either, the row grows.
 * A row with few buttons (at most [ListStyle.inlineButtons], or only "more" with
 * [ListStyle.moreOnly]) is one line: the name and the buttons beside it. This depends only on
 * the number of buttons, never on the length of the name.
 *
 * A horizontal swipe moves the row and shows [SwipeTarget] under it; touch handling costs
 * nothing until a finger moves sideways.
 *
 * The button views are kept when the row is reused for another contact ([setActions]), so
 * scrolling creates no views.
 */
// Created only in code, never inflated: no XML constructors. Taps go through super.onTouchEvent,
// which calls performClick; swipes have no accessibility path of their own, but everything they do
// is also in the buttons and in the menu of all actions.
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class ContactRow(context: Context, private val style: ListStyle) : ViewGroup(context) {

    val avatar = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToCircle()
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
    private val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    /** The "more" button, at the end of the line. */
    private val trailing = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

    /** Button views of [actions], reused; those past [actionCount] are hidden. */
    private val actionSlots = ArrayList<ButtonSlot>()
    private var actionCount = 0
    private var trailingSlot: ButtonSlot? = null
    /** A button's touch target, also the height of the button line. */
    private val actionSize = context.dp(style.actionSize)
    private val ownIconSize = context.dp(style.actionIconSize)
    private val appIconSize = context.dp(style.appIconSize)
    private val iconTint = context.themeColor(android.R.attr.textColorSecondary)
        ?: android.content.res.ColorStateList.valueOf(0xFF757575.toInt())

    private val avatarSize = context.dp(style.avatarSize)
    private val avatarGap = context.dp(style.avatarGap)
    private val restGap = context.dp(8)
    /** Narrower than this, the rest of the name gets lines of its own above the buttons. */
    private val minRestWidth = context.dp(64)
    private var restBeside = true
    private var inline = false

    /** The buttons of this contact are loaded; until then the button line is kept. */
    var buttonsKnown = false
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }
    private val minRowHeight = context.dp(style.rowMinHeight)
    private val oneLineMinHeight = context.dp(ONE_LINE_MIN_HEIGHT)

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
        background = context.themeDrawable(android.R.attr.selectableItemBackground)
    }

    /** A button view and what it shows: an app icon (the drawable it was copied from) or our icon. */
    private class ButtonSlot(val view: ImageView) {
        var source: Drawable? = null
        var res = 0
    }

    /** Shows [list] as the buttons and [more] (or nothing) at the end; the views are reused. */
    fun setActions(list: List<RowAction>, more: RowAction?) {
        while (actionSlots.size < list.size) {
            val slot = ButtonSlot(newButton())
            actionSlots += slot
            actions.addView(slot.view)
        }
        for ((i, slot) in actionSlots.withIndex()) {
            if (i < list.size) show(slot, list[i]) else hide(slot)
        }
        if (more != null) {
            val slot = trailingSlot ?: ButtonSlot(newButton()).also {
                trailingSlot = it
                trailing.addView(it.view)
            }
            show(slot, more)
        } else {
            trailingSlot?.let(::hide)
        }
        if (actionCount != list.size) {
            actionCount = list.size
            requestLayout()
        }
    }

    private fun newButton() = ImageView(context).apply {
        layoutParams = LinearLayout.LayoutParams(actionSize, actionSize)
        scaleType = ImageView.ScaleType.FIT_CENTER
        background = context.themeDrawable(android.R.attr.selectableItemBackgroundBorderless)
    }

    private fun show(slot: ButtonSlot, action: RowAction) {
        val view = slot.view
        val app = action.appIcon
        val pad = ((actionSize - (if (app != null) appIconSize else ownIconSize)) / 2).coerceAtLeast(0)
        if (view.paddingLeft != pad) view.setPadding(pad, pad, pad, pad)
        if (app != null) {
            if (slot.source !== app) {
                // One drawable can't be shown by several views at once
                view.imageTintList = null
                view.setImageDrawable(app.constantState?.newDrawable(resources) ?: app)
                slot.source = app
                slot.res = 0
            }
        } else if (slot.res != action.iconRes) {
            view.setImageResource(action.iconRes)
            view.imageTintList = iconTint
            slot.source = null
            slot.res = action.iconRes
        }
        view.contentDescription = action.description
        view.setOnClickListener { action.onClick() }
        val long = action.onLongClick
        if (long != null) {
            view.setOnLongClickListener { long(); true }
        } else {
            view.setOnLongClickListener(null)
            view.isLongClickable = false
        }
        view.visibility = View.VISIBLE
    }

    private fun hide(slot: ButtonSlot) {
        if (slot.view.visibility == View.GONE) return
        slot.view.visibility = View.GONE
        slot.view.setOnClickListener(null)
        slot.view.setOnLongClickListener(null)
        requestLayout()
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

        val buttons = actionCount + (if (trailingSlot?.view?.visibility == View.VISIBLE) 1 else 0)
        inline = style.moreOnly || (buttonsKnown && style.inlineButtons > 0 && buttons <= style.inlineButtons)
        if (inline) {
            // One line: the name (wrapping if it must) and the buttons; the place of "more" is kept
            if (name.text.toString() != nameText.toString()) name.text = nameText
            nameRest.text = ""
            splitWidth = -1
            val buttonsWidth = actions.measuredWidth + max(trailing.measuredWidth, if (style.moreOnly) actionSize else 0)
            val nameWidth = max(textAvailable - buttonsWidth - restGap, 0)
            name.measure(MeasureSpec.makeMeasureSpec(nameWidth, MeasureSpec.AT_MOST), unspecified)
            nameRest.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.EXACTLY))
            val content = maxOf(avatarHeight, name.measuredHeight, actionSize)
            setMeasuredDimension(width, max(content + paddingTop + paddingBottom, oneLineMinHeight))
            return
        }

        split(textAvailable)
        name.measure(MeasureSpec.makeMeasureSpec(textAvailable, MeasureSpec.AT_MOST), unspecified)
        val besideWidth = textAvailable - actions.measuredWidth - trailing.measuredWidth - restGap
        restBeside = nameRest.text.isEmpty() || besideWidth >= minRestWidth
        val restWidth = if (restBeside) max(besideWidth, 0) else textAvailable
        nameRest.measure(MeasureSpec.makeMeasureSpec(restWidth, MeasureSpec.AT_MOST), unspecified)

        val rest = if (nameRest.text.isEmpty()) 0 else nameRest.measuredHeight
        val second = if (restBeside) max(actionSize, rest) else rest + actionSize
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

        if (inline) {
            place(name, textStart, centerY - name.measuredHeight / 2)
            place(nameRest, textStart, centerY)
            val trailingStart = width - endPad - trailing.measuredWidth
            place(trailing, trailingStart, centerY - trailing.measuredHeight / 2)
            place(actions, trailingStart - actions.measuredWidth, centerY - actions.measuredHeight / 2)
            return
        }

        val hasRest = nameRest.text.isNotEmpty()
        val rest = if (hasRest) nameRest.measuredHeight else 0
        val second = if (restBeside) max(actionSize, rest) else rest + actionSize
        val top = centerY - (name.measuredHeight + second) / 2
        place(name, textStart, top)
        var lineTop = top + name.measuredHeight
        if (hasRest) {
            // Beside the buttons, one line of the rest is centered on them
            place(nameRest, textStart, if (restBeside) lineTop + max(0, (actionSize - rest) / 2) else lineTop)
        } else {
            place(nameRest, textStart, lineTop)
        }
        if (!restBeside) lineTop += rest
        val trailingStart = width - endPad - trailing.measuredWidth
        place(trailing, trailingStart, lineTop + (actionSize - trailing.measuredHeight) / 2)
        place(actions, trailingStart - actions.measuredWidth, lineTop + (actionSize - actions.measuredHeight) / 2)
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
    context.themeColor(android.R.attr.textColorPrimary)?.let(::setTextColor)
}
