package com.eagertolearn.bookscanner.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

class PageGeometryTest {

    @Test
    fun quadraticInterpolatesSpineMidAndOuterStations() {
        val spine = 0.03f
        val outer = -0.01f
        val quadratic = PageGeometry.fitQuadratic(
            PageGeometry.STATION_SPINE, spine,
            PageGeometry.STATION_MID, 0f,
            PageGeometry.STATION_OUTER, outer
        )

        assertEquals(spine, quadratic.eval(PageGeometry.STATION_SPINE), 1e-5f)
        assertEquals(0f, quadratic.eval(PageGeometry.STATION_MID), 1e-5f)
        assertEquals(outer, quadratic.eval(PageGeometry.STATION_OUTER), 1e-5f)
    }

    @Test
    fun displacementIsMeasuredFromTheSpineNotTheOuterEdge() {
        val quadratic = PageGeometry.fitQuadratic(0.08f, 0.03f, 0.50f, 0f, 0.90f, -0.01f)
        val atOuterEdgeCoordinate = quadratic.eval(1f - 0.08f)
        assertTrue(abs(atOuterEdgeCoordinate - 0.03f) > 0.01f)
    }

    @Test
    fun zeroSpineAngleIsIdentity() {
        assertEquals(0f, PageGeometry.cylindricalImageU(0f, spineAngleRad = 0f), 1e-6f)
        assertEquals(0.4f, PageGeometry.cylindricalImageU(0.4f, spineAngleRad = 0f), 1e-6f)
        assertEquals(1f, PageGeometry.cylindricalImageU(1f, spineAngleRad = 0f), 1e-6f)
    }

    @Test
    fun cylinderExpandsTheGutterAndKeepsBothEdgesFixed() {
        assertEquals(0f, PageGeometry.cylindricalImageU(0f), 1e-5f)
        assertEquals(1f, PageGeometry.cylindricalImageU(1f), 1e-4f)

        var previous = -1f
        var arc = 0f
        while (arc <= 1f) {
            val image = PageGeometry.cylindricalImageU(arc)
            assertTrue("source spine-distance must increase, arc=$arc image=$image", image >= previous - 1e-6f)
            previous = image
            arc += 0.05f
        }

        val zone = PageGeometry.CURL_ZONE
        val imageAtZone = PageGeometry.cylindricalImageU(zone)
        assertTrue(
            "curl zone must occupy less source width than arc length ($imageAtZone vs $zone)",
            imageAtZone < zone
        )

        val derivativeAtSpine = PageGeometry.cylindricalImageU(0.01f) / 0.01f
        assertTrue("gutter must be expanded (dx/ds=$derivativeAtSpine)", derivativeAtSpine < 0.95f)
        assertTrue("gutter expansion must stay modest (dx/ds=$derivativeAtSpine)", derivativeAtSpine > 0.7f)
    }

    @Test
    fun gutterPrefersNarrowValleyOverDarkPlateauAndRule() {
        val width = 4000
        val page = 200f
        val profile = FloatArray(width) { page }
        val valley = (0.48f * width).toInt()
        for (i in (valley - 30) until (valley + 30)) profile[i] = page - 40f
        for (i in (0.55f * width).toInt() until (0.64f * width).toInt()) profile[i] = 40f
        val rule = (0.40f * width).toInt()
        profile[rule] = 0f
        profile[rule + 1] = 0f

        val split = PageGeometry.spineGutter(profile)
        assertEquals(0.48f, split.center / width.toFloat(), 0.015f)
        assertTrue(split.leftEdge < split.center)
        assertTrue(split.rightEdge > split.center)
    }

    @Test
    fun flatProfileSplitsAtCenterWithNoInset() {
        val split = PageGeometry.spineGutter(FloatArray(4000) { 180f })
        assertEquals(2000, split.center)
        assertEquals(split.center, split.leftEdge)
        assertEquals(split.center, split.rightEdge)
    }

    @Test
    fun thinRuleDoesNotMoveTheSplit() {
        val width = 4000
        val profile = FloatArray(width) { 180f }
        val rule = width / 2
        profile[rule - 1] = 0f
        profile[rule] = 0f
        profile[rule + 1] = 0f

        val split = PageGeometry.spineGutter(profile)
        assertEquals(width / 2, split.center)
        assertEquals(split.leftEdge, split.rightEdge)
    }

    @Test
    fun wideDarkBlockDoesNotHideARealGutter() {
        val width = 4000
        val page = 200f
        val profile = FloatArray(width) { page }
        for (i in (0.36f * width).toInt() until (0.46f * width).toInt()) profile[i] = 30f
        val gutter = (0.58f * width).toInt()
        for (i in (gutter - 20) until (gutter + 20)) profile[i] = page - 25f

        val split = PageGeometry.spineGutter(profile)
        assertEquals(0.58f, split.center / width.toFloat(), 0.02f)
    }

    @Test
    fun mildGutterBeatsADarkerNarrowColumn() {
        val width = 4000
        val page = 200f
        val profile = FloatArray(width) { page }
        val gutter = (0.51f * width).toInt()
        for (i in (gutter - 20) until (gutter + 20)) profile[i] = page - 22f
        val column = (0.42f * width).toInt()
        for (i in column until column + 4) profile[i] = 20f

        val split = PageGeometry.spineGutter(profile)
        assertEquals(0.51f, split.center / width.toFloat(), 0.02f)
    }

