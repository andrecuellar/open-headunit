package com.andrerinas.openheadunit.main

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import com.andrerinas.openheadunit.utils.AppLog
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The launcher home's pages, side by side, one screen wide each.
 *
 * Written here rather than taken from ViewPager2 because the home needs very little of it and the
 * app still runs on Android 4.1: a handful of pages built by the fragment, a swipe that follows the
 * finger, and a settle that keeps the finger's speed ([PageSwipePolicy] holds the numbers).
 *
 * With one page it is a plain container and never takes a touch, so a home with nothing added
 * behaves exactly as before. A widget that scrolls sideways keeps the swipes that start on it.
 */
class HomePager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {

    /** Called once per change, when a swipe or a tap on the dots picks another page. */
    var onPageChanged: ((Int) -> Unit)? = null

    /** The space between two pages while they slide, so they read as separate panels. */
    var pageGap = 0
        set(value) {
            field = value
            requestLayout()
        }

    var currentPage = 0
        private set

    private val density = context.resources.displayMetrics.density
    private val configuration = ViewConfiguration.get(context)
    // A little more than the system slop, so a tap that wobbles is still a tap.
    private val touchSlop = max(configuration.scaledTouchSlop, (TOUCH_SLOP_DP * density).roundToInt())
    private val flingThreshold = configuration.scaledMinimumFlingVelocity * FLING_THRESHOLD_FACTOR
    private val maxFling = configuration.scaledMaximumFlingVelocity.toFloat()

    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var dragAnchorX = 0f
    private var dragStartScroll = 0f
    private var dragging = false

    private val spring = PageSwipePolicy.Spring()
    private var animating = false
    private var lastFrameNanos = 0L
    private val frame = object : Runnable {
        override fun run() = stepSpring()
    }

