package com.andrerinas.openheadunit.main

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The feel of a swipe between home pages, kept out of the view so it is a unit test.
 *
 * - While the finger is down the page follows it one to one, and past the first or the last page it
 *   follows less and less instead of stopping dead ([banded]).
 * - On release the page lands where the swipe was heading, not only where the finger stopped:
 *   the release speed is projected forward the way a scroll decelerates ([targetPage]).
 * - The page then settles on a spring that starts at the finger's speed ([step]), so there is no
 *   seam between dragging and animating, and a finger that grabs it again takes over from wherever
 *   it is.
 */
object PageSwipePolicy {

    /** How quickly a released page slows down, per millisecond. Close to a normal scroll's feel. */
    const val DECELERATION_RATE = 0.998f

    /** How hard the edge pulls back. Lower is stiffer. */
    const val RUBBER_BAND = 0.55f

    /** Seconds the spring takes to reach the page, roughly. Not a duration: it has none. */
    const val RESPONSE_S = 0.38f

    /** No overshoot when nothing threw the page. */
    const val DAMPING_SETTLE = 1.0f

    /** A little overshoot after a flick, which the swipe itself earned. */
    const val DAMPING_FLICK = 0.85f

    /** Below this the spring is at rest: half a pixel away and slower than five pixels a second. */
    private const val REST_DISTANCE_PX = 0.5f
    private const val REST_SPEED_PX_S = 5f

    /** Long frames are cut to this, so a stall does not throw the spring past its page. */
    const val MAX_FRAME_S = 0.032f
    private const val SUBSTEPS = 4

    /** How far a release at [velocity] pixels a second would carry the page if nothing caught it. */
    fun project(velocity: Float, decelerationRate: Float = DECELERATION_RATE): Float =
        velocity / 1000f * decelerationRate / (1f - decelerationRate)

    /** The distance an [overshoot] past the edge actually moves the page: always less, never more. */
    fun rubberBand(overshoot: Float, dimension: Float, constant: Float = RUBBER_BAND): Float {
        if (dimension <= 0f) return 0f
        val sign = if (overshoot < 0f) -1f else 1f
        val o = abs(overshoot)
        return sign * (o * dimension * constant) / (dimension + constant * o)
    }

    /** The scroll position for a finger that would put the pages at [raw], between 0 and [max]. */
    fun banded(raw: Float, max: Float, dimension: Float): Float = when {
        raw < 0f -> rubberBand(raw, dimension)
        raw > max -> max + rubberBand(raw - max, dimension)
        else -> raw
    }

    /**
     * The page a release lands on: the nearest one to where the swipe was heading, never more than
     * one page from the one it started on, so a hard flick does not skip a page the driver wanted.
     *
     * @param scroll where the pages are now, in pixels from the first one.
     * @param velocity the scroll speed at release, pixels a second, positive toward later pages.
     * @param stride the distance from one page to the next.
     */
    fun targetPage(scroll: Float, velocity: Float, stride: Float, current: Int, count: Int): Int {
        if (count <= 1 || stride <= 0f) return 0
        val projected = scroll + project(velocity)
        val nearest = (projected / stride).roundToInt()
        return nearest.coerceIn(current - 1, current + 1).coerceIn(0, count - 1)
    }

    fun damping(velocity: Float, flingThreshold: Float): Float =
        if (abs(velocity) >= flingThreshold) DAMPING_FLICK else DAMPING_SETTLE

    /** A spring's position and speed, reused across frames so the animation allocates nothing. */
    class Spring {
        var position = 0f
        var velocity = 0f
        var target = 0f
        var damping = DAMPING_SETTLE
        var response = RESPONSE_S

        fun isAtRest(): Boolean =
            abs(position - target) < REST_DISTANCE_PX && abs(velocity) < REST_SPEED_PX_S
    }

    /** Moves [spring] forward [dt] seconds, in small steps so a slow frame stays stable. */
    fun step(spring: Spring, dt: Float) {
        val frame = dt.coerceIn(0f, MAX_FRAME_S)
        val omega = 2f * PI.toFloat() / spring.response
        val stiffness = omega * omega
        val friction = 2f * spring.damping * omega
        val h = frame / SUBSTEPS
        repeat(SUBSTEPS) {
            val accel = -stiffness * (spring.position - spring.target) - friction * spring.velocity
            spring.velocity += accel * h
            spring.position += spring.velocity * h
        }
    }
}
