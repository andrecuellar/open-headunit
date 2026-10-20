package com.andrerinas.openheadunit.decoder.audio

/** Delay the first concealment decision while submitted real PCM can cover the wait.
 * AudioTrack's head counts frames consumed by AudioFlinger, not the DAC. Using that
 * earlier boundary leaves HAL audio out of the estimate rather than inventing extra reserve.
 * Unknown clocks (including AAudio), recovery, and new outputs keep normal write pacing.
 */
internal class PcmRenderDeadline {
    private var epoch = -1L
    private var lastPlayed = -1L
    private var lastProgressMs = -1L

    fun waitMs(nowMs: Long, outputEpoch: Long, written: Long, played: Long,
               realEnd: Long, realEpoch: Long, bufferFrames: Int,
               recovering: Boolean, renderWorkMs: Long): Long {
        if (recovering || played < 0 || written < played) {
            reset()
            return 0
        }
        if (epoch != outputEpoch || lastPlayed < 0 || played < lastPlayed) {
            epoch = outputEpoch
            lastPlayed = played
            lastProgressMs = -1
            return 0
        }
        if (played > lastPlayed) {
            lastPlayed = played
            lastProgressMs = nowMs
        }
        // A newly started track may need more writes to reach its start threshold.
        // Require observed playback progress before borrowing any of its queue as reserve.
        if (lastProgressMs < 0 || realEpoch != outputEpoch || realEnd <= played || realEnd > written) return 0
        // Keep at least half the device budget, 40ms, and the recent render cost plus
        // one cycle. If scheduling is too slow, there is no safe time to borrow.
        val guardFrames = maxOf(bufferFrames.toLong() / 2, 40L * 48, (renderWorkMs + 10) * 48)
        // A stale/frozen vendor head must not hold the writer forever. Age its last
        // observation as though consumption continued, even if the reported head stops.
        val elapsed = (nowMs - lastProgressMs).coerceAtLeast(0)
        val remaining = realEnd - played - elapsed * 48 - guardFrames
        return (remaining / 48).coerceIn(0, 10)
    }

    fun reset() {
        epoch = -1
        lastPlayed = -1
        lastProgressMs = -1
    }
}
