package com.andrerinas.openheadunit.main

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * A hosted widget that still answers a long press. Most widgets fill their box with their own tap
 * target, so the long press never reached the home and a widget like a clock could not be removed:
 * the press opened the clock app instead. Launchers watch for the long press above the widget, and
 * so does this. Once it fires, the widget is told the touch was cancelled, so it does not also
 * take it as a tap.
 */
class HomeWidgetHostView(context: Context) : AppWidgetHostView(context) {

    /**
     * What a long press on the widget does. A callback rather than a long click listener: that
     * would make this view long-clickable, and its own touch handling would then fire a second
     * long press wherever the widget leaves a gap.
     */
    var onLongPress: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private var widgetCancelled = false

    private val longPress = Runnable {
        val action = onLongPress ?: return@Runnable
        longPressed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        action()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                widgetCancelled = false
                downX = ev.x
                downY = ev.y
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE ->
                if (abs(ev.x - downX) > touchSlop || abs(ev.y - downY) > touchSlop) removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        if (!longPressed) return super.dispatchTouchEvent(ev)
        if (!widgetCancelled) {
            widgetCancelled = true
            val cancel = MotionEvent.obtain(ev)
            cancel.action = MotionEvent.ACTION_CANCEL
            super.dispatchTouchEvent(cancel)
            cancel.recycle()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }
}
