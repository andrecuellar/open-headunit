package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class PcmPlaybackPositionTest {
    @Test fun `a full legacy track admits only frames already consumed`() {
        val p = PcmPlaybackPosition()
        assertEquals(4800, p.writableFrames(4800, 0))
        p.written(4800)
        assertEquals(0, p.writableFrames(4800, 0))
        assertEquals(120, p.writableFrames(4800, 120))
        p.written(120)
        assertEquals(0, p.writableFrames(4800, 120))
        assertEquals(120L, p.played(120))
    }

    @Test fun `a smaller route cannot admit more writes while its old queue drains`() {
        val p = PcmPlaybackPosition()
        p.written(4800)
        assertEquals(0, p.writableFrames(960, 100))
        assertEquals(60, p.writableFrames(960, 3900))
    }

    @Test fun `unsigned head passes both signed boundary and full wrap`() {
        val p = PcmPlaybackPosition()
        p.written(0x100000100L)
        assertEquals(0x70000000L, p.played(0x70000000))
        assertEquals(0x80000000L, p.played(0x80000000.toInt()))
        assertEquals(0xffffffffL, p.played(-1))
        assertEquals(0x100000100L, p.played(0x100))
    }

    @Test fun `unexpected reset is not evidence of free space or completed playback`() {
        val p = PcmPlaybackPosition()
        p.written(4800)
        assertEquals(2400L, p.played(2400))
        assertEquals(-1L, p.played(0))
        assertEquals(0, p.writableFrames(4800, 2400))
        p.reset()
        assertEquals(4800, p.writableFrames(4800, 0))
        assertEquals(0L, p.writtenFrames)
    }

    @Test fun `a head beyond submitted PCM cannot falsely finish a prompt`() {
        val p = PcmPlaybackPosition()
        p.written(480)
        assertEquals(-1L, p.played(960))
        assertEquals(0, p.writableFrames(960, 960))
    }
}
