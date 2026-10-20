package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class PcmRenderDeadlineTest {
    private fun PcmRenderDeadline.poll(now: Long, played: Long = 480, written: Long = 8160,
        realEnd: Long = written, epoch: Long = 0, realEpoch: Long = epoch,
        recovery: Boolean = false, work: Long = 0) =
        waitMs(now, epoch, written, played, realEnd, realEpoch, 7680, recovery, work)

    private fun running() = PcmRenderDeadline().also { it.poll(0, played = 0); it.poll(10) }

    @Test fun `cold output must fill its start threshold before any waiting`() {
        val gate = PcmRenderDeadline()
        assertEquals(0L, gate.poll(0, played = 0))
        assertEquals(0L, gate.poll(100, played = 0))
        assertEquals(10L, gate.poll(110))
    }

    @Test fun `frozen head exhausts its borrowed time without parking writes`() {
        val gate = running()
        assertEquals(10L, gate.poll(20))
        assertEquals(1L, gate.poll(89))
        assertEquals(0L, gate.poll(90))
        assertEquals(0L, gate.poll(10_000))
    }

    @Test fun `unknown clocks replacement replay and reset cannot borrow old reserve`() {
        assertEquals(0L, running().poll(20, played = -1, written = -1))
        assertEquals(0L, running().poll(20, epoch = 1, realEpoch = 0))
        assertEquals(0L, running().poll(20, recovery = true))
        assertEquals(0L, running().poll(20, played = 0))
        assertEquals(0L, running().poll(20, written = 100))
        assertEquals(0L, running().poll(20, realEnd = 9000))
    }

    @Test fun `warmup silence and ending frames do not count as real coverage`() {
        assertEquals(0L, running().poll(20, realEnd = -1))
        assertEquals(0L, running().poll(20, realEnd = 480))
        assertEquals(0L, running().poll(20, realEnd = 960))
    }

    @Test fun `guard retains device budget and grows with measured render cost`() {
        assertEquals(10L, running().poll(20, work = 89))
        assertEquals(0L, running().poll(20, work = 150))
        val small = PcmRenderDeadline()
        small.waitMs(0, 0, 960, 0, 960, 0, 960, false, 0)
        assertEquals(0L, small.waitMs(10, 0, 1440, 480, 1440, 0, 960, false, 0))
    }

    @Test fun `only a live first shortage is eligible including partial blocks`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 2)
        val out = ShortArray(960)
        bank.write(ShortArray(1000) { 1000 }, 1000, 0)
        assertFalse(bank.wouldConceal()) // Preroll.
        bank.render(out, 1000)
        assertEquals(480, bank.lastRenderRealFrames)
        assertTrue(bank.wouldConceal()) // 20 real frames left.
        bank.render(out, 1010)
        assertEquals(20, bank.lastRenderRealFrames)
        assertFalse(bank.wouldConceal()) // Already concealing.
        bank.render(out, 1020); bank.render(out, 1030)
        assertFalse(bank.wouldConceal()) // Refilling.
        bank.finish()
        assertFalse(bank.wouldConceal())
        bank.reset()
        bank.write(ShortArray(960) { 1000 }, 960, 2000)
        bank.render(out, 3000)
        bank.finish()
        bank.noteArrival(3010, 100)
        bank.write(ShortArray(200), 200, 3010)
        assertFalse(bank.wouldConceal()) // Closing prefix / new preroll.
    }
}
