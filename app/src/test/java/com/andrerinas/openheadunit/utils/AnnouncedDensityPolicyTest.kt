package com.andrerinas.openheadunit.utils

import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.utils.AnnouncedDensityPolicy.Reason
import com.andrerinas.openheadunit.utils.SystemOptimizer.DisplaySizePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncedDensityPolicyTest {

    private val small = DisplaySizePreset.SMALL_7_8

    @Test
    fun `the floor's maximum density per codec size`() {
        assertEquals(170, AnnouncedDensityPolicy.huigMaxDpi(800, 480))
        assertEquals(256, AnnouncedDensityPolicy.huigMaxDpi(1280, 720))
        assertEquals(384, AnnouncedDensityPolicy.huigMaxDpi(1920, 1080))
    }

    @Test
    fun `171 at 800x480 is announced as 170`() {
        val d = AnnouncedDensityPolicy.decide(171, small, 800, 480)
        assertEquals(170, d.announced)
        assertEquals(171, d.requested)
        assertEquals(Reason.HEIGHT_FLOOR, d.reason)
    }

    @Test
    fun `175 is clamped and 160 is kept at 800x480`() {
        assertEquals(170, AnnouncedDensityPolicy.decide(175, small, 800, 480).announced)
        val kept = AnnouncedDensityPolicy.decide(160, small, 800, 480)
        assertEquals(160, kept.announced)
        assertEquals(Reason.AS_STORED, kept.reason)
    }

    @Test
    fun `a width bound canvas names the width floor`() {
        val d = AnnouncedDensityPolicy.decide(230, small, 1000, 700)
        assertEquals(213, d.announced)
        assertEquals(Reason.WIDTH_FLOOR, d.reason)
    }

    @Test
    fun `values that meet the floor at 720p stay`() {
        for (v in intArrayOf(215, 240)) {
            val d = AnnouncedDensityPolicy.decide(v, small, 1280, 720)
            assertEquals(v, d.announced)
            assertEquals(Reason.AS_STORED, d.reason)
        }
    }

    @Test
    fun `automatic is codec based`() {
        val d = AnnouncedDensityPolicy.decide(0, small, 800, 480)
        assertEquals(150, d.announced)
        assertEquals(Reason.AUTOMATIC, d.reason)
        assertEquals(113, AnnouncedDensityPolicy.decide(0, DisplaySizePreset.STANDARD_9_10, 800, 480).announced)
    }

    @Test
    fun `portrait is clamped to the narrow layout's width`() {
        val d = AnnouncedDensityPolicy.decide(240, small, 480, 800)
        assertEquals(105, d.announced)
        assertEquals(Reason.PORTRAIT_FLOOR, d.reason)
    }

    @Test
    fun `the rig's portrait edge on an 800 px panel with a 720 px codec`() {
        // Measured: Maps took touch at 175 (phone w731) and none at 176 (w727) with PAR 11111.
        assertEquals(800 to 1280, AnnouncedDensityPolicy.phoneCanvas(720, 1280, 11111))
        val kept = AnnouncedDensityPolicy.decide(175, small, 720, 1280, 11111)
        assertEquals(175, kept.announced)
        assertEquals(Reason.PORTRAIT, kept.reason)
        for (v in intArrayOf(176, 190, 198)) {
            val d = AnnouncedDensityPolicy.decide(v, small, 720, 1280, 11111)
            assertEquals(175, d.announced)
            assertEquals(Reason.PORTRAIT_FLOOR, d.reason)
        }
    }

    @Test
    fun `square pixels lower the portrait edge`() {
        // Measured: 175 failed and 157 worked with PAR 10001 (phone w658 and w734).
        assertEquals(157, AnnouncedDensityPolicy.decide(175, small, 720, 1280, 10001).announced)
        assertEquals(157, AnnouncedDensityPolicy.decide(157, small, 720, 1280, 10001).announced)
    }

    @Test
    fun `automatic portrait stays under the edge`() {
        val d = AnnouncedDensityPolicy.decide(0, small, 720, 1280)
        assertEquals(157, d.announced)
        assertEquals(Reason.PORTRAIT, d.reason)
    }

    @Test
    fun `the portrait boundary is exact`() {
        for (w in listOf(720, 800, 1080)) {
            val n = AnnouncedDensityPolicy.portraitMaxDpi(w)
            assertTrue("$w at $n", AnnouncedDensityPolicy.canvasDp(w, n) >= 730)
            assertTrue("$w at ${n + 1}", AnnouncedDensityPolicy.canvasDp(w, n + 1) < 730)
        }
    }

    @Test
    fun `the phone canvas grows on the stretched axis only`() {
        assertEquals(1280 to 720, AnnouncedDensityPolicy.phoneCanvas(1280, 720, 10000))
        assertEquals(1280 to 720, AnnouncedDensityPolicy.phoneCanvas(1280, 720, 0))
        // Measured in landscape on the same rig: contentArea 1280x800 for a 1280x720 codec.
        assertEquals(1280 to 800, AnnouncedDensityPolicy.phoneCanvas(1280, 720, 9000))
    }

    @Test
    fun `the preview aspect ratio follows the manual override`() {
        assertEquals(10001, AnnouncedDensityPolicy.pixelAspectE4(Settings.VideoFitMode.FILL, 800, 1280, 1280, 720, 10001))
        val derived = AnnouncedDensityPolicy.pixelAspectE4(Settings.VideoFitMode.FILL, 800, 1280, 1280, 720, 10000)
        assertEquals(
            ProjectionGeometryPolicy.pixelAspectRatioE4(Settings.VideoFitMode.FILL, 800, 1280, 720, 1280, 0, 0), derived
        )
    }

    @Test
    fun `unknown geometry keeps the stored value or 160`() {
        assertEquals(171, AnnouncedDensityPolicy.decide(171, small, 0, 0).announced)
        assertEquals(160, AnnouncedDensityPolicy.decide(0, small, 0, 0).announced)
    }

    @Test
    fun `margins shrink the visible area the floor is checked on`() {
        assertEquals(213, AnnouncedDensityPolicy.decide(213, small, 1024, 600).announced)
        assertEquals(213, AnnouncedDensityPolicy.decide(215, small, 1024, 600).announced)
    }

    @Test
    fun `automatic keeps the canvas at or above the floor for every size`() {
        for (r in Settings.Resolution.values()) {
            if (r.width <= r.height) continue
            for (p in DisplaySizePreset.values()) {
                val dpi = AnnouncedDensityPolicy.decide(0, p, r.width, r.height).announced
                assertTrue("$p ${r.resName} -> $dpi", AnnouncedDensityPolicy.canvasDp(r.width, dpi) >= 748)
                assertTrue("$p ${r.resName} -> $dpi", AnnouncedDensityPolicy.canvasDp(r.height, dpi) >= 450)
            }
        }
    }

    @Test
    fun `the fallback density scales by the width ratio`() {
        assertEquals(160, AnnouncedDensityPolicy.fallbackDensity(240, 1920, 1280))
        assertEquals(120, AnnouncedDensityPolicy.fallbackDensity(240, 2560, 1280))
        assertEquals(80, AnnouncedDensityPolicy.fallbackDensity(240, 3840, 1280))
        assertEquals(240, AnnouncedDensityPolicy.fallbackDensity(240, 0, 1280))
    }

    @Test
    fun `the fallback keeps the primary dp canvas`() {
        val primary = Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080
        val fb = VideoFallbackPolicy.fallbackFor(primary)!!
        val (pw, ph) = VideoFallbackPolicy.sizeOf(primary)
        val (fw, fh) = VideoFallbackPolicy.sizeOf(fb)
        val m = VideoFallbackPolicy.scaledMargins(pw, ph, 120, 60, fb)
        val dpi = 240
        val fdpi = AnnouncedDensityPolicy.fallbackDensity(dpi, pw, fw)
        assertTrue(Math.abs(AnnouncedDensityPolicy.canvasDp(pw - 120, dpi) - AnnouncedDensityPolicy.canvasDp(fw - m.width, fdpi)) <= 1)
        assertTrue(Math.abs(AnnouncedDensityPolicy.canvasDp(ph - 60, dpi) - AnnouncedDensityPolicy.canvasDp(fh - m.height, fdpi)) <= 1)
    }

    @Test
    fun `describe pins the rig's grep targets`() {
        assertEquals(
            "170 (dp canvas 752x451, requested 171, clamped to the 450 dp height floor)",
            AnnouncedDensityPolicy.describe(AnnouncedDensityPolicy.decide(171, small, 800, 480), 800, 480)
        )
        assertEquals(
            "150 (dp canvas 853x512, requested automatic, codec-based)",
            AnnouncedDensityPolicy.describe(AnnouncedDensityPolicy.decide(0, small, 800, 480), 800, 480)
        )
        assertEquals(
            "175 (dp canvas 731x1170, requested 198, clamped to the 730 dp portrait width floor)",
            AnnouncedDensityPolicy.describe(AnnouncedDensityPolicy.decide(198, small, 720, 1280, 11111), 720, 1280, 11111)
        )
        assertEquals(
            "198 (dp canvas 646x1034, requested 198, the 730 dp portrait width floor overridden)",
            AnnouncedDensityPolicy.describe(
                AnnouncedDensityPolicy.decide(198, small, 720, 1280, 11111, floorOverride = true), 720, 1280, 11111
            )
        )
    }

    @Test
    fun `the override sends a stored portrait value above the floor`() {
        val d = AnnouncedDensityPolicy.decide(198, small, 720, 1280, 11111, floorOverride = true)
        assertEquals(AnnouncedDensityPolicy.Decision(198, 198, Reason.FLOOR_OVERRIDE), d)
    }

    @Test
    fun `the override leaves a value under the floor and Automatic alone`() {
        assertEquals(
            AnnouncedDensityPolicy.Decision(160, 160, Reason.PORTRAIT),
            AnnouncedDensityPolicy.decide(160, small, 720, 1280, 11111, floorOverride = true)
        )
        assertEquals(
            AnnouncedDensityPolicy.decide(0, small, 720, 1280, 11111),
            AnnouncedDensityPolicy.decide(0, small, 720, 1280, 11111, floorOverride = true)
        )
    }

    @Test
    fun `the override sends a stored landscape value above the HUIG floor`() {
        assertEquals(
            AnnouncedDensityPolicy.Decision(171, 171, Reason.FLOOR_OVERRIDE),
            AnnouncedDensityPolicy.decide(171, small, 800, 480, floorOverride = true)
        )
        assertEquals(
            "171 (dp canvas 748x449, requested 171, the 748x450 dp HUIG floor overridden)",
            AnnouncedDensityPolicy.describe(AnnouncedDensityPolicy.decide(171, small, 800, 480, floorOverride = true), 800, 480)
        )
        assertEquals(
            AnnouncedDensityPolicy.decide(0, small, 800, 480),
            AnnouncedDensityPolicy.decide(0, small, 800, 480, floorOverride = true)
        )
    }

    @Test
    fun `the widescreen maximum per codec width`() {
        assertEquals(103, AnnouncedDensityPolicy.widescreenMaxDpi(800))
        assertEquals(165, AnnouncedDensityPolicy.widescreenMaxDpi(1280))
        assertEquals(247, AnnouncedDensityPolicy.widescreenMaxDpi(1920))
        assertEquals(0, AnnouncedDensityPolicy.widescreenMaxDpi(0))
    }

    @Test
    fun `the widescreen boundary is exact`() {
        for (w in listOf(800, 1280, 1920)) {
            val n = AnnouncedDensityPolicy.widescreenMaxDpi(w)
            assertTrue("$w at $n", AnnouncedDensityPolicy.canvasDp(w, n) >= 1240)
            assertTrue("$w at ${n + 1}", AnnouncedDensityPolicy.canvasDp(w, n + 1) < 1240)
        }
    }

    @Test
    fun `the widescreen line never names a value the clamp lowers`() {
        for (r in Settings.Resolution.values().filter { it != Settings.Resolution.AUTO }) {
            val n = AnnouncedDensityPolicy.widescreenMaxDpi(r.width)
            assertTrue("${r.resName}", n <= AnnouncedDensityPolicy.huigMaxDpi(r.width, r.height))
            assertEquals(Reason.AS_STORED, AnnouncedDensityPolicy.decide(n, small, r.width, r.height).reason)
        }
    }

    @Test
    fun `the widescreen hint skips a portrait or unknown codec`() {
        assertEquals(103, AnnouncedDensityPolicy.widescreenHintDpi(800, 480))
        assertEquals(0, AnnouncedDensityPolicy.widescreenHintDpi(480, 800))
        assertEquals(0, AnnouncedDensityPolicy.widescreenHintDpi(0, 0))
    }

    @Test
    fun `a resolution that matches the panel is fully visible`() {
        assertEquals(800 to 480, AnnouncedDensityPolicy.visibleSize(Settings.VideoFitMode.FILL, 800, 480, 800, 480))
    }

    @Test
    fun `a resolution larger than a small panel loses its margins`() {
        assertEquals(1024 to 600, AnnouncedDensityPolicy.visibleSize(Settings.VideoFitMode.FILL, 1024, 600, 1280, 720))
        assertEquals(1024 to 720, AnnouncedDensityPolicy.visibleSize(Settings.VideoFitMode.CONTAIN, 1024, 768, 1280, 720))
    }

    @Test
    fun `the preview of a margined resolution sends what connect sends`() {
        val (w, h) = AnnouncedDensityPolicy.visibleSize(Settings.VideoFitMode.CONTAIN, 1024, 768, 1280, 720)
        assertTrue(AnnouncedDensityPolicy.decide(0, small, w, h).announced < AnnouncedDensityPolicy.decide(0, small, 1280, 720).announced)
    }

    @Test
    fun `the resolution turns to a portrait panel`() {
        assertEquals(480 to 800, AnnouncedDensityPolicy.visibleSize(Settings.VideoFitMode.FILL, 480, 800, 800, 480))
    }

    @Test
    fun `an unknown panel leaves the resolution whole`() {
        assertEquals(800 to 480, AnnouncedDensityPolicy.visibleSize(Settings.VideoFitMode.FILL, 0, 0, 800, 480))
    }

    @Test
    fun `a panel held in portrait previews a landscape projection`() {
        assertEquals(1440 to 720, AnnouncedDensityPolicy.orientPanel(720, 1440, portrait = false))
        assertEquals(720 to 1440, AnnouncedDensityPolicy.orientPanel(1440, 720, portrait = true))
        val (pw, ph) = AnnouncedDensityPolicy.orientPanel(720, 1440, portrait = false)
        assertEquals(800 to 480, AnnouncedDensityPolicy.visibleSize(Settings.VideoFitMode.FILL, pw, ph, 800, 480))
    }

    @Test
    fun `only an explicit portrait setting projects in portrait`() {
        for (o in Settings.ScreenOrientation.values()) {
            val portrait = o == Settings.ScreenOrientation.PORTRAIT || o == Settings.ScreenOrientation.PORTRAIT_REVERSE
            assertEquals(o.name, portrait, AnnouncedDensityPolicy.projectsPortrait(o))
        }
    }
}
