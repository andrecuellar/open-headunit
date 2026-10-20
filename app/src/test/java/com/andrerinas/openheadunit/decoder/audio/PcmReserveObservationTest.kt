package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class PcmReserveObservationTest {
    private val cycle = ShortArray(960) { 1000 }
    private val out = ShortArray(960)

    private fun feed(bank: AdaptivePcmBuffer, now: Long, frames: Int = 480) {
        val pcm = if (frames == 480) cycle else ShortArray(frames * 2) { 1000 }
        bank.noteArrival(now, frames)
        bank.write(pcm, pcm.size, now)
    }

    @Test fun `starvation captures an open supply gap before a later refill`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 2)
        feed(bank, 0, 960)
        bank.render(out, 100); bank.render(out, 110)
        bank.render(out, 120, 7680)
        feed(bank, 130, 9600)
        val snapshot = bank.takeStarvation()!!
        assertEquals(120L, snapshot.atMs)
        assertEquals(120L, snapshot.lastArrivalAgeMs)
        assertEquals(120L, snapshot.lastPcmAgeMs)
        assertEquals(0, snapshot.availableFrames)
        assertEquals(7680, snapshot.outputBudgetFrames)
        assertNull(bank.takeStarvation())
        bank.finish()
        repeat(30) { bank.render(out, 140L + it * 10) }
        assertNull(bank.takeStarvation())
    }

    @Test fun `brief shortages are not reported as full rebanks`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 2)
        feed(bank, 0, 960)
        bank.render(out, 1000) // Preroll deadline permits this short input.
        bank.render(out, 1010)
        bank.render(out, 1020) // First missing 10ms: concealment only.
        assertEquals(1L, bank.rebanks)
        assertEquals(0L, bank.rebankEntries)
        feed(bank, 1030); bank.render(out, 1030)
        bank.render(out, 1040) // A separate shortage.
        assertEquals(2L, bank.rebanks)
        assertEquals(0L, bank.rebankEntries)
        bank.render(out, 1050); bank.render(out, 1060)
        assertEquals(2L, bank.rebanks)
        assertEquals(1L, bank.rebankEntries)
        val snapshot = bank.takeReserveWindow(61_000)!!
        assertEquals(2L, snapshot.underrunEvents)
        assertEquals(1L, snapshot.rebankEntries)
        assertEquals(0, snapshot.minimumFrames)
    }

    @Test fun `render trough survives a packet arriving immediately before the log`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 2)
        feed(bank, 0, 960)
        bank.render(out, 1000); bank.render(out, 1010)
        feed(bank, 1020, 2048)
        assertNull(bank.takeReserveWindow(60_999))
        val snapshot = bank.takeReserveWindow(61_000)!!
        assertEquals(0, snapshot.minimumFrames)
        assertEquals(480, snapshot.maximumFrames)
        assertEquals(2048, snapshot.depthFrames)
        assertEquals(2, snapshot.cycles)
        assertNull(bank.takeReserveWindow(121_000))
    }

    @Test fun `reset and preroll do not masquerade as active zero-depth observations`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 2, isMediaSink = true)
        bank.render(out, 0)
        assertNull(bank.takeReserveWindow(6000))
        feed(bank, 6000, 960)
        bank.render(out, 6000)
        assertNull(bank.takeReserveWindow(12000))
        bank.render(out, 12000)
        bank.reset()
        assertNull(bank.takeReserveWindow(18000))
        feed(bank, 18000, 9600)
        bank.render(out, 20000) // Let this new preroll deadline expire.
        val snapshot = bank.takeReserveWindow(80_000)!!
        assertEquals(1, snapshot.cycles)
        assertEquals(bank.depthFrames(), snapshot.minimumFrames)
    }
    @Test fun `intentional Stop tail and paused time do not enter live reserve windows`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 2)
        feed(bank, 0, 960)
        bank.render(out, 1000)
        bank.finish()
        repeat(4) { bank.render(out, 1010L + it * 10) }
        val stopped = bank.takeReserveWindow(10_000)!!
        assertTrue(stopped.stopped)
        assertEquals(480, stopped.minimumFrames)
        assertEquals(480, stopped.depthFrames)
        assertEquals(1, stopped.cycles)
        assertEquals(0L, stopped.windowMs)
        assertNull(bank.takeReserveWindow(10_000))
        assertEquals(0L, bank.rebanks)
        assertEquals(0L, bank.rebankEntries)
        feed(bank, 10_000, 960)
        bank.render(out, 11_000)
        val snapshot = bank.takeReserveWindow(71_000)!!
        assertEquals(60_000L, snapshot.windowMs)
        assertEquals(480, snapshot.minimumFrames)
    }

    @Test fun `multiple short prompts keep a bounded summary and expose skipped windows`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 2)
        repeat(3) { index ->
            val now = index * 1000L
            feed(bank, now, 960)
            bank.render(out, now + 100)
            bank.finish()
            repeat(4) { bank.render(out, now + 110 + it * 10) }
            bank.finish() // Repeated Stop without live renders adds no record.
        }
        val snapshot = bank.takeReserveWindow(3000)!!
        assertTrue(snapshot.stopped)
        assertEquals(2L, snapshot.skippedWindows)
        assertEquals(480, snapshot.minimumFrames)
        assertNull(bank.takeReserveWindow(3000))
        feed(bank, 4000, 960)
        bank.render(out, 4100); bank.finish(); bank.reset()
        assertNull(bank.takeReserveWindow(5000))
    }
}
