package com.eagertolearn.bookscanner.vision

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * Geometric polynomial baseline unrolling + cylindrical spine unwrapping + LAB L-channel CLAHE.
 *
 * Invariant compliance:
 * - Strictly avoids 1-bit binarization (Otsu/Sauvola).
 * - Applies CLAHE only to the L (luminance) channel in CIE LAB color space so yellow/pink/green
 *   highlighter ink and pencil marginalia are preserved intact.
 * - Releases every native OpenCV [Mat] in explicit `finally` blocks.
 * - Remap maps are full-frame CV_32FC1 (required by [Imgproc.remap]) plus two 1-row headers.
 *   They are released before the function returns. Column shifts are precomputed once; rows are
 *   filled with Core ops rather than a per-pixel `pow`.
 */
class DewarpFilter(
    private val claheClipLimit: Double = 2.0,
    private val claheTileGridSize: Double = 8.0
) {

    enum class PageSide {
        LEFT,
        RIGHT
    }

    /**
     * Baseline displacement `δ(u) = a2·u² + a1·u + a0` as a fraction of image height.
     * `u = 0` is the spine edge and `u = 1` is the outer margin, in the source image.
     * [spineAngleRad] is the cylindrical gutter tilt passed through to the horizontal unwrap.
     */
    data class BaselinePolynomial(
        val a2: Float,
        val a1: Float,
        val a0: Float,
        val spineAngleRad: Float = PageGeometry.SPINE_ANGLE_RAD
    )

    /**
     * Processes a single raw page file from disk:
     * 1. Estimates polynomial baseline curvature from horizontal text-line gradient ridges.
     * 2. Unwraps cylindrical spine foreshortening and baseline warp via [Imgproc.remap].
     * 3. Enhances local contrast on the LAB Luminance channel via CLAHE.
     * 4. Writes the output JPEG directly to [outputFile].
     */
    suspend fun processPage(
        rawPageFile: File,
        outputFile: File,
        pageSide: PageSide
    ): File = withContext(Dispatchers.Default) {
        val srcBgr = Imgcodecs.imread(rawPageFile.absolutePath, Imgcodecs.IMREAD_COLOR)
        if (srcBgr.empty()) {
            srcBgr.release()
            throw IllegalStateException("Cannot read raw page image: ${rawPageFile.absolutePath}")
        }

        val dewarpedBgr = Mat()
        val enhancedBgr = Mat()
        try {
            val poly = estimateBaselinePolynomial(srcBgr, pageSide)
            applyCylindricalPolynomialRemap(srcBgr, dewarpedBgr, poly, pageSide)
            applyLabLuminanceClahe(dewarpedBgr, enhancedBgr)

            check(Imgcodecs.imwrite(outputFile.absolutePath, enhancedBgr)) {
                "Failed to write dewarped page: ${outputFile.absolutePath}"
            }
            return@withContext outputFile
        } finally {
            enhancedBgr.release()
            dewarpedBgr.release()
            srcBgr.release()
        }
    }

    /**
     * Fits `δ(u) = a2·u² + a1·u + a0` through three source-image stations so the curve
     * actually interpolates them: spine, mid-page (displacement 0), and outer margin.
     *
     * The station value is the vertical center of mass of horizontal edges (|Sobel Y|) in the
     * middle half of the page. A column shift of every text line moves that center of mass by
     * the same amount, so δ is a displacement in units of image height, not a vertical scale.
     * Differences are clamped (±4% of page height at the spine, ±2% at the outer edge) so a
     * single rule or figure cannot throw the page.
     */
    fun estimateBaselinePolynomial(srcBgr: Mat, pageSide: PageSide): BaselinePolynomial {
        val gray = Mat()
        val gradY = Mat()
        val absGradY = Mat()
        try {
            Imgproc.cvtColor(srcBgr, gray, Imgproc.COLOR_BGR2GRAY)
            Imgproc.Sobel(gray, gradY, CvType.CV_16S, 0, 1, 3)
            Core.convertScaleAbs(gradY, absGradY)

            val cols = absGradY.cols()
            val rows = absGradY.rows()
            if (cols < 32 || rows < 32) {
                return BaselinePolynomial(a2 = 0f, a1 = 0f, a0 = 0f)
            }

            val sampleU = floatArrayOf(
                PageGeometry.STATION_SPINE,
                PageGeometry.STATION_MID,
                PageGeometry.STATION_OUTER
            )
            val centroidsY = FloatArray(3)
            val bandTop = (rows * 0.25).toInt()
            val bandHeight = (rows * 0.50).toInt().coerceAtLeast(1)

            for (idx in sampleU.indices) {
                val u = sampleU[idx]
                val normX = if (pageSide == PageSide.LEFT) 1f - u else u
                val stripCenterX = (normX * cols).toInt().coerceIn(8, cols - 9)
                val stripRect = org.opencv.core.Rect(stripCenterX - 8, bandTop, 16, bandHeight)
                val strip = absGradY.submat(stripRect)
                val rowMeans = Mat()
                try {
                    Core.reduce(strip, rowMeans, 1, Core.REDUCE_AVG, CvType.CV_32F)
                    val values = FloatArray(rowMeans.rows())
                    rowMeans.get(0, 0, values)

                    var weightedSum = 0f
                    var weightTotal = 1e-5f
                    for (r in values.indices) {
                        val w = values[r]
                        weightedSum += r * w
                        weightTotal += w
                    }
                    centroidsY[idx] = (weightedSum / weightTotal) / bandHeight.toFloat()
                } finally {
                    rowMeans.release()
                    strip.release()
                }
            }

            // Centroid is normalized to the middle band, which is half the page.
            val bandFraction = bandHeight.toFloat() / rows.toFloat()
            val dSpine = ((centroidsY[0] - centroidsY[1]) * bandFraction).coerceIn(-0.04f, 0.04f)
            val dOuter = ((centroidsY[2] - centroidsY[1]) * bandFraction).coerceIn(-0.02f, 0.02f)
            val quadratic = PageGeometry.fitQuadratic(
                u0 = sampleU[0],
                d0 = dSpine,
                u1 = sampleU[1],
                d1 = 0f,
                u2 = sampleU[2],
                d2 = dOuter
            )
            return BaselinePolynomial(
                a2 = quadratic.a2,
                a1 = quadratic.a1,
                a0 = quadratic.a0,
                spineAngleRad = PageGeometry.SPINE_ANGLE_RAD
            )
        } finally {
            absGradY.release()
            gradY.release()
            gray.release()
        }
    }

    /**
     * Builds `mapX` / `mapY` and runs [Imgproc.remap].
     *
     * Horizontal: destination arc-length `u` reads source spine-distance
     * [PageGeometry.cylindricalImageU], which is compressed toward the spine so the gutter
     * is expanded. Vertical: `mapY = y + δ(u_source) · (height − 1)`. δ is the fitted
     * baseline displacement at the source column being sampled, so a line that sags near the
     * spine is pulled back. It is not a scale about the page center — the centroid measurement
     * is a translation, and a scale would move the top and bottom in opposite directions.
     */
    fun applyCylindricalPolynomialRemap(
        srcBgr: Mat,
        dstBgr: Mat,
        poly: BaselinePolynomial,
        pageSide: PageSide
    ) {
        val width = srcBgr.cols()
        val height = srcBgr.rows()
        if (width < 2 || height < 2) {
            srcBgr.copyTo(dstBgr)
            return
        }

        val widthDenom = (width - 1).toFloat()
        val heightDenom = (height - 1).toFloat()
        val maxDelta = heightDenom * MAX_DISPLACEMENT
        val quadratic = PageGeometry.Quadratic(poly.a2, poly.a1, poly.a0)
        val sourceX = FloatArray(width)
        val deltaY = FloatArray(width)
        for (x in 0 until width) {
            val normX = x.toFloat() / widthDenom
            val arcU = if (pageSide == PageSide.LEFT) 1f - normX else normX
            val imageU = PageGeometry.cylindricalImageU(arcU, poly.spineAngleRad)
            val mappedNormX = if (pageSide == PageSide.LEFT) 1f - imageU else imageU
            sourceX[x] = mappedNormX * widthDenom
            deltaY[x] = (quadratic.eval(imageU) * heightDenom).coerceIn(-maxDelta, maxDelta)
        }

        // Empty headers first, allocated inside the try: a CvException from the second
        // full-frame allocation (the OOM case) must not leak the first.
        val mapX = Mat()
        val mapY = Mat()
        val xRow = Mat()
        val yRow = Mat()
        try {
            xRow.create(1, width, CvType.CV_32FC1)
            yRow.create(1, width, CvType.CV_32FC1)
            xRow.put(0, 0, sourceX)
            yRow.put(0, 0, deltaY)
            Core.repeat(xRow, height, 1, mapX)
            Core.repeat(yRow, height, 1, mapY)

            val maxY = (height - 1).toDouble()
            for (y in 0 until height) {
                val row = mapY.row(y)
                try {
                    Core.add(row, Scalar(y.toDouble()), row)
                    Imgproc.threshold(row, row, maxY, 0.0, Imgproc.THRESH_TRUNC)
                    Imgproc.threshold(row, row, 0.0, 0.0, Imgproc.THRESH_TOZERO)
                } finally {
                    row.release()
                }
            }

            Imgproc.remap(
                srcBgr,
                dstBgr,
                mapX,
                mapY,
                Imgproc.INTER_CUBIC,
                Core.BORDER_REPLICATE,
                Scalar(255.0, 255.0, 255.0)
            )
        } finally {
            yRow.release()
            xRow.release()
            mapY.release()
            mapX.release()
        }
    }

    /**
     * Applies Contrast Limited Adaptive Histogram Equalization (CLAHE) exclusively to the
     * L (luminance) channel in CIE LAB space, preserving A/B chrominance for highlighters & pencil.
     */
    fun applyLabLuminanceClahe(srcBgr: Mat, dstBgr: Mat) {
        val labMat = Mat()
        val channels = ArrayList<Mat>(3)
        val claheL = Mat()
        var clahe: org.opencv.imgproc.CLAHE? = null
        try {
            Imgproc.cvtColor(srcBgr, labMat, Imgproc.COLOR_BGR2Lab)
            Core.split(labMat, channels)

            clahe = Imgproc.createCLAHE(
                claheClipLimit,
                Size(claheTileGridSize, claheTileGridSize)
            )
            clahe.apply(channels[0], claheL)

            // Replace L channel with CLAHE-equalized luminance while keeping A and B untouched
            channels[0].release()
            channels[0] = claheL.clone()

            Core.merge(channels, labMat)
            Imgproc.cvtColor(labMat, dstBgr, Imgproc.COLOR_Lab2BGR)
        } finally {
            clahe?.collectGarbage()
            claheL.release()
            for (ch in channels) {
                ch.release()
            }
            labMat.release()
        }
    }

    private companion object {
        /** Hard cap on |δ| so extrapolation past the outer stations cannot shove a line off the page. */
        const val MAX_DISPLACEMENT = 0.06f
    }
}
