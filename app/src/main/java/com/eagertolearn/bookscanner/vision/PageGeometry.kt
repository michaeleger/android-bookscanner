package com.eagertolearn.bookscanner.vision

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure page-geometry used by [DewarpFilter] and [StitcherEngine].
 *
 * Kept free of OpenCV so the station fit, the cylindrical source coordinate, and the gutter
 * valley score can be checked without a device or native libs.
 */
internal object PageGeometry {

    /** Fraction of page width, measured from the spine, that is treated as cylindrical. */
    const val CURL_ZONE = 0.25f

    /**
     * Surface tilt at the spine, in radians (~29.8°).
     *
     * Under the orthographic cylinder below this expands the gutter by 1.14×, which is the
     * magnitude the previous `spineRadiusFactor = 0.14` was aiming at. The outer page stays
     * face-on. A phone held over an open book is close enough to orthographic that perspective
     * is left out of this term.
     */
    const val SPINE_ANGLE_RAD = 0.52081803f

    const val STATION_SPINE = 0.08f
    const val STATION_MID = 0.50f
    const val STATION_OUTER = 0.90f

    /** Gray levels. Below this the search band has no gutter and the split stays at center. */
    const val MIN_GUTTER_PROMINENCE = 12f

    /**
     * Vertical baseline displacement as a fraction of image height:
     * `δ(u) = a2·u² + a1·u + a0`, with `u` the source-image distance from the spine
     * (`0` at the spine, `1` at the outer edge).
     */
    data class Quadratic(
        val a2: Float,
        val a1: Float,
        val a0: Float
    ) {
        fun eval(u: Float): Float = (a2 * u + a1) * u + a0
    }

    data class GutterSplit(
        val center: Int,
        val leftEdge: Int,
        val rightEdge: Int
    )

    /**
     * Exact quadratic through the three stations. [u] is spine distance, [d] is the
     * displacement in the same units the caller will add (fraction of image height).
     */
    fun fitQuadratic(
        u0: Float,
        d0: Float,
        u1: Float,
        d1: Float,
        u2: Float,
        d2: Float
    ): Quadratic {
        val u0s = u0 * u0
        val u1s = u1 * u1
        val u2s = u2 * u2
        // Eliminate a0 by subtracting the first station from the other two.
        val b1 = u1s - u0s
        val c1 = u1 - u0
        val e1 = d1 - d0
        val b2 = u2s - u0s
        val c2 = u2 - u0
        val e2 = d2 - d0
        val det = b1 * c2 - b2 * c1
        require(abs(det) > 1e-6f) { "Baseline stations are degenerate" }
        val a2 = (e1 * c2 - e2 * c1) / det
        val a1 = (b1 * e2 - b2 * e1) / det
        val a0 = d0 - a2 * u0s - a1 * u0
        return Quadratic(a2, a1, a0)
    }

    /**
     * Source-image spine distance for a flattened arc-length coordinate [arcU].
     *
     * The page is face-on beyond [CURL_ZONE]. Inside that zone the surface angle falls linearly
     * from [spineAngleRad] at the spine to 0. An orthographic camera compresses arc length by
     * `cos θ`, so the image position measured from the spine is
     *
     * `x_raw(s) = ∫ cos θ(s') ds'`, then normalized so the outer edge stays at 1.
     *
     * Near the spine `dx/ds = cos(α) / W < 1`: a destination step reads a smaller source step,
     * which spreads the foreshortened gutter back out. `α = 0` is the identity.
     */
    fun cylindricalImageU(
        arcU: Float,
        spineAngleRad: Float = SPINE_ANGLE_RAD,
        curlZone: Float = CURL_ZONE
    ): Float {
        val s = arcU.coerceIn(0f, 1f)
        if (spineAngleRad < 1e-3f || curlZone <= 0f) return s
        val alpha = spineAngleRad
        val zone = curlZone
        val sinAlpha = sin(alpha)
        val rawAtZone = (zone / alpha) * sinAlpha
        val totalRaw = rawAtZone + (1f - zone)
        val raw = if (s <= zone) {
            val remainingAngle = alpha * (1f - s / zone)
            (zone / alpha) * (sinAlpha - sin(remainingAngle))
        } else {
            rawAtZone + (s - zone)
        }
        return (raw / totalRaw).coerceIn(0f, 1f)
    }

