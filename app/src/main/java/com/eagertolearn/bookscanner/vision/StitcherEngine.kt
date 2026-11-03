package com.eagertolearn.bookscanner.vision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Multi-shot spread registration, glare-suppressing fusion, and spine-gutter page splitter.
 *
 * Invariant compliance:
 * - Operates strictly disk-to-disk via [Imgcodecs.imread] and [Imgcodecs.imwrite];
 *   never allocates JVM [android.graphics.Bitmap] objects.
 * - Releases every OpenCV native [Mat] in explicit `finally` blocks.
 * - Fusion keeps one CV_32FC3 accumulator (the running sum) plus 8-bit weight, darkest-color,
 *   and luminance mats. It does not keep every warped shot resident, so a 4-shot 12MP burst
 *   stays one pipeline instead of four full float frames. The base frame is released after
 *   [resolveBurstConflicts], before the gutter profile is built. The native peak is the
 *   warp in the alignment loop (~350MB at 12MP). The division runs in row strips so it stays
 *   below that even with the base frame resident.
 */
class StitcherEngine {

    data class SplitSpreadResult(
        val spreadIndex: Int,
        val leftPageFile: File,
        val rightPageFile: File,
        val detectedGutterX: Int
    )

    /**
     * Aligns 3-4 handheld burst shots via ORB + RANSAC homography, blends them to suppress
     * overhead specular glare, detects the vertical spine-gutter trough, and writes left/right
     * page images directly to [outputDir].
     */
    suspend fun stitchAndSplitSpread(
        spreadIndex: Int,
        shotFiles: List<File>,
        outputDir: File
    ): SplitSpreadResult = withContext(Dispatchers.Default) {
        require(shotFiles.isNotEmpty()) { "shotFiles cannot be empty for spread #$spreadIndex" }
        if (!outputDir.exists()) outputDir.mkdirs()

        val baseMat = Imgcodecs.imread(shotFiles.first().absolutePath, Imgcodecs.IMREAD_COLOR)
        if (baseMat.empty()) {
            baseMat.release()
            throw IllegalStateException("Failed to decode reference shot: ${shotFiles.first().absolutePath}")
        }

        val accumulator = Mat()
        val weight = Mat()
        val darkest = Mat()
        val minGray = Mat()
        val maxGray = Mat()
        val fusedBgr = Mat()
        try {
            baseMat.convertTo(accumulator, CvType.CV_32FC3)
            weight.create(baseMat.rows(), baseMat.cols(), CvType.CV_8UC1)
            weight.setTo(Scalar(1.0))
            baseMat.copyTo(darkest)
            Imgproc.cvtColor(baseMat, minGray, Imgproc.COLOR_BGR2GRAY)
            minGray.copyTo(maxGray)

            if (shotFiles.size > 1) {
                accumulateAlignedShots(
                    baseMat = baseMat,
                    shotFiles = shotFiles,
                    accumulator = accumulator,
                    weight = weight,
                    darkest = darkest,
                    minGray = minGray,
                    maxGray = maxGray
                )
            }

            divideByWeight(accumulator, weight, fusedBgr)
            accumulator.release()
            weight.release()
            resolveBurstConflicts(fusedBgr, baseMat, darkest, minGray, maxGray)
            baseMat.release()
            darkest.release()
            minGray.release()
            maxGray.release()

            val gutter = detectSpineGutter(fusedBgr)
            val leftPageFile = File(outputDir, "spread_${spreadIndex}_left_raw.jpg")
            val rightPageFile = File(outputDir, "spread_${spreadIndex}_right_raw.jpg")
            writePage(fusedBgr, 0, gutter.leftEdge, leftPageFile)
            writePage(fusedBgr, gutter.rightEdge, fusedBgr.cols() - gutter.rightEdge, rightPageFile)

            return@withContext SplitSpreadResult(
                spreadIndex = spreadIndex,
                leftPageFile = leftPageFile,
                rightPageFile = rightPageFile,
                detectedGutterX = gutter.center
            )
        } finally {
            fusedBgr.release()
            maxGray.release()
            minGray.release()
            darkest.release()
            weight.release()
            accumulator.release()
            baseMat.release()
        }
    }

