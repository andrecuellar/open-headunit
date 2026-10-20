package com.andrerinas.openheadunit.decoder.audio

/** Single output-owner accounting in PCM frames, not bytes or interleaved samples.
 * AudioTrack's API-3 playback head is an unsigned 32-bit counter. Explicit flush resets both
 * counters; an unexpected backwards jump invalidates admission until the output is recreated. */
internal class PcmPlaybackPosition {
    var writtenFrames = 0L
        private set
    private var lastHead = 0L
    private var played = 0L
    private var valid = true
    val isValid: Boolean get() = valid

    fun written(frames: Long) {
        require(frames >= 0)
        writtenFrames += frames
    }

    fun played(rawHead: Int): Long {
        if (!valid) return -1
        val head = rawHead.toLong() and 0xffffffffL
        val advance = (head - lastHead) and 0xffffffffL
        // The owner samples at mixer cadence, so a half-range backwards jump is a reset,
        // not twelve hours of legitimate playback. Never mistake it for a fully drained track.
        if (advance > 0x7fffffffL || advance > writtenFrames - played) {
            valid = false
            return -1
        }
        lastHead = head
        played += advance
        return played
    }

    fun writableFrames(capacityFrames: Int, rawHead: Int): Int {
        val position = played(rawHead)
        if (position < 0) return 0
        return (capacityFrames.toLong() - (writtenFrames - position))
            .coerceIn(0, capacityFrames.coerceAtLeast(0).toLong()).toInt()
    }

    fun reset() {
        writtenFrames = 0
        lastHead = 0
        played = 0
        valid = true
    }
}
