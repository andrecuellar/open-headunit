package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class AacDecoderRetryTest {
    @Test fun `healthy output reports budget renewal exactly once`() {
        val recovery = AacDecoderRecovery()
        recovery.request()
        recovery.next(0)
        var renewals = 0
        for (tick in 0..300) {
            if (recovery.decodedPcm(4800, 48000, tick * 100L)) renewals++
        }
        assertEquals(1, renewals)
        assertFalse(recovery.decodedPcm(4800, 48000, 30_100))
    }

    @Test fun `construction failure arms recovery without requiring a codec callback`() {
        val recovery = AacDecoderRecovery()
        recovery.request()
        assertEquals(AacDecoderRecovery.Action.REBUILD, recovery.next(0))
        assertEquals(1, recovery.attempts)
    }

    @Test fun `a failed replacement keeps its retry pending throughout cooldown`() {
        val recovery = AacDecoderRecovery()
        recovery.request()
        recovery.next(0)
        recovery.request()
        for (now in 1L..999L) {
            assertEquals(AacDecoderRecovery.Action.WAIT, recovery.next(now))
            assertTrue(recovery.pending)
            assertEquals(1, recovery.attempts)
        }
        assertEquals(AacDecoderRecovery.Action.REBUILD, recovery.next(1000))
        assertEquals(2, recovery.attempts)
    }

    @Test fun `terminal failure stops churn and a new playback can explicitly rearm`() {
        val recovery = AacDecoderRecovery()
        repeat(3) {
            recovery.request()
            assertEquals(AacDecoderRecovery.Action.REBUILD, recovery.next(it * 10000L))
        }
        recovery.request()
        assertEquals(AacDecoderRecovery.Action.STOP, recovery.next(30000))
        repeat(10) {
            recovery.request()
            assertEquals(AacDecoderRecovery.Action.NONE, recovery.next(40000))
        }
        recovery.retryIfExhausted()
        assertEquals(AacDecoderRecovery.Action.REBUILD, recovery.next(40001))
    }

    @Test fun `a playback request cannot reset a rebuild in progress or its cooldown`() {
        val recovery = AacDecoderRecovery()
        recovery.request()
        recovery.next(0)
        recovery.retryIfExhausted()
        assertEquals(1, recovery.attempts)
        assertEquals(AacDecoderRecovery.Action.NONE, recovery.next(1))
        recovery.request()
        recovery.retryIfExhausted()
        assertEquals(1, recovery.attempts)
        assertEquals(AacDecoderRecovery.Action.WAIT, recovery.next(2))
    }

    @Test fun `Start racing the last failure is retained on either side of the callback`() {
        for (startBeforeFailure in listOf(false, true)) {
            val recovery = AacDecoderRecovery()
            repeat(3) {
                recovery.request()
                assertEquals(AacDecoderRecovery.Action.REBUILD, recovery.next(it * 10000L))
            }
            if (startBeforeFailure) recovery.retryIfExhausted()
            recovery.request()
            if (!startBeforeFailure) recovery.retryIfExhausted()
            assertEquals(AacDecoderRecovery.Action.REBUILD, recovery.next(20001))
            assertEquals(1, recovery.attempts)
        }
    }
    @Test fun `separate faults in one long track each retain a recovery budget`() {
        val recovery = AacDecoderRecovery()
        repeat(8) { fault ->
            val start = fault * 60_000L
            recovery.request()
            assertEquals(AacDecoderRecovery.Action.REBUILD, recovery.next(start))
            for (tick in 0..100) recovery.decodedPcm(4800, 48000, start + tick * 100)
            assertEquals(0, recovery.attempts)
        }
    }

    @Test fun `brief successes between faults cannot replenish the budget`() {
        val recovery = AacDecoderRecovery()
        repeat(3) { fault ->
            recovery.request()
            assertEquals(AacDecoderRecovery.Action.REBUILD, recovery.next(fault * 10_000L))
            repeat(90) { recovery.decodedPcm(4800, 48000, fault * 10_000L + it * 100) }
        }
        recovery.request()
        assertEquals(AacDecoderRecovery.Action.STOP, recovery.next(30_000))
        recovery.decodedPcm(480000, 48000, 50_000)
        assertTrue(recovery.isExhausted())
    }

    @Test fun `a decoded backlog and a silent pause cannot masquerade as sustained progress`() {
        val recovery = AacDecoderRecovery()
        recovery.request(); recovery.next(0)
        recovery.decodedPcm(480000, 48000, 0)
        assertEquals(1, recovery.attempts)
        recovery.decodedPcm(480, 48000, 10_000)
        assertEquals(1, recovery.attempts)
        repeat(99) { recovery.decodedPcm(4800, 48000, 10_100L + it * 100) }
        assertEquals(1, recovery.attempts)
        recovery.decodedPcm(4800, 48000, 20_000)
        assertEquals(0, recovery.attempts)
    }

    @Test fun `progress uses the decoded sample rate and ignores pending failure output`() {
        for (rate in listOf(8000, 16000, 44100, 48000, 96000)) {
            val recovery = AacDecoderRecovery()
            recovery.request(); recovery.next(0)
            repeat(99) { recovery.decodedPcm(rate / 10, rate, it * 100L) }
            assertEquals(1, recovery.attempts)
            recovery.request()
            repeat(120) { recovery.decodedPcm(rate / 10, rate, 10_000 + it * 100L) }
            assertEquals(1, recovery.attempts)
            assertTrue(recovery.pending)
            assertEquals(AacDecoderRecovery.Action.REBUILD, recovery.next(25_000))
            for (tick in 0..100) recovery.decodedPcm(rate / 10, rate, 25_000 + tick * 100L)
            assertEquals(0, recovery.attempts)
        }
    }

    @Test fun `retirement clears old output without spending or replenishing retries`() {
        val recovery = AacDecoderRecovery()
        recovery.request(); recovery.next(0)
        repeat(99) { recovery.decodedPcm(4800, 48000, it * 100L) }
        recovery.codecRetired()
        assertEquals(1, recovery.attempts)
        recovery.decodedPcm(4800, 48000, 10_000)
        assertEquals(1, recovery.attempts)
        for (tick in 1..100) recovery.decodedPcm(4800, 48000, 10_000 + tick * 100L)
        assertEquals(0, recovery.attempts)
    }

}