    /**
     * ORB runs on a long edge of [DETECT_LONG_EDGE], then match coordinates are scaled back
     * to full resolution before RANSAC so the reprojection threshold stays in full-res pixels.
     * Each accepted shot is accumulated only where its warped coverage mask is set, and feeds
     * the per-pixel min/max luminance used later to spot glare.
     */
    private fun accumulateAlignedShots(
        baseMat: Mat,
        shotFiles: List<File>,
        accumulator: Mat,
        weight: Mat,
        darkest: Mat,
        minGray: Mat,
        maxGray: Mat
    ) {
        val orb = ORB.create(1500)
        val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
        val noMask = Mat()
        val baseGray = Mat()
        val baseSmall = Mat()
        val baseKeypoints = MatOfKeyPoint()
        val baseDescriptors = Mat()
        try {
            Imgproc.cvtColor(baseMat, baseGray, Imgproc.COLOR_BGR2GRAY)
            val longEdge = max(baseMat.cols(), baseMat.rows()).toDouble()
            val detectScale = min(1.0, DETECT_LONG_EDGE / longEdge)
            val smallWidth = max(32, (baseMat.cols() * detectScale).roundToInt())
            val smallHeight = max(32, (baseMat.rows() * detectScale).roundToInt())
            val smallSize = Size(smallWidth.toDouble(), smallHeight.toDouble())
            Imgproc.resize(baseGray, baseSmall, smallSize, 0.0, 0.0, Imgproc.INTER_AREA)
            baseGray.release()
            val baseScaleX = smallWidth.toDouble() / baseMat.cols().toDouble()
            val baseScaleY = smallHeight.toDouble() / baseMat.rows().toDouble()
            orb.detectAndCompute(baseSmall, noMask, baseKeypoints, baseDescriptors)
            if (baseDescriptors.empty()) return

            for (i in 1 until shotFiles.size) {
                val candidateMat = Imgcodecs.imread(shotFiles[i].absolutePath, Imgcodecs.IMREAD_COLOR)
                val warped = Mat()
                val coverage = Mat()
                try {
                    if (candidateMat.empty()) continue
                    val aligned = alignToBase(
                        orb = orb,
                        matcher = matcher,
                        noMask = noMask,
                        baseSmallSize = smallSize,
                        baseScaleX = baseScaleX,
                        baseScaleY = baseScaleY,
                        baseKeypoints = baseKeypoints,
                        baseDescriptors = baseDescriptors,
                        candidateBgr = candidateMat,
                        targetSize = baseMat.size(),
                        dstWarped = warped,
                        dstMask = coverage
                    )
                    // The warp is the only copy needed from here on.
                    candidateMat.release()
                    if (!aligned) continue
                    Imgproc.accumulate(warped, accumulator, coverage)
                    Core.add(weight, Scalar(1.0), weight, coverage)
                    updateSpecularReferences(warped, coverage, darkest, minGray, maxGray)
                } finally {
                    coverage.release()
                    warped.release()
                    candidateMat.release()
                }
            }
        } finally {
            baseDescriptors.release()
            baseKeypoints.release()
            baseSmall.release()
            baseGray.release()
            noMask.release()
        }
    }