    private val stride: Int get() = width + pageGap
    private val maxScroll: Int get() = ((childCount - 1).coerceAtLeast(0)) * stride

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        val childWidth = MeasureSpec.makeMeasureSpec((width - paddingLeft - paddingRight).coerceAtLeast(0), MeasureSpec.EXACTLY)
        val childHeight = MeasureSpec.makeMeasureSpec((height - paddingTop - paddingBottom).coerceAtLeast(0), MeasureSpec.EXACTLY)
        for (i in 0 until childCount) getChildAt(i).measure(childWidth, childHeight)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val left = i * stride + paddingLeft
            child.layout(left, paddingTop, left + child.measuredWidth, paddingTop + child.measuredHeight)
        }
        if (!dragging && !animating) scrollTo(currentPage * stride, 0)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // A rotation or a new density: whatever was mid-flight lands on its page at the new size.
        stopSpring()
        scrollTo(currentPage * (w + pageGap), 0)
    }

    // Nothing to click on the pager itself; a tap that no page took ends here.
    override fun performClick(): Boolean = super.performClick()

    /** Pages scroll while a finger is down, so a press is not shown until it is clearly a tap. */
    override fun shouldDelayChildPressedState(): Boolean = childCount > 1

    /** Moves to [page], sliding there when [animate] and the unit's animations are on. */
    fun showPage(page: Int, animate: Boolean) {
        val target = HomePagesPolicy.clampPage(page, childCount)
        if (!animate || width == 0 || !animationsEnabled()) {
            stopSpring()
            setPage(target)
            scrollTo(target * stride, 0)
            return
        }
        settleOn(target, velocity = 0f)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (childCount < 2) return false
        trackVelocity(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                // A page still settling is caught where it is, not after it lands.
                if (animating) {
                    stopSpring()
                    startDrag(ev.x)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> if (!dragging && isHorizontalSwipe(ev)) {
                startDrag(ev.x)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                recycleVelocity()
            }
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (childCount < 2) return false
        trackVelocity(ev)
        when (ev.actionMasked) {
            // A touch that no page took: keep it, it may become a swipe.
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                if (animating) {
                    stopSpring()
                    startDrag(ev.x)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && isHorizontalSwipe(ev)) startDrag(ev.x)
                if (dragging) dragTo(ev.x)
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    // The lift can land past the last move, and that distance counts too.
                    dragTo(ev.x)
                    val tracker = velocityTracker
                    tracker?.computeCurrentVelocity(VELOCITY_UNITS_MS, maxFling)
                    // The finger moving left scrolls toward the later pages.
                    val velocity = -(tracker?.xVelocity ?: 0f)
                    val target = PageSwipePolicy.targetPage(scrollX.toFloat(), velocity, stride.toFloat(), currentPage, childCount)
                    dragging = false
                    settleOn(target, velocity)
                } else {
                    performClick()
                }
                recycleVelocity()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    settleOn((scrollX.toFloat() / stride).roundToInt().coerceIn(0, childCount - 1), 0f)
                }
                recycleVelocity()
            }
        }
        return true
    }

    /** The first move past the slop decides: sideways and not something a page wants to scroll. */
    private fun isHorizontalSwipe(ev: MotionEvent): Boolean {
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dx) < touchSlop || abs(dx) < abs(dy) * DIRECTION_RATIO) return false
        // A finger moving right shows what is to the left, so a child that can scroll left keeps it.
        val direction = if (dx > 0) -1 else 1
        return !childCanScroll(this, direction, downX.roundToInt() + scrollX, downY.roundToInt() + scrollY)
    }

    private fun childCanScroll(group: ViewGroup, direction: Int, x: Int, y: Int): Boolean {
        for (i in group.childCount - 1 downTo 0) {
            val child = group.getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            if (x < child.left || x >= child.right || y < child.top || y >= child.bottom) continue
            if (child.canScrollHorizontally(direction)) return true
            if (child is ViewGroup &&
                childCanScroll(child, direction, x - child.left + child.scrollX, y - child.top + child.scrollY)
            ) return true
        }
        return false
    }

    private fun dragTo(x: Float) {
        val raw = dragStartScroll + (dragAnchorX - x)
        scrollTo(PageSwipePolicy.banded(raw, maxScroll.toFloat(), width.toFloat()).roundToInt(), 0)
    }

    private fun startDrag(x: Float) {
        dragging = true
        dragAnchorX = x
        dragStartScroll = scrollX.toFloat()
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun settleOn(page: Int, velocity: Float) {
        val target = HomePagesPolicy.clampPage(page, childCount)
        setPage(target)
        if (!animationsEnabled()) {
            stopSpring()
            scrollTo(target * stride, 0)
            return
        }
        spring.position = scrollX.toFloat()
        spring.velocity = velocity
        spring.target = (target * stride).toFloat()
        spring.damping = PageSwipePolicy.damping(velocity, flingThreshold)
        if (spring.isAtRest()) {
            scrollTo(target * stride, 0)
            return
        }
        animating = true
        lastFrameNanos = System.nanoTime()
        ViewCompat.postOnAnimation(this, frame)
    }

    private fun stepSpring() {
        if (!animating) return
        val now = System.nanoTime()
        PageSwipePolicy.step(spring, (now - lastFrameNanos) / NANOS_PER_SECOND)
        lastFrameNanos = now
        if (spring.isAtRest()) {
            animating = false
            scrollTo(spring.target.roundToInt(), 0)
            return
        }
        scrollTo(spring.position.roundToInt(), 0)
        ViewCompat.postOnAnimation(this, frame)
    }

    private fun stopSpring() {
        if (!animating) return
        animating = false
        removeCallbacks(frame)
    }

    private fun setPage(page: Int) {
        if (page == currentPage) return
        currentPage = page
        AppLog.i("HomePager: showing page ${page + 1} of $childCount")
        onPageChanged?.invoke(page)
    }

    /**
     * A page under focus from a D-pad or a rotary knob is brought into view, so the driver is never
     * moving focus around a page they cannot see.
     */
    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        val index = child?.let { indexOfChild(it) } ?: -1
        if (index >= 0 && index != currentPage && !dragging && !isInTouchMode) showPage(index, animate = true)
    }

    override fun onDetachedFromWindow() {
        stopSpring()
        recycleVelocity()
        super.onDetachedFromWindow()
    }

    private fun trackVelocity(ev: MotionEvent) {
        // The pager's own events are not shifted by its scroll, so they are the finger on screen.
        (velocityTracker ?: VelocityTracker.obtain().also { velocityTracker = it }).addMovement(ev)
    }

    private fun recycleVelocity() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    /**
     * The unit's animator scale: switched off in the developer options, or by a reduced motion
     * setting on the units that have one. Then the page changes without sliding.
     */
    @Suppress("DEPRECATION")
    private fun animationsEnabled(): Boolean = try {
        val scale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        } else {
            android.provider.Settings.System.getFloat(context.contentResolver, android.provider.Settings.System.ANIMATOR_DURATION_SCALE, 1f)
        }
        scale > 0f
    } catch (e: Exception) {
        true
    }

    private companion object {
        const val TOUCH_SLOP_DP = 10f
        /** Sideways has to clearly win, so a scroll down a widget never turns the page. */
        const val DIRECTION_RATIO = 1.2f
        /** A release slower than a few times the system fling minimum settles without overshoot. */
        const val FLING_THRESHOLD_FACTOR = 4f
        const val VELOCITY_UNITS_MS = 1000
        const val NANOS_PER_SECOND = 1_000_000_000f
    }
}
