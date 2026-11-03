package com.eagertolearn.bookscanner.vision

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure page-geometry used by [DewarpFilter] and [StitcherEngine].
 *
 * Kept free of OpenCV so the station fit, the cylindrical source coordinate, the baseline
 * shift, and the gutter valley score can be checked without a device or native libs.
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

    /**
     * Largest vertical shift the correlator will search for, as a fraction of page height.
     * The remap applies the same cap. Station clamps are tighter (see DewarpFilter).
     */
    const val MAX_SHIFT_FRACTION = 0.06f

    /** Spine-station clamp, fraction of page height. Real gutter sag of 1–3% sits inside this. */
    const val SPINE_STATION_CLAMP = 0.04f

    /** Outer-station clamp, fraction of page height. */
    const val OUTER_STATION_CLAMP = 0.02f

    /** Gray levels. Below this the search band has no gutter and the split stays at center. */
    const val MIN_GUTTER_PROMINENCE = 12f

    /**
     * Per-pixel fusion rule. OpenCV `THRESH_BINARY` is strict `>`, and `THRESH_BINARY_INV`
     * is `<=`, so the comparisons here match the masks in `StitcherEngine`.
     *
     * Glare on paper is a modest lift: the darker shot is still above [PAPER_GRAY] and the
     * gap is at most [MAX_GLARE_LIFT]. Ink against paper is a larger gap with a dark minimum.
     * That is misalignment, and the base shot is kept, unless the base pixel itself is at
     * or above [SPECULAR_GRAY] and the brightest shot is above it. Then the base is the glare
     * the burst exists to remove, and the darkest shot still holds the ink. Anything else
     * keeps the weighted mean.
     */
    const val SPECULAR_GRAY = 245f
    const val PAPER_GRAY = 180f
    const val GLARE_RANGE = 20f
    const val MAX_GLARE_LIFT = 90f
    const val INK_GRAY = 140f
    const val MISALIGN_RANGE = 30f

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
     * Vertical shift of a strip relative to a reference strip, in pixels.
     * Positive means the moving strip's text sits lower (larger row index).
     * [confidence] is the fine normalized correlation, or 0 when the shift was rejected.
     */
    data class ShiftEstimate(
        val pixels: Float,
        val confidence: Float
    )

    enum class FusionChoice {
        /** Shots agree, or the disagreement is too mild to call. */
        MEAN,

        /** Highlight on paper, or glare on the reference shot over ink another shot still holds. */
        DARKEST,

        /** Ink in one shot and paper in another, and the reference shot is not clipped. */
        BASE
    }

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
     * How far [moving] is shifted relative to [reference], in pixels.
     *
     * The centroid of a periodic line profile aliases at half a line pitch, so a real sag of
     * one or two pitches comes back near zero or with the wrong sign. This correlates the
     * text-block envelopes first (the top and bottom margins are not periodic), then refines
     * inside half a pitch so the ±pitch sidelobes cannot win. A full-bleed strip with no
     * margin anchor, a flat strip, or an ambiguous peak returns 0 rather than a wrapped shift.
     *
     * Positive pixels mean features in [moving] lie below the same features in [reference].
     */
    fun verticalShiftPx(
        reference: FloatArray,
        moving: FloatArray,
        maxLag: Int
    ): ShiftEstimate {
        val length = min(reference.size, moving.size)
        if (length < 32 || maxLag < 1) return ShiftEstimate(0f, 0f)
        val ref = if (reference.size == length) reference else reference.copyOf(length)
        val mov = if (moving.size == length) moving else moving.copyOf(length)

        val pitch = linePitchPx(ref, length)
        if (pitch < 0) return ShiftEstimate(0f, 0f)

        val envelopeSigma = 0.85f * pitch
        val envRef = gaussianSmooth(ref, envelopeSigma)
        val envMov = gaussianSmooth(mov, envelopeSigma)
        if (!hasTextBlockAnchor(ref, envRef)) return ShiftEstimate(0f, 0f)

        val lagLimit = min(maxLag, length / 3)
        if (lagLimit < 1) return ShiftEstimate(0f, 0f)
        val scores = FloatArray(2 * lagLimit + 1)
        var bestLag = 0
        var best = Float.NEGATIVE_INFINITY
        for (lag in -lagLimit..lagLimit) {
            val score = normalizedCorrelation(envRef, envMov, lag, length)
            scores[lag + lagLimit] = score
            if (score > best) {
                best = score
                bestLag = lag
            }
        }
        if (abs(bestLag) == lagLimit || best < MIN_COARSE_NCC) return ShiftEstimate(0f, 0f)

        var second = Float.NEGATIVE_INFINITY
        for (lag in -lagLimit..lagLimit) {
            if (abs(lag - bestLag) <= pitch) continue
            val score = scores[lag + lagLimit]
            if (score > second) second = score
        }
        if (second > Float.NEGATIVE_INFINITY && best - second < MIN_COARSE_MARGIN) {
            return ShiftEstimate(0f, 0f)
        }

        val centerIndex = bestLag + lagLimit
        val coarse = bestLag + parabolicDelta(
            left = scores[centerIndex - 1],
            center = best,
            right = scores[centerIndex + 1]
        )

        val highPassSigma = 2f * pitch
        val highRef = subtractSmooth(ref, highPassSigma)
        val highMov = subtractSmooth(mov, highPassSigma)
        val halfPitch = max(2, (0.45f * pitch).toInt())
        val fineCenter = coarse.roundToInt()
        var fineLag = fineCenter
        var fineBest = Float.NEGATIVE_INFINITY
        val fineScores = HashMap<Int, Float>(halfPitch * 2 + 1)
        for (lag in (fineCenter - halfPitch)..(fineCenter + halfPitch)) {
            if (lag < -lagLimit || lag > lagLimit) continue
            val score = normalizedCorrelation(highRef, highMov, lag, length)
            fineScores[lag] = score
            if (score > fineBest) {
                fineBest = score
                fineLag = lag
            }
        }
        if (fineBest < MIN_FINE_NCC) return ShiftEstimate(0f, 0f)
        val pixels = fineLag + parabolicDelta(
            left = fineScores[fineLag - 1] ?: fineBest,
            center = fineBest,
            right = fineScores[fineLag + 1] ?: fineBest
        )
        return ShiftEstimate(pixels, fineBest)
    }

    /**
     * [baseGray] is the reference shot at this pixel. It is at most [maxGray], because the
     * max is taken over the reference and the aligned shots.
     */
    fun fusionChoice(minGray: Float, maxGray: Float, baseGray: Float): FusionChoice {
        val range = maxGray - minGray
        val glareOnPaper = maxGray > SPECULAR_GRAY &&
            minGray > PAPER_GRAY &&
            range > GLARE_RANGE &&
            range <= MAX_GLARE_LIFT
        if (glareOnPaper) return FusionChoice.DARKEST
        if (minGray <= INK_GRAY && range > MISALIGN_RANGE) {
            // base == SPECULAR_GRAY stays BASE unless some shot is strictly brighter, matching
            // the paper-glare test (max > SPECULAR_GRAY) and an 8-bit threshold at that level.
            if (baseGray >= SPECULAR_GRAY && maxGray > SPECULAR_GRAY) return FusionChoice.DARKEST
            return FusionChoice.BASE
        }
        return FusionChoice.MEAN
    }

    /**
     * Spine column from a full-width luminance profile (column means).
     *
     * The gutter is a valley with page on both shoulders. A thin rule and a narrow dark
     * column are rejected by width. A flat dark figure is rejected when it is wider than 6%
     * of the spread, because its floor occupies the whole run. A thick-book shadow is wider
     * than that and still curved, so shoulders are read by walking uphill until the slope
     * flattens (up to 12% of the width on each side) and the valley may be as wide as 16%.
     * The split excludes the half-depth core, capped at 8% of the width on each side.
     * A flat or ambiguous profile stays at the center with no inset.
     */
    fun spineGutter(profile: FloatArray): GutterSplit {
        val width = profile.size
        if (width < 20) {
            val mid = width / 2
            return GutterSplit(mid, mid, mid)
        }

        val smooth = boxSmooth(profile, max(2, width / 400))
        val start = (width * 0.35f).toInt().coerceIn(1, width - 2)
        val end = (width * 0.65f).toInt().coerceIn(start + 1, width - 1)
        val minWidth = max(8, (width * 0.004f).toInt())
        val maxNarrow = max(minWidth, (width * 0.06f).toInt())
        val maxWide = max(maxNarrow, (width * 0.16f).toInt())
        val sideLimit = (width * 0.12f).toInt().coerceAtLeast(1)
        val pageCenter = width / 2f
        val insetCap = (width * 0.08f).toInt()

        var bestScore = Float.NEGATIVE_INFINITY
        var bestMid = width / 2
        var bestLeft = bestMid
        var bestRight = bestMid

        var x = start
        while (x < end - 1) {
            val isMin = smooth[x] <= smooth[x - 1] && smooth[x] <= smooth[x + 1]
            if (!isMin) {
                x++
                continue
            }
            var right = x
            while (right + 1 < end && abs(smooth[right + 1] - smooth[x]) <= 1f) {
                right++
            }
            var floorIndex = x
            for (i in x..right) {
                if (profile[i] < profile[floorIndex]) floorIndex = i
            }
            val leftShoulder = shoulderIndex(smooth, floorIndex, -1, sideLimit)
            val rightShoulder = shoulderIndex(smooth, floorIndex, 1, sideLimit)
            val prominence = min(smooth[leftShoulder], smooth[rightShoulder]) - smooth[floorIndex]
            if (prominence >= MIN_GUTTER_PROMINENCE) {
                val run = halfDepthRun(profile, floorIndex, prominence)
                val valleyWidth = run.second - run.first + 1
                if (valleyWidth in minWidth..maxWide) {
                    val floorFraction = fractionNearFloor(profile, run.first, run.second, profile[floorIndex], prominence)
                    val wide = valleyWidth > maxNarrow
                    val plateau = floorFraction > MAX_FLOOR_FRACTION
                    if (!(wide && plateau)) {
                        val runMid = (run.first + run.second) / 2
                        val penalty = 3f * abs(runMid - pageCenter) / (width * 0.15f)
                        val score = prominence - penalty
                        if (score > bestScore) {
                            bestScore = score
                            bestMid = runMid
                            bestLeft = max(runMid - insetCap, run.first)
                            bestRight = min(runMid + insetCap, run.second)
                        }
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

    private fun linePitchPx(profile: FloatArray, length: Int): Int {
        val maxLag = min(length / 3, max(12, (length * 0.10f).toInt()))
        if (maxLag <= 8) return -1
        var prev = normalizedCorrelation(profile, profile, 6, length)
        var currentLag = 7
        var current = normalizedCorrelation(profile, profile, currentLag, length)
        var lag = 8
        while (lag <= maxLag) {
            val next = normalizedCorrelation(profile, profile, lag, length)
            if (current >= prev && current >= next && current > MIN_PITCH_NCC) {
                return currentLag
            }
            prev = current
            current = next
            currentLag = lag
            lag++
        }
        return -1
    }

    /**
     * Margins make the smoothed text block darker than the page above and below it.
     * A full-bleed periodic strip has a flat envelope, and correlating it aliases at the pitch.
     */
    private fun hasTextBlockAnchor(raw: FloatArray, envelope: FloatArray): Boolean {
        val rawRange = centralRange(raw)
        val envelopeRange = centralRange(envelope)
        if (envelopeRange < MIN_ENVELOPE_RANGE) return false
        if (rawRange <= 1f) return false
        return envelopeRange >= MIN_ENVELOPE_FRACTION * rawRange
    }

    private fun centralRange(values: FloatArray): Float {
        val from = (values.size * 0.08f).toInt()
        val until = (values.size * 0.92f).toInt().coerceAtLeast(from + 1).coerceAtMost(values.size)
        var low = Float.POSITIVE_INFINITY
        var high = Float.NEGATIVE_INFINITY
        for (i in from until until) {
            val value = values[i]
            if (value < low) low = value
            if (value > high) high = value
        }
        if (high < low) return 0f
        return high - low
    }

    private fun subtractSmooth(values: FloatArray, sigma: Float): FloatArray {
        val trend = gaussianSmooth(values, sigma)
        return FloatArray(values.size) { i -> values[i] - trend[i] }
    }

    private fun gaussianSmooth(values: FloatArray, sigma: Float): FloatArray {
        if (sigma < 0.6f) return values.copyOf()
        val radius = min(values.size / 2, max(1, (3f * sigma).roundToInt()))
        val kernel = FloatArray(radius * 2 + 1)
        val inv = 1.0 / (2.0 * sigma * sigma)
        var weight = 0.0
        for (i in kernel.indices) {
            val x = (i - radius).toDouble()
            val w = exp(-x * x * inv)
            kernel[i] = w.toFloat()
            weight += w
        }
        val norm = 1.0 / weight
        for (i in kernel.indices) {
            kernel[i] = (kernel[i] * norm).toFloat()
        }
        val n = values.size
        val out = FloatArray(n)
        for (i in 0 until n) {
            var acc = 0.0
            val base = i - radius
            for (k in kernel.indices) {
                var j = base + k
                if (j < 0) j = 0 else if (j >= n) j = n - 1
                acc += values[j] * kernel[k]
            }
            out[i] = acc.toFloat()
        }
        return out
    }

    /** Normalized cross-correlation of [a] against [b] shifted by [lag], on the overlap only. */
    private fun normalizedCorrelation(a: FloatArray, b: FloatArray, lag: Int, length: Int): Float {
        val i0 = max(0, -lag)
        val i1 = min(length, length - lag)
        val count = i1 - i0
        if (count < 8) return 0f
        var meanA = 0.0
        var meanB = 0.0
        for (i in i0 until i1) {
            meanA += a[i]
            meanB += b[i + lag]
        }
        meanA /= count
        meanB /= count
        var numerator = 0.0
        var denA = 0.0
        var denB = 0.0
        for (i in i0 until i1) {
            val da = a[i] - meanA
            val db = b[i + lag] - meanB
            numerator += da * db
            denA += da * da
            denB += db * db
        }
        val den = sqrt(denA * denB)
        if (den < 1e-8) return 0f
        return (numerator / den).toFloat()
    }

    private fun parabolicDelta(left: Float, center: Float, right: Float): Float {
        val denom = left - 2f * center + right
        if (abs(denom) < 1e-6f) return 0f
        return (0.5f * (left - right) / denom).coerceIn(-0.5f, 0.5f)
    }

    /**
     * Walk uphill from a valley floor. Stops at a new dip, at [limit], or once the slope
     * has flattened. The value at the stop is the shoulder used for prominence.
     */
    private fun shoulderIndex(smooth: FloatArray, start: Int, step: Int, limit: Int): Int {
        var x = start
        val floor = smooth[start]
        val hold = max(6, limit / 25)
        val last = smooth.lastIndex
        while (abs(x - start) < limit) {
            val next = x + step
            if (next < 0 || next > last) break
            if (smooth[next] < smooth[x] - 1.5f) break
            val risen = smooth[x] - floor
            val ahead = x + step * hold
            if (ahead in 0..last && risen > 4f) {
                val aheadRise = smooth[ahead] - smooth[x]
                if (aheadRise < max(0.5f, 0.08f * risen)) break
            }
            x = next
        }
        return x
    }

    private fun fractionNearFloor(
        profile: FloatArray,
        left: Int,
        right: Int,
        floor: Float,
        prominence: Float
    ): Float {
        val level = floor + 0.20f * prominence
        var count = 0
        for (i in left..right) {
            if (profile[i] <= level) count++
        }
        val span = right - left + 1
        if (span <= 0) return 1f
        return count.toFloat() / span.toFloat()
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

    private const val MIN_PITCH_NCC = 0.25f
    private const val MIN_COARSE_NCC = 0.25f
    private const val MIN_COARSE_MARGIN = 0.05f
    private const val MIN_FINE_NCC = 0.30f
    private const val MIN_ENVELOPE_RANGE = 4f
    private const val MIN_ENVELOPE_FRACTION = 0.04f

    /** Wide runs flatter than this are figures, not gutter shadows. */
    private const val MAX_FLOOR_FRACTION = 0.75f
}