    @Test
    fun crossCorrelationTracksShiftPastHalfALinePitch() {
        val height = 1600
        val pitch = 47.0
        val reference = textRowProfile(height, pitch, shift = 0.0)
        val maxLag = (height * PageGeometry.MAX_SHIFT_FRACTION).toInt()
        // Half a line is 23.5px. 1% and 3% of this page are 16px and 48px, past the point
        // where a band centroid wraps. 48px is about one full pitch.
        val shifts = doubleArrayOf(0.0, 8.0, 16.0, 23.5, 48.0, -24.0, 12.4)
        for (shift in shifts) {
            val estimate = PageGeometry.verticalShiftPx(
                reference,
                textRowProfile(height, pitch, shift),
                maxLag
            )
            assertEquals("shift $shift px", shift.toFloat(), estimate.pixels, 0.5f)
            assertTrue("shift $shift confidence ${estimate.confidence}", estimate.confidence >= 0.30f)
        }

        val gained = textRowProfile(height, pitch, shift = 48.0).map { it * 0.7f + 30f }.toFloatArray()
        val gainEstimate = PageGeometry.verticalShiftPx(reference, gained, maxLag)
        assertEquals(48f, gainEstimate.pixels, 0.5f)
    }

    @Test
    fun unanchoredOrFlatProfilesAreNotShifted() {
        val flat = PageGeometry.verticalShiftPx(FloatArray(400) { 128f }, FloatArray(400) { 140f }, 24)
        assertEquals(0f, flat.pixels, 0f)

        val height = 800
        val pitch = 47.0
        val reference = textRowProfile(height, pitch, shift = 0.0, marginFrac = 0.0)
        val moving = textRowProfile(height, pitch, shift = 0.03 * height, marginFrac = 0.0)
        val bleed = PageGeometry.verticalShiftPx(reference, moving, (height * 0.06f).toInt())
        assertEquals(0f, bleed.pixels, 0f)
    }

    @Test
    fun wideGutterShadowStaysOffCenter() {
        val width = 4000
        for (sigmaFrac in floatArrayOf(0.03f, 0.05f)) {
            for (depth in floatArrayOf(20f, 30f)) {
                val profile = gaussianValley(width, centerFrac = 0.52f, sigmaFrac = sigmaFrac, depth = depth)
                val split = PageGeometry.spineGutter(profile)
                assertEquals(
                    "sigma=$sigmaFrac depth=$depth",
                    0.52f,
                    split.center / width.toFloat(),
                    0.01f
                )
                assertTrue(split.leftEdge < split.center)
                assertTrue(split.rightEdge > split.center)
            }
        }
    }

    @Test
    fun misalignedInkKeepsTheBaseShot() {
        assertEquals(PageGeometry.FusionChoice.BASE, PageGeometry.fusionChoice(40f, 250f))
        assertEquals(PageGeometry.FusionChoice.BASE, PageGeometry.fusionChoice(100f, 250f))
    }

    @Test
    fun glareOnPaperSelectsTheDarkestShot() {
        assertEquals(PageGeometry.FusionChoice.DARKEST, PageGeometry.fusionChoice(210f, 255f))
        assertEquals(PageGeometry.FusionChoice.DARKEST, PageGeometry.fusionChoice(190f, 250f))
    }

    @Test
    fun agreeingPixelsKeepTheMean() {
        assertEquals(PageGeometry.FusionChoice.MEAN, PageGeometry.fusionChoice(180f, 200f))
        assertEquals(PageGeometry.FusionChoice.MEAN, PageGeometry.fusionChoice(30f, 45f))
        // Cream paper under a hard clip is left as the mean: the darker value is not ink,
        // and it is not bright enough to call a highlight.
        assertEquals(PageGeometry.FusionChoice.MEAN, PageGeometry.fusionChoice(160f, 255f))
    }

    private fun textRowProfile(
        height: Int,
        pitch: Double,
        shift: Double,
        marginFrac: Double = 0.12,
        lineSigma: Double = 1.8,
        paper: Double = 200.0,
        ink: Double = 40.0
    ): FloatArray {
        val out = FloatArray(height) { paper.toFloat() }
        val top = height * marginFrac + shift
        val bottom = height - height * marginFrac + shift
        var line = top + pitch * 0.5
        while (line < bottom) {
            val y0 = max(0, (line - 4.0 * lineSigma).toInt())
            val y1 = min(height, (line + 4.0 * lineSigma).toInt() + 1)
            for (row in y0 until y1) {
                val delta = (row - line) / lineSigma
                val weight = exp(-0.5 * delta * delta)
                out[row] = (out[row] - (paper - ink) * weight).toFloat()
            }
            line += pitch
        }
        return out
    }

    private fun gaussianValley(
        width: Int,
        centerFrac: Float,
        sigmaFrac: Float,
        depth: Float,
        page: Float = 200f
    ): FloatArray {
        val center = centerFrac * width
        val sigma = sigmaFrac * width
        return FloatArray(width) { column ->
            val delta = (column - center) / sigma
            page - depth * exp(-0.5 * delta * delta).toFloat()
        }
    }
}
