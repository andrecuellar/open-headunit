package com.andrerinas.openheadunit.decoder.audio

import java.util.ArrayDeque

/** A paced native client queue, including production's prefilled warmup silence.
 * This is a source-level scheduling regression, not a replay of the reporter's packets. */
internal fun pcmRenderDeadlineRegression() {
    for (primed in listOf(false, true)) for (shared in listOf(false, true)) {
        val baseline = runQueuedPrompt(primed, shared, defer = false)
        val fixed = runQueuedPrompt(primed, shared, defer = true)
        check(baseline.plc > 0 && baseline.internalMissingMs > 0) { "fixture must expose early concealment primed=$primed shared=$shared: $baseline / $fixed" }
        check(fixed.plc == 0L && fixed.internalMissingMs == 0) { "avoidable prompt gap: $fixed" }
        check(fixed.realFrames == 48000 && fixed.queueUnderruns == 0 && fixed.musicPlc == 0L)
        check(fixed.waits > 0)
    }
    val stopped = runQueuedPrompt(true, true, true, stopWhileWaiting = true)
    check(stopped.plc == 0L && stopped.realFrames == 24000 && stopped.queueUnderruns == 0)
    val late = runQueuedPrompt(true, true, true, returnAt = 900)
    check(late.plc > 0 && late.queueUnderruns == 0 && late.musicPlc == 0L)
    check(late.realFrames == 48000)
    println("PASS production mixer defers premature PLC with cold/primed outputs and shared music; Stop and late input remain bounded")

    val mixer = AudioMixer()
    val channel = mixer.registerChannel(4, 16000, 1)
    val output = PacedOutput(false)
    val channels = arrayOf(channel)
    channel.buffer.write(ShortArray(960) { 1000 }, 960, 0)
    mixer.renderCycle(1000, 7680, channels)
    check(channel.buffer.wouldConceal())
    val before = output.writtenFrames
    output.write(ShortArray(960), 0, 960)
    mixer.recordRealOutput(output, 0, before, false, channels)
    check(channel.lastRealEndFrame == 480L)
    mixer.renderCycle(1010, 7680, channels) // PLC must not extend real coverage.
    output.write(ShortArray(960), 0, 960)
    mixer.recordRealOutput(output, 0, 480, false, channels)
    check(channel.lastRealEndFrame == 480L)
    mixer.recordRealOutput(output, 0, 480, true, channels)
    check(channel.lastRealEndFrame == -1L)
    channel.lastRealEndFrame = 480
    mixer.recordRealOutput(output, 99, 480, false, channels)
    check(channel.lastRealEndFrame == -1L)
    println("PASS real-output markers exclude PLC and invalidate on replay/replacement")
}

private data class QueueResult(val plc: Long, val internalMissingMs: Int, val realFrames: Int,
                               val queueUnderruns: Int, val musicPlc: Long, val waits: Int)
private class PacedOutput(primed: Boolean) : PcmOutput {
    val blocks = ArrayDeque<Int>() // real guidance frames in each submitted 10ms block
    override val name = "paced client"
    override val capacityFrames = 7680
    override val bufferFrames = 7680
    override val burstFrames = 480
    override val underruns = 0
    override var writtenFrames = 0L
    var played = 0L
    init { if (primed) repeat(16) { blocks.addLast(0); writtenFrames += 480 } }
    override fun playedFrames() = played
    override fun write(data: ShortArray, offset: Int, count: Int): Int { writtenFrames += count / 2; return count }
    override fun setBufferFrames(frames: Int) = bufferFrames
    override fun start() {}
    override fun pause() {}
    override fun close() {}
}

private fun runQueuedPrompt(primed: Boolean, shared: Boolean, defer: Boolean,
                            stopWhileWaiting: Boolean = false,
                            returnAt: Int = if (primed) 560 else 400): QueueResult {
    val mixer = AudioMixer(audioLatencyMultiplier = 16)
    val speech = mixer.registerChannel(4, 16000, 1, 16)
    val music = if (shared) mixer.registerChannel(6, 48000, 2, 2, true) else null
    val channels = if (music == null) arrayOf(speech) else arrayOf(speech, music)
    val device = PacedOutput(primed)
    val played = ArrayList<Int>()
    var pending: ShortArray? = null
    var pendingReal = 0
    var waits = 0
    var queueUnderruns = 0
    var realFrames = 0
    fun feed(channel: AudioMixer.Channel, ms: Int, now: Long) {
        var remaining = ms
        while (remaining > 0) {
            val packetMs = minOf(50, remaining)
            val data = ShortArray(packetMs * 48 * 2) { 1000 }
            channel.buffer.noteArrival(now, packetMs * 48)
            channel.buffer.write(data, data.size, now)
            remaining -= packetMs
        }
    }
    feed(speech, 500, 0)
    if (music != null) feed(music, 400, 0)
    for (t in 0..1800 step 10) {
        if (music != null && t > 0 && t % 100 == 0) feed(music, 100, t.toLong())
        if (t == returnAt && !stopWhileWaiting) {
            feed(speech, 500, t.toLong()); speech.buffer.finish()
        }
        if (stopWhileWaiting && t == 520) speech.buffer.finish()
        if (t > 0) {
            if (device.blocks.isEmpty()) queueUnderruns++ else {
                played.add(device.blocks.removeFirst()); device.played += 480
            }
        }
        var loops = 0
        while (++loops < 40) {
            if (pending == null) {
                if (defer && mixer.renderWaitMs(device, t.toLong(), 0, channels) > 0) { waits++; break }
                pending = mixer.renderCycle(t.toLong(), 7680, channels).copyOf()
                pendingReal = speech.buffer.lastRenderRealFrames
                realFrames += pendingReal
            }
            if (device.blocks.size == 16) break
            val before = device.writtenFrames
            device.write(pending, 0, 960)
            mixer.recordRealOutput(device, device.outputEpoch, before, false, channels)
            device.blocks.addLast(pendingReal)
            pending = null
        }
        check(loops < 40)
    }
    val first = played.indexOfFirst { it > 0 }
    val last = played.indexOfLast { it > 0 }
    val missing = played.subList(first, last + 1).count { it == 0 } * 10
    return QueueResult(speech.buffer.concealedFrames, missing, realFrames, queueUnderruns,
        music?.buffer?.concealedFrames ?: 0, waits)
}

