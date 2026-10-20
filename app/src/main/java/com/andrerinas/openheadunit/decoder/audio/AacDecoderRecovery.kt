package com.andrerinas.openheadunit.decoder.audio

/** Codec callbacks request recovery; only the decode thread consumes it. Waiting for the retry
 * deadline must retain the request, including when codec construction itself failed. */
internal class AacDecoderRecovery {
    enum class Action { NONE, WAIT, REBUILD, STOP }
    @Volatile var pending = false
        private set
    @Volatile var attempts = 0
        private set
    private var lastAttemptMs = 0L
    private var healthyOutputUs = 0L
    private var healthySinceMs = -1L
    private var lastHealthyMs = -1L
    private var exhausted = false
    @Synchronized fun isExhausted(): Boolean = exhausted
    private var playbackVersion = 0L
    private var attemptedPlaybackVersion = 0L

    @Synchronized fun request() {
        clearProgress()
        if (!exhausted) pending = true
    }

    /** Count PCM accepted by the mixer, not input AUs, configure success or wire timestamps.
     * AA can reuse its AAC encoder across Stop/Start and several DATA packets can carry the
     * same capture timestamp. Neither is a healthy decoder lifetime boundary. A long track
     * may therefore recover from separate faults without ever receiving another Start.
     *
     * Require ten seconds of decoded audio AND elapsed time with no long output gap: a burst
     * draining old input, or an occasional successful buffer between failures, cannot rearm
     * a broken decoder. The caller must exclude output from a retired codec under its lock.
     * Return true only at renewal so the caller can log that edge without periodic noise.
     */
    @Synchronized fun decodedPcm(frames: Int, sampleRate: Int, nowMs: Long): Boolean {
        if (attempts == 0 || pending || exhausted || frames <= 0 || sampleRate <= 0) return false
        if (lastHealthyMs < 0 || nowMs < lastHealthyMs || nowMs - lastHealthyMs > 1000) {
            clearProgress()
            healthySinceMs = nowMs
        }
        lastHealthyMs = nowMs
        healthyOutputUs = minOf(AacDecoderRecoveryPolicy.HEALTHY_OUTPUT_MS * 1000,
            healthyOutputUs + frames.toLong() * 1_000_000 / sampleRate)
        if (healthyOutputUs >= AacDecoderRecoveryPolicy.HEALTHY_OUTPUT_MS * 1000 &&
            nowMs - healthySinceMs >= AacDecoderRecoveryPolicy.HEALTHY_OUTPUT_MS) {
            resetBudget()
            return true
        }
        return false
    }

    /** Retirement can race output after next() consumed a rebuild request. Do not let that
     * last old-codec buffer seed the replacement's healthy-output window. */
    @Synchronized fun codecRetired() { clearProgress() }

    private fun clearProgress() {
        healthyOutputUs = 0
        healthySinceMs = -1
        lastHealthyMs = -1
    }

    /** Remember Start even if it races the final failed rebuild, before STOP is consumed. */
    @Synchronized fun retryIfExhausted() {
        playbackVersion++
        if (!exhausted) return
        resetBudget()
        pending = true
    }

    @Synchronized fun next(nowMs: Long): Action {
        if (!pending) return Action.NONE
        if (attempts >= AacDecoderRecoveryPolicy.MAX_REBUILDS) {
            if (playbackVersion != attemptedPlaybackVersion) resetBudget()
            else {
                pending = false
                exhausted = true
                return Action.STOP
            }
        }
        if (!AacDecoderRecoveryPolicy.allowsRebuild(attempts, nowMs - lastAttemptMs)) return Action.WAIT
        pending = false
        attempts++
        attemptedPlaybackVersion = playbackVersion
        lastAttemptMs = nowMs
        return Action.REBUILD
    }

    /** A new configuration or explicit playback request may retry a terminal decoder. */
    @Synchronized fun reset() {
        pending = false
        playbackVersion = 0
        attemptedPlaybackVersion = 0
        resetBudget()
    }

    private fun resetBudget() {
        clearProgress()
        attempts = 0
        lastAttemptMs = 0
        exhausted = false
    }
}
