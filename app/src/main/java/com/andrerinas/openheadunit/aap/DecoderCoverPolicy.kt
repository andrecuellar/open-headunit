package com.andrerinas.openheadunit.aap

/**
 * How long the projection keeps its decoder after the activity is stopped.
 *
 * A reverse camera, or any other app over the projection, stops the activity for a moment. Releasing
 * the decoder there costs a black screen on the way back: it has to be rebuilt and the phone has to
 * be asked for a keyframe before anything shows again. Holding it through a short cover keeps the
 * picture alive, and a longer stay in the background still releases it.
 */
object DecoderCoverPolicy {

    /** Covers shorter than this keep the decoder running. */
    const val KEEP_DECODER_MS = 20_000L

    /** True when a stopped activity should schedule the decoder release. */
    fun shouldReleaseOnCover(inPictureInPicture: Boolean, changingConfigurations: Boolean): Boolean =
        !inPictureInPicture && !changingConfigurations
}
