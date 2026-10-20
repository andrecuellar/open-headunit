package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class GuidanceDeliveryScheduleTest {
    @Test fun `selected 16x covers batched guidance across device drain phases`() {
        for (batchMs in listOf(128, 192, 256, 320)) {
            for (burstMs in listOf(10, 40, 160)) for (phaseMs in listOf(0, 30, 90)) {
                val bank = AdaptivePcmBuffer(latencyMultiplier = 16)
                val packet = ShortArray(3072 * 2) { 10000 } // 64ms at mixer rate.
                val out = ShortArray(960)
                repeat(3) { prompt ->
                    var packets = 0
                    var stopped = false
                    for (time in 0..6000) {
                        val now = prompt * 30_000L + time
                        if (time % batchMs == 0) {
                            while (packets < 64 && packets * 64 <= time) {
                                bank.noteArrival(now, 3072)
                                bank.write(packet, packet.size, now)
                                packets++
                            }
                            if (packets == 64 && !stopped) {
                                bank.finish()
                                stopped = true
                            }
                        }
                        if (time >= phaseMs && (time - phaseMs) % burstMs == 0) {
                            repeat(burstMs / 10) { bank.render(out, now, burstMs * 48) }
                        }
                    }
                    val trace = "batch=$batchMs burst=$burstMs phase=$phaseMs prompt=$prompt"
                    assertTrue(trace, bank.isIdle())
                    assertEquals(trace, 0L, bank.droppedFrames)
                    assertEquals(trace, 0L, bank.concealedFrames)
                    assertEquals(trace, 0L, bank.rebankEntries)
                }
            }
        }
    }
}
