package com.andrerinas.openheadunit.main

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetStripPolicyTest {

    // The numbers a 1024x600 head unit leaves: 560dp of column, a 120dp apps row, a 20dp gap and
    // a main button that needs 296dp.
    private val roomOnA1024x600 = 560 - 120 - 20 - 296

    @Test
    fun `a widget taller than the room gets the room`() {
        assertEquals(roomOnA1024x600, WidgetStripPolicy.height(roomOnA1024x600, 72, 220, 360))
    }

    @Test
    fun `a short widget keeps its own height`() {
        assertEquals(110, WidgetStripPolicy.height(roomOnA1024x600, 72, 220, 110))
    }

    @Test
    fun `a very tall widget is capped so it does not own the screen`() {
        assertEquals(220, WidgetStripPolicy.height(600, 72, 220, 520))
    }

    @Test
    fun `a room too small for any widget leaves the row out`() {
        assertEquals(0, WidgetStripPolicy.height(40, 72, 220, 110))
    }

    @Test
    fun `with no widgets placed there is no row`() {
        assertEquals(0, WidgetStripPolicy.height(600, 72, 220, 0))
    }

    @Test
    fun `a negative room from a screen mid layout leaves the row out`() {
        assertEquals(0, WidgetStripPolicy.height(-300, 72, 220, 110))
    }

    @Test
    fun `the room takes both dividers out of the column`() {
        // 560dp column, a main button that needs 296dp and a 120dp apps row, with 20dp gaps.
        assertEquals(104, WidgetStripPolicy.roomFor(560, 296, 120, 20))
    }
}