    /**
     * Spine column from a full-width luminance profile (column means).
     *
     * The gutter is a narrow valley: dark, with bright page on both shoulders, and only a few
     * percent of the spread wide. The darkest column is not that — a figure, a rule, or a
     * black border inside the 35–65% band often is darker than the gutter shadow.
     *
     * Score at each local minimum is shoulder prominence after a light box smooth. Reject dips
     * shallower than [MIN_GUTTER_PROMINENCE], narrower than 0.4% of the width (rules), or wider
     * than 6% (a dark illustration). The split excludes the half-depth core of the winning
     * valley so the shadow is not left on either page. A flat or ambiguous profile stays at
     * the center with no inset.
     */
    fun spineGutter(profile: FloatArray): GutterSplit {
        val width = profile.size
        if (width < 20) {
            val mid = width / 2
            return GutterSplit(mid, mid, mid)
        }

        val smoothR = max(2, width / 400)
        val shoulder = max(8, width / 40)
        val smooth = boxSmooth(profile, smoothR)
        val start = (width * 0.35f).toInt().coerceIn(1, width - 2)
        val end = (width * 0.65f).toInt().coerceIn(start + 1, width - 1)
        val minWidth = max(8, (width * 0.004f).toInt())
        val maxWidth = max(minWidth, (width * 0.06f).toInt())
        val pageCenter = width / 2f

        var bestScore = Float.NEGATIVE_INFINITY
        var bestMid = width / 2
        var bestLeft = bestMid
        var bestRight = bestMid

        var x = start
        while (x < end) {
            val isMin = smooth[x] <= smooth[x - 1] && smooth[x] <= smooth[x + 1]
            if (!isMin) {
                x++
                continue
            }
            // Group only the flat floor. A darker column is a different dip, and the
            // surrounding page is a different (usually worthless) local minimum.
            var right = x
            while (right + 1 < end && abs(smooth[right + 1] - smooth[x]) <= 1f) {
                right++
            }
            val mid = (x + right) / 2
            val leftShoulder = maxIn(smooth, mid - shoulder, mid)
            val rightShoulder = maxIn(smooth, mid + 1, mid + 1 + shoulder)
            val prominence = min(leftShoulder, rightShoulder) - smooth[mid]
            if (prominence >= MIN_GUTTER_PROMINENCE) {
                val run = halfDepthRun(profile, mid, prominence)
                val valleyWidth = run.second - run.first + 1
                if (valleyWidth in minWidth..maxWidth) {
                    val runMid = (run.first + run.second) / 2
                    val penalty = 3f * abs(runMid - pageCenter) / (width * 0.15f)
                    val score = prominence - penalty
                    if (score > bestScore) {
                        bestScore = score
                        bestMid = runMid
                        val cap = (width * 0.03f).toInt()
                        bestLeft = max(runMid - cap, run.first)
                        bestRight = min(runMid + cap, run.second)
                    }
                }
            }
            x = right + 1
        }

        if (bestScore == Float.NEGATIVE_INFINITY) {
            val mid = width / 2
            return GutterSplit(mid, mid, mid)
        }
        val leftEdge = bestLeft.coerceIn(1, width - 2)
        val rightEdge = bestRight.coerceIn(leftEdge, width - 1)
        return GutterSplit(bestMid.coerceIn(leftEdge, rightEdge), leftEdge, rightEdge)
    }

    private fun boxSmooth(profile: FloatArray, radius: Int): FloatArray {
        val n = profile.size
        val prefix = FloatArray(n + 1)
        for (i in 0 until n) {
            prefix[i + 1] = prefix[i] + profile[i]
        }
        val out = FloatArray(n)
        for (i in 0 until n) {
            val a = max(0, i - radius)
            val b = min(n - 1, i + radius)
            out[i] = (prefix[b + 1] - prefix[a]) / (b - a + 1).toFloat()
        }
        return out
    }

    private fun maxIn(values: FloatArray, from: Int, until: Int): Float {
        val a = from.coerceAtLeast(0)
        val b = until.coerceAtMost(values.size)
        if (b <= a) return values[a.coerceIn(0, values.lastIndex)]
        var peak = Float.NEGATIVE_INFINITY
        for (i in a until b) {
            if (values[i] > peak) peak = values[i]
        }
        return peak
    }

    /** Inclusive column run around [x] that stays within half of [prominence] of the floor. */
    private fun halfDepthRun(profile: FloatArray, x: Int, prominence: Float): Pair<Int, Int> {
        val level = profile[x] + 0.5f * prominence
        var left = x
        while (left > 0 && profile[left - 1] <= level) left--
        var right = x
        val last = profile.lastIndex
        while (right < last && profile[right + 1] <= level) right++
        return left to right
    }
}
