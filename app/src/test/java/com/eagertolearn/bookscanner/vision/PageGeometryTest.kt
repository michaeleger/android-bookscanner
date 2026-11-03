package com.eagertolearn.bookscanner.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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
}