/** Run the real owner loop across a flush: a new track cannot wait for progress
 * before enough PCM has been submitted to satisfy its start threshold. */
internal fun pcmRenderResumeRegression() {
    fun read(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.get(owner)
    fun set(owner: Any, name: String, value: Any) = owner.javaClass.getDeclaredField(name).apply {
        isAccessible = true
    }.set(owner, value)
    fun await(label: String, ready: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!ready()) { check(System.nanoTime() < deadline) { label }; Thread.sleep(2) }
    }
    val device = ResumeOutput()
    val output = RecoveringPcmOutput(device, { error("unexpected reopen") },
        android.os.SystemClock::elapsedRealtime, {})
    val mixer = AudioMixer()
    val channel = mixer.registerChannel(4, 48000, 2)
    val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
    val method = AudioMixer::class.java.getDeclaredMethod("mixLoop").apply { isAccessible = true }
    val worker = Thread({ try { method.invoke(mixer) } catch (e: Throwable) {
        if (e.cause !is InterruptedException) failure.set(e)
    } }, "deadline-resume-test")
    set(mixer, "output", output)
    set(mixer, "mixThread", worker)
    (read(mixer, "running") as java.util.concurrent.atomic.AtomicBoolean).set(true)
    val gate = read(mixer, "renderDeadline")!!
    fun feed(ms: Int) {
        var remaining = ms
        while (remaining > 0) {
            val packetMs = minOf(50, remaining)
            val bytes = ByteArray(packetMs * 48 * 4) { if (it % 2 == 0) 100 else 0 }
            mixer.noteArrival(channel, packetMs * 48, android.os.SystemClock.elapsedRealtime())
            mixer.feed(channel, bytes, 0, bytes.size)
            remaining -= packetMs
        }
    }
    try {
        feed(400); mixer.finishChannel(channel)
        worker.start()
        await("first output pause") { device.pauses == 1 }
        await("pause clears render clock") { read(gate, "lastProgressMs") == -1L }
        device.allowProgress = false
        mixer.prepareChannel(channel)
        feed(120)
        await("cold resumed output fills despite no playback progress") {
            device.starts == 2 && device.writtenFrames >= 7680
        }
        check(device.playedFrames() == 0L)
        check(read(gate, "lastProgressMs") == -1L)
        device.allowProgress = true
        feed(500)
        await("fresh head progress after resume") { (read(gate, "lastProgressMs") as Long) >= 0 }
        mixer.finishChannel(channel)
        await("resumed prompt drains and pauses") { device.pauses == 2 }
        check(failure.get() == null) { "owner failure: ${failure.get()}" }
    } finally {
        mixer.stop()
        output.close()
    }
    println("PASS actual mixer pause/flush resets pacing and resumed output primes before borrowing fresh playback progress")
}

private class ResumeOutput : PcmOutput {
    override val name = "flush-on-pause"
    override val capacityFrames = 7680
    override val bufferFrames = 7680
    override val burstFrames = 480
    override val minimumBufferFrames = 7680
    override val underruns = 0
    @Volatile override var writtenFrames = 0L
    @Volatile var allowProgress = true
    @Volatile var starts = 0
    @Volatile var pauses = 0
    private var played = 0L
    private var playing = false
    private var primed = false
    private var lastMs = 0L
    private fun advance() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (playing && primed && allowProgress)
            played = minOf(writtenFrames, played + (now - lastMs).coerceAtLeast(0) * 48)
        lastMs = now
    }
    @Synchronized override fun playedFrames(): Long { advance(); return played }
    @Synchronized override fun write(data: ShortArray, offset: Int, count: Int): Int {
        advance()
        val frames = minOf(count / 2, (bufferFrames - writtenFrames + played).toInt().coerceAtLeast(0))
        writtenFrames += frames
        if (writtenFrames - played >= bufferFrames) primed = true
        return frames * 2
    }
    override fun setBufferFrames(frames: Int) = bufferFrames
    @Synchronized override fun start() { playing = true; starts++; lastMs = android.os.SystemClock.elapsedRealtime() }
    @Synchronized override fun pause() { playing = false; primed = false; writtenFrames = 0; played = 0; pauses++ }
    override fun close() {}
}
