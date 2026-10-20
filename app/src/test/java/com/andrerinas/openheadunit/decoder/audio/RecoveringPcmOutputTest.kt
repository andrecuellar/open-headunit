package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class RecoveringPcmOutputTest {
    @Test fun `a complete short prompt survives initial open backoff without more packets`() {
        var now = 0L
        var opens = 0
        val device = Device()
        val output = RecoveringPcmOutput(null, {
            if (++opens == 1) error("route restarting")
            device
        }, { now }, {})
        output.start()
        val prompt = ShortArray(640) { (it + 100).toShort() }
        assertEquals(prompt.size, AudioWriteLoop.writeFully(prompt.size, { true },
            { offset, remaining -> output.write(prompt, offset, remaining) }, {}, { now++ }))
        assertEquals(251L, now)
        assertEquals(2, opens)
        assertEquals(prompt.toList(), device.accepted)
    }

    @Test fun `new Start can bypass output recovery cooldown`() {
        var now = 0L
        var opens = 0
        var working = false
        val device = Device()
        val output = RecoveringPcmOutput(null, {
            opens++
            if (!working) error("route unavailable")
            device
        }, { now }, {})
        val pcm = ShortArray(320) { it.toShort() }
        repeat(3) { output.write(pcm, 0, pcm.size); now += 250 }
        assertEquals(-6, output.write(pcm, 0, pcm.size))
        assertEquals(3, opens)
        working = true
        assertEquals(-6, output.write(pcm, 0, pcm.size))
        output.preparePlayback()
        assertEquals(0, output.write(pcm, 0, pcm.size))
        assertEquals(pcm.size, output.write(pcm, 0, pcm.size))
        assertEquals(pcm.toList(), device.accepted)
    }

    @Test fun `a recovered route resumes the same prompt without another Start`() {
        var now = 0L
        var working = false
        var opens = 0
        val device = Device()
        val output = RecoveringPcmOutput(null, {
            opens++
            if (!working) error("route unavailable")
            device
        }, { now }, {})
        output.start()
        val pcm = ShortArray(320) { (it + 1).toShort() }
        repeat(3) { output.write(pcm, 0, pcm.size); now += 250 }
        assertTrue(output.isParked)
        working = true
        now = 1499
        assertEquals(-6, output.write(pcm, 0, pcm.size))
        assertEquals(3, opens)
        now = 1500
        assertTrue(output.retryReady)
        assertEquals(0, output.write(pcm, 0, pcm.size))
        assertEquals(pcm.size, output.write(pcm, 0, pcm.size))
        assertEquals(1, device.starts)
        assertEquals(pcm.toList(), device.accepted)
    }

    @Test fun `permanently unavailable output backs off instead of reopening each mixer tick`() {
        var now = 0L
        var opens = 0
        val output = RecoveringPcmOutput(null, { opens++; error("no route") }, { now }, {})
        val pcm = ShortArray(960)
        for (deadline in listOf(1500L, 4000L, 9500L)) {
            repeat(3) { output.write(pcm, 0, pcm.size); now += 250 }
            val before = opens
            while (now < deadline) {
                assertEquals(-6, output.write(pcm, 0, pcm.size))
                now++
            }
            assertEquals(before, opens)
            assertTrue(output.retryReady)
        }
        repeat(3) { output.write(pcm, 0, pcm.size); now += 250 }
        val exhausted = opens
        now += 1_000_000
        repeat(100) { assertEquals(-6, output.write(pcm, 0, pcm.size)) }
        assertEquals(exhausted, opens)
        assertTrue(output.isParked)
        output.preparePlayback()
        repeat(3) { output.write(pcm, 0, pcm.size); now += 250 }
        assertEquals(exhausted + 3, opens)
        now += 750
        assertFalse(output.retryReady) // Start permits a burst, not a fresh automatic budget.
        assertTrue(output.isParked)
    }

    @Test fun `repeated Starts on a dead route do not renew automatic focus retries`() {
        var now = 0L
        var opens = 0
        val output = RecoveringPcmOutput(null, { opens++; error("no route") }, { now }, {})
        val pcm = ShortArray(960)
        var retryEdges = 0
        repeat(12000) { tick ->
            // Each prompt still gets its explicit attempt. Count only automatic retry edges.
            if (tick % 1000 == 0) output.preparePlayback()
            else if (output.retryReady) retryEdges++
            output.write(pcm, 0, pcm.size)
            now += 10
        }
        assertEquals(3, retryEdges)
        assertTrue(output.isParked)
        // A Start during a retry round may be consumed by that round's next open.
        assertTrue("explicit Starts must still retry: opens=$opens", opens >= 12 + 3)
        assertTrue("opens=$opens", opens <= (12 + 3) * 3)
    }

    @Test fun `one second of successful writes between faults cannot rearm retries`() {
        var now = 0L
        val device = Device()
        var openedAt = 0L
        var opens = 0
        val output = RecoveringPcmOutput(device, {
            opens++
            openedAt = now
            device
        }, { now }, {})
        val pcm = ShortArray(960)
        var retryEdges = 0
        repeat(12000) {
            device.result = if (now - openedAt < 1010) pcm.size else -6
            if (output.retryReady) retryEdges++
            output.write(pcm, 0, pcm.size)
            now += 10
        }
        assertEquals(3, retryEdges)
        assertTrue(output.isParked)
        assertEquals(11, opens)
    }

    @Test fun `sustained playback renews the watchdog between isolated failures`() {
        var now = 0L
        var opens = 0
        val device = Device()
        val output = RecoveringPcmOutput(device, { opens++; device }, { now }, {})
        val pcm = ShortArray(960)
        var retryEdges = 0
        for (tick in 0..12000) {
            device.result = if (now > 0 && now % 15_000 == 0L) -6 else pcm.size
            if (output.retryReady) retryEdges++
            output.write(pcm, 0, pcm.size)
            assertFalse("isolated failure parked output at $now ms", output.isTerminal)
            now += 10
        }
        assertEquals(0, retryEdges)
        assertEquals(8, opens)
    }

    @Test fun `retry renewal requires ten seconds of both output and elapsed time without gaps`() {
        for (mode in listOf("healthy", "burst", "gap", "sparse")) {
            var now = 0L
            var working = false
            val device = Device()
            val output = RecoveringPcmOutput(null, {
                if (!working) error("no route")
                device
            }, { now }, {})
            val pcm = ShortArray(960)
            repeat(3000) { output.write(pcm, 0, pcm.size); now += 10 }
            assertTrue(output.isParked)
            working = true
            output.preparePlayback()
            output.write(pcm, 0, pcm.size)
            repeat(1001) { tick ->
                if (mode == "gap" && tick == 500) now += 1001
                output.write(pcm, 0, if (mode == "sparse") 2 else pcm.size)
                if (mode != "burst") now += 10
            }
            device.result = -6
            working = false
            repeat(100) { output.write(pcm, 0, pcm.size); now += 10 }
            now += 1000
            assertEquals(mode, mode == "healthy", output.retryReady)
        }
    }

    @Test fun `clean short prompts renew recovery despite long intentional pauses`() {
        var now = 0L
        var unavailable = false
        val device = Device(recordAccepted = false)
        val output = RecoveringPcmOutput(device, {
            check(!unavailable)
            device.result = Int.MAX_VALUE
            device
        }, { now }, {})
        val pcm = ShortArray(960)
        // One isolated fault followed by two completely clean prompts. There is enough
        // good output between faults, but never a ten-second uninterrupted prompt.
        repeat(36) { prompt ->
            output.preparePlayback(); output.start()
            repeat(400) { tick ->
                if (prompt % 3 == 0 && tick == 100) device.result = -6
                output.write(pcm, 0, pcm.size)
                now += 10
            }
            output.pause()
            now += 56_000
        }
        val before = device.acceptedSamples
        val outageAt = now
        device.result = -6
        output.preparePlayback(); output.start()
        repeat(1000) {
            unavailable = now - outageAt < 2000
            output.write(pcm, 0, pcm.size)
            now += 10
        }
        assertTrue("a recovered route must not wait for another Start", device.acceptedSamples > before)
        assertFalse(output.isParked)
    }

    @Test fun `idle time and instantaneous bursts across pauses cannot renew retries`() {
        for (burst in listOf(false, true)) {
            var now = 0L
            var working = false
            val device = Device(recordAccepted = false)
            val output = RecoveringPcmOutput(null, {
                check(working)
                device
            }, { now }, {})
            val pcm = ShortArray(960)
            repeat(3000) { output.write(pcm, 0, pcm.size); now += 10 }
            assertTrue(output.isParked)
            working = true
            output.preparePlayback(); output.start()
            output.write(pcm, 0, pcm.size)
            repeat(20) {
                // More than ten seconds of submitted PCM in burst mode, but no active
                // elapsed time. Single writes also must not turn the minute-long idle into health.
                repeat(if (burst) 100 else 1) { output.write(pcm, 0, pcm.size) }
                output.pause()
                now += 60_000
                output.preparePlayback(); output.start()
            }
            working = false
            device.result = -6
            repeat(1000) { output.write(pcm, 0, pcm.size); now += 10 }
            assertTrue("burst=$burst", output.isParked)
            assertFalse(output.retryReady)
        }
    }

    @Test fun `lifecycle failures output replacement and unexplained gaps discard earned health`() {
        for (mode in listOf("pause", "epoch", "start", "gap")) {
            var now = 0L
            var working = false
            val device = Device(recordAccepted = false)
            val output = RecoveringPcmOutput(null, {
                check(working)
                device
            }, { now }, {})
            val pcm = ShortArray(960)
            repeat(3000) { output.write(pcm, 0, pcm.size); now += 10 }
            assertTrue(output.isParked)
            working = true
            output.preparePlayback(); output.start()
            output.write(pcm, 0, pcm.size)
            repeat(901) { output.write(pcm, 0, pcm.size); now += 10 }
            when (mode) {
                "epoch" -> { output.pause(); device.outputEpoch++; output.start() }
                "start" -> { output.pause(); device.failStart = true; output.start() }
                "gap" -> now += 1001
                else -> {
                    device.failPause = true
                    output.pause()
                    device.failPause = false
                    output.start()
                }
            }
            repeat(201) { output.write(pcm, 0, pcm.size); now += 10 }
            device.result = -6
            working = false
            repeat(1000) { output.write(pcm, 0, pcm.size); now += 10 }
            assertTrue("$mode reused old health", output.isParked)
            assertFalse(output.retryReady)
        }
    }

    @Test fun `clean short prompts can restore an already exhausted automatic budget`() {
        var now = 0L
        var working = false
        val device = Device(recordAccepted = false)
        val output = RecoveringPcmOutput(null, {
            check(working)
            device.result = Int.MAX_VALUE
            device
        }, { now }, {})
        val pcm = ShortArray(960)
        repeat(3000) { output.write(pcm, 0, pcm.size); now += 10 }
        assertTrue(output.isParked)
        working = true
        repeat(4) {
            output.preparePlayback(); output.start()
            repeat(301) { output.write(pcm, 0, pcm.size); now += 10 }
            output.pause()
            now += 60_000
        }
        output.preparePlayback(); output.start()
        val before = device.acceptedSamples
        val outageAt = now
        device.result = -6
        repeat(1000) {
            working = now - outageAt >= 2000
            output.write(pcm, 0, pcm.size)
            now += 10
        }
        assertTrue(device.acceptedSamples > before)
        assertFalse(output.isParked)
    }

    @Test fun `combined active time must cross ten seconds even across clean pauses`() {
        for (secondPromptTicks in listOf(500, 502)) {
            var now = 0L
            var working = false
            val device = Device(recordAccepted = false)
            val output = RecoveringPcmOutput(null, {
                check(working)
                device
            }, { now }, {})
            val pcm = ShortArray(960)
            repeat(3000) { output.write(pcm, 0, pcm.size); now += 10 }
            assertTrue(output.isParked)
            working = true
            output.preparePlayback(); output.start()
            output.write(pcm, 0, pcm.size) // Reopen; no PCM accepted yet.
            for (ticks in listOf(501, secondPromptTicks)) {
                output.preparePlayback(); output.start()
                repeat(ticks) { output.write(pcm, 0, pcm.size); now += 10 }
                output.pause()
                now += 60_000
            }
            // 5000 + 4990 active ms must not renew; 5000 + 5010 must renew.
            output.preparePlayback(); output.start()
            working = false
            device.result = -6
            repeat(100) { output.write(pcm, 0, pcm.size); now += 10 }
            now += 1000
            assertEquals("ticks=$secondPromptTicks", secondPromptTicks == 502, output.retryReady)
        }
    }

    private class Device(var result: Int = Int.MAX_VALUE, val recordAccepted: Boolean = true) : PcmOutput {
        override val name = "AudioTrack"
        override val capacityFrames = 19200
        override var bufferFrames = 19200
        override val burstFrames = 480
        override val underruns = 0
        var closes = 0
        var starts = 0
        var failPause = false
        var failStart = false
        override var outputEpoch = 0L
        val accepted = mutableListOf<Short>()
        var acceptedSamples = 0L
        override fun start() {
            starts++
            if (failStart) { failStart = false; error("start failed once") }
        }
        override fun pause() { check(!failPause) }
        override fun close() { closes++ }
        override fun setBufferFrames(frames: Int): Int { bufferFrames = frames; return frames }
        override fun write(data: ShortArray, offset: Int, count: Int): Int {
            val n = minOf(count, result)
            if (n > 0) {
                acceptedSamples += n
                if (recordAccepted) accepted.addAll(data.slice(offset until offset + n))
            }
            return n
        }
    }

    @Test fun `dead audioserver after a partial write reopens and writes only remaining samples`() {
        val first = Device(320)
        val replacement = Device()
        val output = RecoveringPcmOutput(first, { replacement }, { 0 }, {})
        output.setBufferFrames(1440)
        output.start()
        val pcm = ShortArray(960) { it.toShort() }
        assertEquals(960, AudioWriteLoop.writeFully(960, { true }, { off, size ->
            output.write(pcm, off, size)
        }, { first.result = -6 }, {}))
        assertEquals(pcm.toList(), first.accepted + replacement.accepted)
        assertEquals(1, first.closes)
        assertEquals(1, replacement.starts)
        assertEquals(1440, replacement.bufferFrames)
    }

    @Test fun `repeated route errors are bounded even when no output accepts any PCM`() {
        var opened = 0
        val output = RecoveringPcmOutput(Device(-6), { opened++; Device(-6) }, { 0 }, {})
        assertEquals(-6, AudioWriteLoop.writeFully(960, { true }, { off, size ->
            output.write(ShortArray(960), off, size)
        }, {}, {}))
        assertEquals(2, opened)
    }

    @Test fun `zero writes retain the half second watchdog and learned output budget`() {
        var now = 0L
        val replacement = Device()
        val output = RecoveringPcmOutput(Device(0), { replacement }, { now }, {})
        output.setBufferFrames(1920)
        output.start()
        val pcm = ShortArray(960)
        assertEquals(0, output.write(pcm, 0, pcm.size))
        now = 499
        assertEquals(0, output.write(pcm, 0, pcm.size))
        assertEquals(0, replacement.starts)
        now = 500
        assertEquals(0, output.write(pcm, 0, pcm.size))
        assertEquals(1, replacement.starts)
        assertEquals(1920, replacement.bufferFrames)
        assertEquals(960, output.write(pcm, 0, pcm.size))
    }

    @Test fun `invalid write arguments never churn hardware outputs`() {
        val output = RecoveringPcmOutput(Device(-2), { error("must not reopen") }, { 0 }, {})
        assertEquals(-2, output.write(ShortArray(960), 0, 960))
    }

    @Test fun `a few priming samples cannot reset the recovery budget`() {
        val devices = mutableListOf(Device(32))
        val output = RecoveringPcmOutput(devices.first(), {
            Device(32).also { devices.add(it) }
        }, { 0 }, {})
        var writes = 0
        assertEquals(-6, AudioWriteLoop.writeFully(960, { writes < 20 }, { offset, size ->
            writes++
            output.write(ShortArray(960), offset, size)
        }, { devices.last().result = -6 }, {}))
        assertEquals(3, devices.size)
        assertEquals(6, writes)
    }

    @Test fun `a failed reopen preserves the partial block while awaiting its next attempt`() {
        var now = 0L
        var opens = 0
        val first = Device(320)
        val replacement = Device()
        val output = RecoveringPcmOutput(first, {
            if (++opens == 1) error("audioserver still restarting")
            replacement
        }, { now }, {})
        output.start()
        val pcm = ShortArray(960) { it.toShort() }
        val count = AudioWriteLoop.writeFully(pcm.size, { true }, { offset, remaining ->
            output.write(pcm, offset, remaining)
        }, { first.result = -6 }, { now++ })
        assertEquals(960, count)
        assertTrue(now in 250L..251L)
        assertEquals(2, opens)
        assertEquals(pcm.toList(), first.accepted + replacement.accepted)
        assertEquals(1, first.closes)
    }

    @Test fun `permanent reopen exceptions consume the budget and close once`() {
        var now = 0L
        var opens = 0
        val first = Device(-6)
        val output = RecoveringPcmOutput(first, { opens++; error("no route") }, { now }, {})
        val count = AudioWriteLoop.writeFully(960, { true }, { offset, remaining ->
            output.write(ShortArray(960), offset, remaining)
        }, {}, { now++ })
        assertEquals(-6, count)
        output.close()
        assertEquals(2, opens)
        assertEquals(1, first.closes)
        assertTrue(output.isParked)
    }
}
