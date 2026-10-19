package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderCoverPolicyTest {

    @Test
    fun `a plain cover releases the decoder once the grace period runs out`() {
        assertTrue(DecoderCoverPolicy.shouldReleaseOnCover(inPictureInPicture = false, changingConfigurations = false))
    }

    @Test
    fun `picture in picture keeps the decoder, the projection is still on screen`() {
        assertFalse(DecoderCoverPolicy.shouldReleaseOnCover(inPictureInPicture = true, changingConfigurations = false))
    }

    @Test
    fun `a configuration change keeps the decoder, the projection is coming straight back`() {
        assertFalse(DecoderCoverPolicy.shouldReleaseOnCover(inPictureInPicture = false, changingConfigurations = true))
    }

    @Test
    fun `the grace period is long enough for a camera and short enough for a real exit`() {
        assertTrue(DecoderCoverPolicy.KEEP_DECODER_MS in 5_000..30_000)
    }
}
