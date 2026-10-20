package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class GuidanceBoundaryTest {
    private val out = ShortArray(960)
    private fun feed(bank: AdaptivePcmBuffer, frames: Int, now: Long, value: Short) {
        bank.noteArrival(now, frames)
        bank.write(ShortArray(frames * 2) { value }, frames * 2, now)
    }

    @Test fun `sparse next prompt cannot borrow the previous stopped tails running state`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 16)
        feed(bank, 960, 0, 10000)
        bank.finish()
        bank.render(out, 0) // 10ms of the completed first prompt remains.
        feed(bank, 3072, 10, -10000)
        assertTrue(bank.render(out, 10))
        assertEquals(10000, out.last().toInt()) // Preserve the final real block of A.
        for (t in 20L..300L step 10) bank.render(out, t)
        assertEquals(3072, bank.depthFrames()) // B is still banked, never replayed as PLC.
        assertEquals(0L, bank.concealedFrames)
        assertEquals(0L, bank.rebanks)
        bank.finish()
        repeat(10) { bank.render(out, 310 + it * 10L) }
        assertTrue(bank.isIdle())
        assertEquals(0L, bank.droppedFrames)
    }

    @Test fun `arrival before PCM cannot turn a partial stopped tail into a shortage`() {
        for (remaining in listOf(0, 120, 480)) {
            val bank = AdaptivePcmBuffer(latencyMultiplier = 16)
            feed(bank, 480 + remaining, 0, 10000)
            // Let A start before Stop, including the case Stop arrives at depth zero.
            bank.render(out, 450)
            bank.finish()
            bank.noteArrival(460, 3072)
            bank.render(out, 460)
            assertEquals(0L, bank.concealedFrames)
            assertEquals(0L, bank.rebanks)
            bank.write(ShortArray(6144) { -10000 }, 6144, 470)
            bank.render(out, 480)
            assertEquals(3072, bank.depthFrames())
            bank.finish()
            repeat(10) { bank.render(out, 490 + it * 10L) }
            assertTrue(bank.isIdle())
        }
    }

    @Test fun `complete or fully banked next prompt continues in sample order`() {
        for (completed in listOf(false, true)) {
            val bank = AdaptivePcmBuffer(latencyMultiplier = 16)
            feed(bank, 600, 0, 10000)
            bank.finish(); bank.render(out, 0)
            val frames = if (completed) 480 else 8 * 3072
            if (completed) feed(bank, frames, 10, -10000)
            else repeat(8) { feed(bank, 3072, 10, -10000) }
            assertTrue(completed || bank.depthFrames() - 120 >= bank.targetFrames())
            if (completed) bank.finish()
            assertTrue(bank.render(out, 10))
            assertEquals(10000, out[238].toInt())
            assertEquals(-10000, out[240].toInt())
            assertEquals(frames - 360, bank.depthFrames())
            assertEquals(0L, bank.concealedFrames)
        }
    }

    @Test fun `a completed prefix drains even if it had not started before the next arrival`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 16)
        feed(bank, 960, 0, 10000); bank.finish()
        feed(bank, 3072, 10, -10000)
        assertTrue(bank.render(out, 10))
        assertEquals(10000, out.last().toInt())
        assertTrue(bank.render(out, 20))
        bank.render(out, 30)
        assertFalse(bank.render(out, 40))
        assertEquals(3072, bank.depthFrames())
        assertEquals(0L, bank.concealedFrames)
    }

    @Test fun `several completed prompts ahead of a sparse prompt retain sample order`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 16)
        feed(bank, 600, 0, 10000); bank.finish(); bank.render(out, 0)
        feed(bank, 480, 10, 20000); bank.finish()
        feed(bank, 480, 11, -10000)
        bank.render(out, 20)
        assertEquals(10000, out[238].toInt())
        assertEquals(20000, out[240].toInt())
        bank.render(out, 30)
        assertEquals(20000, out[238].toInt())
        assertEquals(480, bank.depthFrames())
        assertFalse(bank.render(out, 40))
        bank.finish(); bank.render(out, 50)
        assertEquals(-10000, out.last().toInt())
        assertEquals(0L, bank.droppedFrames)
        assertEquals(0L, bank.concealedFrames)
    }

    @Test fun `physical overflow retires a discarded prefix without stale boundary reads`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 16)
        feed(bank, 960, 0, 10000); bank.finish(); bank.render(out, 0)
        feed(bank, 3072, 10, -10000)
        // A decoder catch-up batch can overflow physical storage. Once A is gone, its
        // boundary must not limit or index into B's replacement samples.
        bank.write(ShortArray(72000 * 2) { 20000 }, 72000 * 2, 20)
        assertEquals(3552L, bank.droppedFrames)
        assertTrue(bank.render(out, 20))
        assertEquals(20000, out.last().toInt())
        bank.finish()
        repeat(151) { bank.render(out, 30 + it * 10L) }
        assertTrue(bank.isIdle())
        assertEquals(0L, bank.concealedFrames)
    }

    @Test fun `16x prompt resumes during a draining tail with a delayed second packet`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 16)
        repeat(7) { feed(bank, 3072, 0, 10000) }
        for (time in 0..5000) {
            val now = time.toLong()
            if (time in 64..1984 && time % 64 == 0) feed(bank, 3072, now, 10000)
            if (time == 2000) bank.finish()
            if (time == 2300 || (time in 2600..3496 && (time - 2600) % 64 == 0)) {
                feed(bank, 3072, now, -10000)
            }
            if (time == 3500) bank.finish()
            if (time % 10 == 0) bank.render(out, now, 7680)
        }
        assertTrue(bank.isIdle())
        assertEquals(0L, bank.concealedFrames)
        assertEquals(0L, bank.rebanks)
        assertEquals(0L, bank.droppedFrames)
    }
}
