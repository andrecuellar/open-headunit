package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

/** Controlled 16kHz/1024-frame guidance batches converted to the mixer's 48kHz clock. */
class GuidanceResumeReserveTest {
    private val chunk = ShortArray(3072 * 2) { 12000 }
    private val out = ShortArray(960)

    private fun feed(bank: AdaptivePcmBuffer, now: Long) {
        bank.noteArrival(now, 3072)
        bank.write(chunk, chunk.size, now)
    }

    private fun learnedBank(): AdaptivePcmBuffer {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 4)
        repeat(4) { feed(bank, 0) }
        repeat(4) { feed(bank, 256) }
        assertTrue(bank.targetFrames() >= 256 * 48)
        bank.finish()
        repeat(60) { bank.render(out, 260 + it * 10L) }
        assertTrue(bank.isIdle())
        return bank
    }

    @Test fun `16x guidance waits for its requested reserve without discarding any prompt`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 16)
        assertEquals(400 * 48, bank.targetFrames())
        repeat(6) { feed(bank, it * 64L) }
        assertFalse(bank.render(out, 384)) // 384ms of PCM is still below the selected target.
        feed(bank, 384)
        assertTrue(bank.render(out, 384))
        bank.finish()
        repeat(50) { bank.render(out, 394 + it * 10L) }
        assertTrue(bank.isIdle())
        assertEquals(0L, bank.droppedFrames)
        assertEquals(0L, bank.concealedFrames)
    }

    @Test fun `short 16x guidance drains on Stop and cannot wait forever without Stop`() {
        for (stopped in listOf(false, true)) {
            val bank = AdaptivePcmBuffer(latencyMultiplier = 16)
            feed(bank, 0)
            assertFalse(bank.render(out, 150))
            if (stopped) bank.finish() else assertFalse(bank.render(out, 449))
            assertTrue(bank.render(out, if (stopped) 151 else 450))
            bank.finish()
            repeat(10) { bank.render(out, 460 + it * 10L) }
            assertTrue(bank.isIdle())
            assertEquals(0L, bank.droppedFrames)
            assertEquals(0L, bank.concealedFrames)
        }
    }

    @Test fun `next guidance retains learned batching reserve without counting idle as an outage`() {
        val bank = learnedBank()
        val before = bank.targetFrames()
        feed(bank, 60_000)
        assertEquals(0L, bank.maxArrivalGapMs())
        assertEquals(0L, bank.maxPcmGapMs())
        assertEquals(before, bank.targetFrames())
        assertFalse(bank.render(out, 60_150))
        assertEquals(0L, bank.concealedFrames)
        // A complete new prompt still drains immediately, even below the learned reserve.
        bank.finish()
        assertTrue(bank.render(out, 60_151))
        assertEquals(0L, bank.droppedFrames)
    }

    @Test fun `incomplete learned guidance has a bounded escape without another packet or Stop`() {
        val bank = learnedBank()
        feed(bank, 10_000)
        val deadline = bank.targetFrames() * 1000L / 48000 + 50
        assertTrue(deadline in 300L..450L)
        assertFalse(bank.render(out, 10_000 + deadline - 1))
        assertTrue(bank.render(out, 10_000 + deadline))
    }

    @Test fun `fresh short guidance keeps the existing low latency escape`() {
        val bank = AdaptivePcmBuffer(latencyMultiplier = 2)
        bank.noteArrival(0, 480)
        bank.write(ShortArray(960) { 1000 }, 960, 0)
        assertFalse(bank.render(out, 99))
        assertTrue(bank.render(out, 100))
    }

    @Test fun `learned reserve ages during clean short prompts but not between them`() {
        for (rate in listOf(16000, 48000)) {
            val policy = AdaptiveJitterPolicy(rate, 4)
            fun supply(now: Long, ms: Int) {
                policy.onArrival(now, rate * ms / 1000)
                policy.onPcmDelivery(now, rate * ms / 1000)
            }
            supply(0, 256); supply(256, 256)
            val learned = policy.targetFrames
            var now = 256L
            repeat(40) {
                policy.resetArrival()
                now += 600_000
                supply(now, 20)
                if (it == 0) assertEquals(learned, policy.targetFrames)
                repeat(50) { now += 20; supply(now, 20) }
            }
            assertTrue("rate=$rate target=${policy.targetFrames} learned=$learned",
                policy.targetFrames < learned - rate / 10)
            assertTrue(policy.targetFrames >= rate / 10)
            assertEquals(20L, policy.largestArrivalGapMs)
        }
    }

    @Test fun `next batched prompt waits for replenishment rather than starving on its prefix`() {
        val bank = learnedBank()
        feed(bank, 10_000)
        for (t in 0..600 step 10) {
            if (t == 200 || t == 450) repeat(4) { feed(bank, 10_000L + t) }
            bank.render(out, 10_000L + t)
        }
        assertEquals(0L, bank.rebanks)
        assertEquals(0L, bank.concealedFrames)
        assertEquals(0L, bank.droppedFrames)
    }

    @Test fun `explicit reset discards the carried prompt history`() {
        val bank = learnedBank()
        bank.reset()
        feed(bank, 10_000)
        assertTrue(bank.targetFrames() < 256 * 48)
        assertTrue(bank.render(out, 10_150))
    }

    @Test fun `new matching batch evidence does not shorten learned preroll`() {
        val bank = learnedBank()
        feed(bank, 10_000)
        bank.noteArrival(10_200, 256 * 48)
        val batch = ShortArray(256 * 48 * 2) { 12000 }
        bank.write(batch, batch.size, 10_200)
        assertTrue(bank.depthFrames() < bank.targetFrames())
        assertFalse(bank.render(out, 10_200))
        bank.finish()
        assertTrue(bank.render(out, 10_201))
    }

    @Test fun `a shortage restarts the quiet playback requirement`() {
        val policy = AdaptiveJitterPolicy(48000, 4)
        fun supply(now: Long, frames: Int = 960) {
            policy.onArrival(now, frames)
            policy.onPcmDelivery(now, frames)
        }
        supply(0, 256 * 48); supply(256, 256 * 48)
        policy.resetArrival()
        for (now in 1000L..10_000L step 20) supply(now)
        policy.onUnderrun(10_000)
        val disturbed = policy.targetFrames
        for (now in 10_020L..19_000L step 20) supply(now)
        assertEquals(disturbed, policy.targetFrames)
        for (now in 19_020L..24_000L step 20) supply(now)
        assertTrue(policy.targetFrames < disturbed)
    }

    @Test fun `repeated resets retain the pause boundary including a late shortage`() {
        val policy = AdaptiveJitterPolicy(48000, 4)
        policy.onArrival(0, 256 * 48); policy.onPcmDelivery(0, 256 * 48)
        policy.onArrival(256, 256 * 48); policy.onPcmDelivery(256, 256 * 48)
        policy.onUnderrun(600)
        val before = policy.targetFrames
        policy.resetArrival(); policy.resetArrival()
        policy.onArrival(600_000, 960); policy.onPcmDelivery(600_000, 960)
        assertEquals(before, policy.targetFrames)
        assertEquals(0L, policy.largestPcmGapMs)
        for (now in 600_020L..610_000L step 20) {
            policy.onArrival(now, 960); policy.onPcmDelivery(now, 960)
        }
        // Recovery begins ten active seconds after resume, not after the old underrun's
        // offset from its last packet. The pause shift must not put that event in the future.
        assertTrue(policy.targetFrames < before)
    }
}
