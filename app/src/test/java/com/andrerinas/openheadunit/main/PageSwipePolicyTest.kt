package com.andrerinas.openheadunit.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PageSwipePolicyTest {

    private val stride = 700f

    @Test
    fun `a release at rest projects nowhere`() {
        assertEquals(0f, PageSwipePolicy.project(0f), 0.001f)
    }

    @Test
    fun `a faster release projects further, in its own direction`() {
        val slow = PageSwipePolicy.project(500f)
        val fast = PageSwipePolicy.project(2000f)
        assertTrue(fast > slow && slow > 0f)
        assertTrue(PageSwipePolicy.project(-500f) < 0f)
    }

    @Test
    fun `past the edge the page moves less than the finger, and less the further it goes`() {
        val first = PageSwipePolicy.rubberBand(100f, stride)
        val second = PageSwipePolicy.rubberBand(200f, stride) - first
        assertTrue(first in 0f..100f)
        assertTrue(second < first)
        assertTrue(PageSwipePolicy.rubberBand(-100f, stride) < 0f)
    }

    @Test
    fun `inside the pages the finger is followed one to one`() {
        assertEquals(350f, PageSwipePolicy.banded(350f, max = stride, dimension = stride), 0.001f)
    }

    @Test
    fun `beyond either end the position resists`() {
        assertTrue(PageSwipePolicy.banded(-100f, max = stride, dimension = stride) > -100f)
        assertTrue(PageSwipePolicy.banded(stride + 100f, max = stride, dimension = stride) < stride + 100f)
    }

    @Test
    fun `a slow drag lands on the page nearest to where the finger let go`() {
        assertEquals(0, PageSwipePolicy.targetPage(300f, 0f, stride, current = 0, count = 3))
        assertEquals(1, PageSwipePolicy.targetPage(400f, 0f, stride, current = 0, count = 3))
    }

    @Test
    fun `a short flick still turns the page`() {
        assertEquals(1, PageSwipePolicy.targetPage(80f, 1500f, stride, current = 0, count = 3))
        assertEquals(0, PageSwipePolicy.targetPage(620f, -1500f, stride, current = 1, count = 3))
    }

    @Test
    fun `a hard flick turns one page, not three`() {
        assertEquals(1, PageSwipePolicy.targetPage(100f, 20000f, stride, current = 0, count = 4))
    }

    @Test
    fun `the first and the last page hold`() {
        assertEquals(0, PageSwipePolicy.targetPage(-50f, -3000f, stride, current = 0, count = 3))
        assertEquals(2, PageSwipePolicy.targetPage(2 * stride + 50f, 3000f, stride, current = 2, count = 3))
    }

    @Test
    fun `one page has nowhere to go`() {
        assertEquals(0, PageSwipePolicy.targetPage(100f, 3000f, stride, current = 0, count = 1))
    }

    @Test
    fun `only a flick earns the overshoot`() {
        assertEquals(PageSwipePolicy.DAMPING_SETTLE, PageSwipePolicy.damping(100f, flingThreshold = 400f), 0f)
        assertEquals(PageSwipePolicy.DAMPING_FLICK, PageSwipePolicy.damping(-900f, flingThreshold = 400f), 0f)
    }

    @Test
    fun `the spring reaches its page and comes to rest`() {
        val spring = PageSwipePolicy.Spring().apply { position = 0f; target = stride; velocity = 0f }
        repeat(120) { PageSwipePolicy.step(spring, 1f / 60f) }
        assertTrue(spring.isAtRest())
    }

    @Test
    fun `a settle without a flick never passes its page`() {
        val spring = PageSwipePolicy.Spring().apply { position = 0f; target = stride }
        var furthest = 0f
        repeat(120) {
            PageSwipePolicy.step(spring, 1f / 60f)
            furthest = maxOf(furthest, spring.position)
        }
        assertTrue(furthest <= stride + 0.5f)
    }

    @Test
    fun `the spring starts at the finger's speed`() {
        val spring = PageSwipePolicy.Spring().apply { position = 200f; target = stride; velocity = 3000f }
        PageSwipePolicy.step(spring, 1f / 60f)
        // At 3000 px/s one frame covers about 50 px before the spring has done much.
        assertTrue(abs(spring.position - 250f) < 20f)
    }

    @Test
    fun `a stalled frame does not throw the page past its spring`() {
        val spring = PageSwipePolicy.Spring().apply { position = 0f; target = stride; damping = PageSwipePolicy.DAMPING_FLICK }
        PageSwipePolicy.step(spring, 2f)
        assertTrue(spring.position < stride)
    }
}