    private fun alignToBase(
        orb: ORB,
        matcher: DescriptorMatcher,
        noMask: Mat,
        baseSmallSize: Size,
        baseScaleX: Double,
        baseScaleY: Double,
        baseKeypoints: MatOfKeyPoint,
        baseDescriptors: Mat,
        candidateBgr: Mat,
        targetSize: Size,
        dstWarped: Mat,
        dstMask: Mat
    ): Boolean {
        if (baseDescriptors.empty()) return false

        val candidateGray = Mat()
        val candidateSmall = Mat()
        val candidateKeypoints = MatOfKeyPoint()
        val candidateDescriptors = Mat()
        val knnMatches = ArrayList<MatOfDMatch>()
        var srcPts: MatOfPoint2f? = null
        var dstPts: MatOfPoint2f? = null
        var homography: Mat? = null
        var inlierMask: Mat? = null
        val ones = Mat()
        var erodeKernel: Mat? = null

        try {
            Imgproc.cvtColor(candidateBgr, candidateGray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.resize(candidateGray, candidateSmall, baseSmallSize, 0.0, 0.0, Imgproc.INTER_AREA)
            // Full-res gray is not read again; free it before the warp, which is the stitch peak.
            candidateGray.release()
            orb.detectAndCompute(candidateSmall, noMask, candidateKeypoints, candidateDescriptors)
            if (candidateDescriptors.empty()) return false

            matcher.knnMatch(candidateDescriptors, baseDescriptors, knnMatches, 2)
            val goodMatches = ArrayList<org.opencv.core.DMatch>(knnMatches.size)
            for (pair in knnMatches) {
                val neighbors = pair.toArray()
                if (neighbors.size >= 2 && neighbors[0].distance < LOWE_RATIO * neighbors[1].distance) {
                    goodMatches.add(neighbors[0])
                }
            }
            if (goodMatches.size < MIN_GOOD_MATCHES) return false

            val candScaleX = candidateSmall.cols().toDouble() / candidateBgr.cols().toDouble()
            val candScaleY = candidateSmall.rows().toDouble() / candidateBgr.rows().toDouble()
            val candKp = candidateKeypoints.toArray()
            val baseKp = baseKeypoints.toArray()
            val srcPointList = ArrayList<Point>(goodMatches.size)
            val dstPointList = ArrayList<Point>(goodMatches.size)
            for (match in goodMatches) {
                val query = candKp[match.queryIdx].pt
                val train = baseKp[match.trainIdx].pt
                srcPointList.add(Point(query.x / candScaleX, query.y / candScaleY))
                dstPointList.add(Point(train.x / baseScaleX, train.y / baseScaleY))
            }

            srcPts = MatOfPoint2f(*srcPointList.toTypedArray())
            dstPts = MatOfPoint2f(*dstPointList.toTypedArray())
            inlierMask = Mat()
            homography = Calib3d.findHomography(
                srcPts,
                dstPts,
                Calib3d.RANSAC,
                RANSAC_REPROJ_PX,
                inlierMask
            )
            if (homography == null || homography.empty()) return false
            if (Core.countNonZero(inlierMask) < MIN_GOOD_MATCHES) return false
            if (!homographyIsPlausible(homography, targetSize.width.toInt(), targetSize.height.toInt())) {
                return false
            }

            ones.create(candidateBgr.rows(), candidateBgr.cols(), CvType.CV_8UC1)
            ones.setTo(Scalar(255.0))
            Imgproc.warpPerspective(
                candidateBgr,
                dstWarped,
                homography,
                targetSize,
                Imgproc.INTER_LINEAR,
                Core.BORDER_CONSTANT,
                Scalar(0.0, 0.0, 0.0)
            )
            Imgproc.warpPerspective(
                ones,
                dstMask,
                homography,
                targetSize,
                Imgproc.INTER_NEAREST,
                Core.BORDER_CONSTANT,
                Scalar(0.0)
            )
            // Drop the rim where linear sampling blended valid pixels with the black border.
            erodeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.erode(dstMask, dstMask, erodeKernel)
            return !dstWarped.empty()
        } finally {
            erodeKernel?.release()
            ones.release()
            inlierMask?.release()
            homography?.release()
            dstPts?.release()
            srcPts?.release()
            for (pair in knnMatches) {
                pair.release()
            }
            candidateDescriptors.release()
            candidateKeypoints.release()
            candidateSmall.release()
            candidateGray.release()
        }
    }

    /**
     * A handheld burst is a small nudge of the same page. Reject homographies that scale,
     * translate, or shear it into a different document.
     */
    private fun homographyIsPlausible(homography: Mat, width: Int, height: Int): Boolean {
        if (homography.rows() != 3 || homography.cols() != 3) return false
        val h00 = homography.get(0, 0)[0]
        val h01 = homography.get(0, 1)[0]
        val h02 = homography.get(0, 2)[0]
        val h10 = homography.get(1, 0)[0]
        val h11 = homography.get(1, 1)[0]
        val h12 = homography.get(1, 2)[0]
        val h20 = homography.get(2, 0)[0]
        val h21 = homography.get(2, 1)[0]
        val det = h00 * h11 - h01 * h10
        if (det < 0.6 || det > 1.6) return false
        if (abs(h02) > width * 0.15) return false
        if (abs(h12) > height * 0.15) return false
        if (abs(h20) * width > 0.2) return false
        if (abs(h21) * height > 0.2) return false
        return true
    }

    /**
     * Tracks the darkest valid color and the max luminance. Black warp borders stay out via
     * [validMask], so they cannot win the minimum.
     */
    private fun updateSpecularReferences(
        warped: Mat,
        validMask: Mat,
        darkest: Mat,
        minGray: Mat,
        maxGray: Mat
    ) {
        val candGray = Mat()
        val darker = Mat()
        val darkerValid = Mat()
        val raised = Mat()
        try {
            Imgproc.cvtColor(warped, candGray, Imgproc.COLOR_BGR2GRAY)
            Core.compare(candGray, minGray, darker, Core.CMP_LT)
            Core.bitwise_and(darker, validMask, darkerValid)
            warped.copyTo(darkest, darkerValid)
            candGray.copyTo(minGray, darkerValid)
            Core.max(maxGray, candGray, raised)
            raised.copyTo(maxGray, validMask)
        } finally {
            raised.release()
            darkerValid.release()
            darker.release()
            candGray.release()
        }
    }

    /**
     * Divides the accumulator by the per-pixel weight in row strips of [DIVIDE_STRIP_ROWS],
     * in place, and narrows each strip straight into [dst8]. The base frame is still resident
     * here (it is read by [resolveBurstConflicts]), so full-frame temporaries are not affordable:
     * a full-frame CV_32FC1 weight plus one CV_32FC1 plane plus three 8-bit planes put this
     * step at ~384MB native at 12MP, above the alignment loop. Strip temporaries are ~16MB.
     *
     * The accumulator is consumed (overwritten with the mean) and must not be reused.
     */
    private fun divideByWeight(accumulator: Mat, weight: Mat, dst8: Mat) {
        val rows = accumulator.rows()
        val cols = accumulator.cols()
        dst8.create(rows, cols, CvType.CV_8UC3)
        val weight32 = Mat()
        val weight3 = Mat()
        try {
            var y = 0
            while (y < rows) {
                val stripRows = min(DIVIDE_STRIP_ROWS, rows - y)
                val roi = Rect(0, y, cols, stripRows)
                val accStrip = accumulator.submat(roi)
                val weightStrip = weight.submat(roi)
                val dstStrip = dst8.submat(roi)
                try {
                    weightStrip.convertTo(weight32, CvType.CV_32FC1)
                    Core.merge(listOf(weight32, weight32, weight32), weight3)
                    Core.divide(accStrip, weight3, accStrip)
                    // dstStrip already has this size and type, so convertTo writes into dst8.
                    accStrip.convertTo(dstStrip, CvType.CV_8UC3)
                } finally {
                    dstStrip.release()
                    weightStrip.release()
                    accStrip.release()
                }
                y += stripRows
            }
        } finally {
            weight3.release()
            weight32.release()
        }
    }

    /**
     * Separates glare from misregistered text. The per-pixel rule is [PageGeometry.fusionChoice].
     *
     * Where one shot sees ink and another sees paper, the mean doubles the glyph and the
     * darkest color would stamp that ink onto the paper. Those pixels keep [base]. A highlight
     * is the other case: the darker shot is still paper and the lift is modest. A 5×5 opening
     * drops thin edge halos that pass the gray test. If the opened mask covers more than
     * [MAX_SPECULAR_FRACTION] of the frame, the exposure itself changed and the mean is kept.
     */
    private fun resolveBurstConflicts(
        fused: Mat,
        base: Mat,
        darkest: Mat,
        minGray: Mat,
        maxGray: Mat
    ) {
        val range = Mat()
        val maskA = Mat()
        val maskB = Mat()
        var kernel: Mat? = null
        try {
            Core.subtract(maxGray, minGray, range)

            Imgproc.threshold(minGray, maskA, PageGeometry.INK_GRAY.toDouble(), 255.0, Imgproc.THRESH_BINARY_INV)
            Imgproc.threshold(range, maskB, PageGeometry.MISALIGN_RANGE.toDouble(), 255.0, Imgproc.THRESH_BINARY)
            Core.bitwise_and(maskA, maskB, maskA)
            base.copyTo(fused, maskA)

            Imgproc.threshold(maxGray, maskA, PageGeometry.SPECULAR_GRAY.toDouble(), 255.0, Imgproc.THRESH_BINARY)
            Imgproc.threshold(minGray, maskB, PageGeometry.PAPER_GRAY.toDouble(), 255.0, Imgproc.THRESH_BINARY)
            Core.bitwise_and(maskA, maskB, maskA)
            Imgproc.threshold(range, maskB, PageGeometry.GLARE_RANGE.toDouble(), 255.0, Imgproc.THRESH_BINARY)
            Core.bitwise_and(maskA, maskB, maskA)
            Imgproc.threshold(range, maskB, PageGeometry.MAX_GLARE_LIFT.toDouble(), 255.0, Imgproc.THRESH_BINARY_INV)
            Core.bitwise_and(maskA, maskB, maskA)

            kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0))
            Imgproc.morphologyEx(maskA, maskB, Imgproc.MORPH_OPEN, kernel)

            val pixels = fused.rows() * fused.cols()
            if (pixels <= 0) return
            val fraction = Core.countNonZero(maskB).toDouble() / pixels.toDouble()
            if (fraction > 0.0 && fraction <= MAX_SPECULAR_FRACTION) {
                darkest.copyTo(fused, maskB)
            }
        } finally {
            kernel?.release()
            maskB.release()
            maskA.release()
            range.release()
        }
    }

    private fun detectSpineGutter(spreadBgr: Mat): PageGeometry.GutterSplit {
        val width = spreadBgr.cols()
        val height = spreadBgr.rows()
        if (width < 20 || height < 20) {
            val mid = width / 2
            return PageGeometry.GutterSplit(mid, mid, mid)
        }

        val top = (height * 0.15).toInt()
        val roiHeight = (height * 0.70).toInt().coerceAtLeast(1).coerceAtMost(height - top)
        val band = spreadBgr.submat(Rect(0, top, width, roiHeight))
        val bandGray = Mat()
        val colMeans = Mat()
        try {
            Imgproc.cvtColor(band, bandGray, Imgproc.COLOR_BGR2GRAY)
            Core.reduce(bandGray, colMeans, 0, Core.REDUCE_AVG, CvType.CV_32F)
            val profile = FloatArray(colMeans.cols())
            colMeans.get(0, 0, profile)
            return PageGeometry.spineGutter(profile)
        } finally {
            colMeans.release()
            bandGray.release()
            band.release()
        }
    }

    private fun writePage(fusedBgr: Mat, x: Int, pageWidth: Int, outputFile: File) {
        val safeX = x.coerceIn(0, fusedBgr.cols() - 1)
        val safeWidth = pageWidth.coerceIn(1, fusedBgr.cols() - safeX)
        val page = fusedBgr.submat(Rect(safeX, 0, safeWidth, fusedBgr.rows()))
        try {
            check(Imgcodecs.imwrite(outputFile.absolutePath, page)) {
                "Failed to write ${outputFile.absolutePath}"
            }
        } finally {
            page.release()
        }
    }

    private companion object {
        const val MIN_GOOD_MATCHES = 12
        const val LOWE_RATIO = 0.75
        const val RANSAC_REPROJ_PX = 4.0
        const val DETECT_LONG_EDGE = 960.0

        /** Rows per [divideByWeight] strip: ~12MB of CV_32FC3 temporaries at a 4000px width. */
        const val DIVIDE_STRIP_ROWS = 256

        /** Above this fraction the brightness change is exposure, not a highlight. */
        const val MAX_SPECULAR_FRACTION = 0.35
    }
}
