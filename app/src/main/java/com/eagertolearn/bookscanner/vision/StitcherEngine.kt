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
import org.opencv.features2d.DescriptorMatcher
import org.opencv.features2d.ORB
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * Multi-shot spread registration, glare-suppressing fusion, and spine-gutter page splitter.
 *
 * Invariant compliance:
 * - Operates strictly disk-to-disk via [Imgcodecs.imread] and [Imgcodecs.imwrite];
 *   never allocates JVM [android.graphics.Bitmap] objects.
 * - Releases every OpenCV native [Mat] in explicit `finally` blocks.
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
        try {
            baseMat.convertTo(accumulator, CvType.CV_32FC3)
            var fusedCount = 1

            if (shotFiles.size > 1) {
                val orb = ORB.create(1500)
                val matcher = DescriptorMatcher.create(DescriptorMatcher.BRUTEFORCE_HAMMING)
                val noMask = Mat()
                val baseGray = Mat()
                val baseKeypoints = MatOfKeyPoint()
                val baseDescriptors = Mat()

                try {
                    Imgproc.cvtColor(baseMat, baseGray, Imgproc.COLOR_BGR2GRAY)
                    orb.detectAndCompute(baseGray, noMask, baseKeypoints, baseDescriptors)

                    for (i in 1 until shotFiles.size) {
                        val candidateFile = shotFiles[i]
                        val candidateMat = Imgcodecs.imread(candidateFile.absolutePath, Imgcodecs.IMREAD_COLOR)
                        val warpedCandidate = Mat()
                        try {
                            if (candidateMat.empty()) continue

                            val aligned = alignToBase(
                                orb = orb,
                                matcher = matcher,
                                noMask = noMask,
                                baseKeypoints = baseKeypoints,
                                baseDescriptors = baseDescriptors,
                                candidateBgr = candidateMat,
                                targetSize = baseMat.size(),
                                dstWarped = warpedCandidate
                            )

                            if (aligned) {
                                // Accumulates 8U into the 32F accumulator in place, avoiding a
                                // full-resolution CV_32FC3 temporary (~144MB at 12MP) per shot.
                                Imgproc.accumulate(warpedCandidate, accumulator)
                                fusedCount += 1
                            }
                        } finally {
                            warpedCandidate.release()
                            candidateMat.release()
                        }
                    }
                } finally {
                    baseDescriptors.release()
                    baseKeypoints.release()
                    baseGray.release()
                    noMask.release()
                }
            }

            val fusedBgr = Mat()
            try {
                accumulator.convertTo(fusedBgr, CvType.CV_8UC3, 1.0 / fusedCount.toDouble())

                val gutterX = detectSpineGutterColumn(fusedBgr)
                val leftRect = Rect(0, 0, gutterX, fusedBgr.rows())
                val rightRect = Rect(gutterX, 0, fusedBgr.cols() - gutterX, fusedBgr.rows())

                val leftPageFile = File(outputDir, "spread_${spreadIndex}_left_raw.jpg")
                val rightPageFile = File(outputDir, "spread_${spreadIndex}_right_raw.jpg")

                val leftSubMat = fusedBgr.submat(leftRect)
                try {
                    check(Imgcodecs.imwrite(leftPageFile.absolutePath, leftSubMat)) {
                        "Failed to write ${leftPageFile.absolutePath}"
                    }
                } finally {
                    leftSubMat.release()
                }

                val rightSubMat = fusedBgr.submat(rightRect)
                try {
                    check(Imgcodecs.imwrite(rightPageFile.absolutePath, rightSubMat)) {
                        "Failed to write ${rightPageFile.absolutePath}"
                    }
                } finally {
                    rightSubMat.release()
                }

                return@withContext SplitSpreadResult(
                    spreadIndex = spreadIndex,
                    leftPageFile = leftPageFile,
                    rightPageFile = rightPageFile,
                    detectedGutterX = gutterX
                )
            } finally {
                fusedBgr.release()
            }
        } finally {
            accumulator.release()
            baseMat.release()
        }
    }

    private fun alignToBase(
        orb: ORB,
        matcher: DescriptorMatcher,
        noMask: Mat,
        baseKeypoints: MatOfKeyPoint,
        baseDescriptors: Mat,
        candidateBgr: Mat,
        targetSize: org.opencv.core.Size,
        dstWarped: Mat
    ): Boolean {
        if (baseDescriptors.empty()) return false

        val candidateGray = Mat()
        val candidateKeypoints = MatOfKeyPoint()
        val candidateDescriptors = Mat()
        val matches = MatOfDMatch()
        var srcPts: MatOfPoint2f? = null
        var dstPts: MatOfPoint2f? = null
        var homography: Mat? = null

        try {
            Imgproc.cvtColor(candidateBgr, candidateGray, Imgproc.COLOR_BGR2GRAY)
            orb.detectAndCompute(candidateGray, noMask, candidateKeypoints, candidateDescriptors)
            if (candidateDescriptors.empty()) return false

            matcher.match(candidateDescriptors, baseDescriptors, matches)
            val matchArray = matches.toArray()
            if (matchArray.size < MIN_GOOD_MATCHES) return false

            val goodMatches = matchArray
                .sortedBy { it.distance }
                .take((matchArray.size * 0.25).toInt().coerceAtLeast(MIN_GOOD_MATCHES))

            val candKpArray = candidateKeypoints.toArray()
            val baseKpArray = baseKeypoints.toArray()

            val srcPointList = ArrayList<Point>(goodMatches.size)
            val dstPointList = ArrayList<Point>(goodMatches.size)
            for (m in goodMatches) {
                srcPointList.add(candKpArray[m.queryIdx].pt)
                dstPointList.add(baseKpArray[m.trainIdx].pt)
            }

            srcPts = MatOfPoint2f(*srcPointList.toTypedArray())
            dstPts = MatOfPoint2f(*dstPointList.toTypedArray())

            homography = Calib3d.findHomography(srcPts, dstPts, Calib3d.RANSAC, 4.0)
            if (homography == null || homography.empty()) return false

            Imgproc.warpPerspective(candidateBgr, dstWarped, homography, targetSize)
            return !dstWarped.empty()
        } finally {
            homography?.release()
            dstPts?.release()
            srcPts?.release()
            matches.release()
            candidateDescriptors.release()
            candidateKeypoints.release()
            candidateGray.release()
        }
    }

    /**
     * Locates the vertical spine-gutter shadow trough inside the central [35%, 65%] horizontal band.
     */
    private fun detectSpineGutterColumn(spreadBgr: Mat): Int {
        val width = spreadBgr.cols()
        val height = spreadBgr.rows()
        if (width < 20 || height < 20) return width / 2

        val startX = (width * 0.35).toInt()
        val endX = (width * 0.65).toInt()
        val bandRect = Rect(startX, 0, endX - startX, height)

        val bandBgr = spreadBgr.submat(bandRect)
        val bandGray = Mat()
        val colSums = Mat()
        try {
            Imgproc.cvtColor(bandBgr, bandGray, Imgproc.COLOR_BGR2GRAY)
            Core.reduce(bandGray, colSums, 0, Core.REDUCE_AVG, CvType.CV_32F)

            val profile = FloatArray(colSums.cols())
            colSums.get(0, 0, profile)

            var minVal = Float.MAX_VALUE
            var minOffset = profile.size / 2
            for (i in profile.indices) {
                if (profile[i] < minVal) {
                    minVal = profile[i]
                    minOffset = i
                }
            }
            return startX + minOffset
        } finally {
            colSums.release()
            bandGray.release()
            bandBgr.release()
        }
    }

    companion object {
        private const val MIN_GOOD_MATCHES = 12
    }
}
