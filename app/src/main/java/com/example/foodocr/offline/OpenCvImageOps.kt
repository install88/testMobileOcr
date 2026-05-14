package com.example.foodocr.offline

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Rect
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object OpenCvImageOps {
    const val DATE_HORIZONTAL_PADDING = 6
    const val DATE_VERTICAL_PADDING = 0
    const val MIN_REC_HEIGHT = 48

    fun ensureLoaded() {
        check(OpenCVLoader.initDebug()) { "OpenCV could not be initialized" }
    }

    fun bitmapToBgr(bitmap: Bitmap): Mat {
        ensureLoaded()
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        return bgr
    }

    fun cropWithPadding(
        image: Mat,
        box: RectFBox,
        horizontalPadding: Int = DATE_HORIZONTAL_PADDING,
        verticalPadding: Int = DATE_VERTICAL_PADDING,
    ): Pair<RectFBox, Mat> {
        val padded = box.padded(horizontalPadding, verticalPadding, image.cols(), image.rows())
        val x = padded.left.roundToInt().coerceIn(0, image.cols().coerceAtLeast(1) - 1)
        val y = padded.top.roundToInt().coerceIn(0, image.rows().coerceAtLeast(1) - 1)
        val right = padded.right.roundToInt().coerceIn(x + 1, image.cols())
        val bottom = padded.bottom.roundToInt().coerceIn(y + 1, image.rows())
        val rect = Rect(x, y, right - x, bottom - y)
        return padded to Mat(image, rect).clone()
    }

    fun ensureMinHeight(image: Mat, targetHeight: Int = MIN_REC_HEIGHT): Mat {
        if (image.empty() || image.rows() >= targetHeight) return image.clone()
        val scale = targetHeight.toDouble() / max(1, image.rows()).toDouble()
        val targetWidth = max(1, (image.cols() * scale).roundToInt())
        val resized = Mat()
        Imgproc.resize(image, resized, Size(targetWidth.toDouble(), targetHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)
        return resized
    }

    fun preprocessVariants(crop: Mat): List<PreprocessVariant> {
        val base = ensureMinHeight(crop)
        val variants = mutableListOf<PreprocessVariant>()
        variants += PreprocessVariant("original", base.clone())

        val gray = toGray(base)

        val clahe = Imgproc.createCLAHE(3.0, Size(4.0, 4.0))
        val enhanced = Mat()
        clahe.apply(gray, enhanced)
        variants += PreprocessVariant("clahe", grayToBgr(enhanced))

        val otsu = Mat()
        Imgproc.threshold(gray, otsu, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        variants += PreprocessVariant("otsu", grayToBgr(otsu))

        val invertedOtsu = Mat()
        Core.bitwise_not(otsu, invertedOtsu)
        variants += PreprocessVariant("otsu_inverted", grayToBgr(invertedOtsu))

        val adaptive = Mat()
        val blockSize = max(11, (base.rows() / 4) * 2 + 1)
        Imgproc.adaptiveThreshold(
            gray,
            adaptive,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY,
            blockSize,
            4.0,
        )
        variants += PreprocessVariant("adaptive", grayToBgr(adaptive))

        deskew(base)?.let { deskewed ->
            variants += PreprocessVariant("deskew", deskewed.clone())
            val deskewGray = toGray(deskewed)
            val deskewOtsu = Mat()
            Imgproc.threshold(deskewGray, deskewOtsu, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            variants += PreprocessVariant("deskew_otsu", grayToBgr(deskewOtsu))
            deskewGray.release()
            deskewOtsu.release()
            deskewed.release()
        }

        if (base.rows() < 96) {
            val upscaled = Mat()
            Imgproc.resize(
                base,
                upscaled,
                Size((base.cols() * 2).toDouble(), (base.rows() * 2).toDouble()),
                0.0,
                0.0,
                Imgproc.INTER_CUBIC,
            )
            variants += PreprocessVariant("upscale2x", upscaled.clone())
            val upGray = toGray(upscaled)
            val upOtsu = Mat()
            Imgproc.threshold(upGray, upOtsu, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            variants += PreprocessVariant("upscale2x_otsu", grayToBgr(upOtsu))
            upGray.release()
            upOtsu.release()
            upscaled.release()
        }

        gray.release()
        enhanced.release()
        otsu.release()
        invertedOtsu.release()
        adaptive.release()
        base.release()
        return variants
    }

    fun preparePpocrRecTensor(imageBgr: Mat, targetHeight: Int, maxWidth: Int?): Pair<FloatArray, LongArray> {
        val safeHeight = max(1, targetHeight)
        val ratio = imageBgr.cols().toDouble() / max(1, imageBgr.rows()).toDouble()
        val resizedWidthRaw = max(1, ceil(safeHeight * ratio).toInt())
        val targetWidth = maxWidth?.let { min(it, resizedWidthRaw) } ?: resizedWidthRaw

        val resized = Mat()
        Imgproc.resize(
            imageBgr,
            resized,
            Size(targetWidth.toDouble(), safeHeight.toDouble()),
            0.0,
            0.0,
            Imgproc.INTER_CUBIC,
        )

        val paddedWidth = maxWidth ?: targetWidth
        val padded = Mat(safeHeight, paddedWidth, CvType.CV_8UC3, Scalar(0.0, 0.0, 0.0))
        val targetRegion = padded.submat(Rect(0, 0, targetWidth, safeHeight))
        resized.copyTo(targetRegion)
        targetRegion.release()

        val rgb = Mat()
        Imgproc.cvtColor(padded, rgb, Imgproc.COLOR_BGR2RGB)

        val data = cv8uc3ToChwFloat(rgb) { value ->
            ((value / 255f) - 0.5f) / 0.5f
        }

        resized.release()
        padded.release()
        rgb.release()
        return data to longArrayOf(1, 3, safeHeight.toLong(), paddedWidth.toLong())
    }

    fun cv8uc3ToChwFloat(image: Mat, normalize: (Float) -> Float): FloatArray {
        check(image.type() == CvType.CV_8UC3) { "Expected CV_8UC3 Mat, got type=${image.type()}" }
        val height = image.rows()
        val width = image.cols()
        val channels = 3
        val hw = height * width
        val bytes = ByteArray(hw * channels)
        image.get(0, 0, bytes)

        val data = FloatArray(channels * hw)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val pixelOffset = (y * width + x) * channels
                val outputOffset = y * width + x
                for (c in 0 until channels) {
                    val value = (bytes[pixelOffset + c].toInt() and 0xFF).toFloat()
                    data[c * hw + outputOffset] = normalize(value)
                }
            }
        }
        return data
    }

    fun iou(a: RectFBox, b: RectFBox): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val intersection = max(0f, right - left) * max(0f, bottom - top)
        val union = a.area + b.area - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun toGray(image: Mat): Mat {
        val gray = Mat()
        if (image.channels() == 1) {
            image.copyTo(gray)
        } else {
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY)
        }
        return gray
    }

    private fun grayToBgr(gray: Mat): Mat {
        val bgr = Mat()
        Imgproc.cvtColor(gray, bgr, Imgproc.COLOR_GRAY2BGR)
        return bgr
    }

    private fun deskew(image: Mat): Mat? {
        val gray = toGray(image)
        val binary = Mat()
        Imgproc.threshold(gray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)

        val points = MatOfPoint()
        Core.findNonZero(binary, points)
        if (points.rows() < 50) {
            gray.release()
            binary.release()
            points.release()
            return null
        }

        val point2f = MatOfPoint2f(*points.toArray())
        val rect: RotatedRect = Imgproc.minAreaRect(point2f)
        var angle = rect.angle.toDouble()
        if (angle < -45.0) angle += 90.0

        gray.release()
        binary.release()
        points.release()
        point2f.release()

        if (abs(angle) < 2.0) return null

        val center = org.opencv.core.Point(image.cols() / 2.0, image.rows() / 2.0)
        val matrix = Imgproc.getRotationMatrix2D(center, -angle, 1.0)
        val deskewed = Mat()
        Imgproc.warpAffine(
            image,
            deskewed,
            matrix,
            Size(image.cols().toDouble(), image.rows().toDouble()),
            Imgproc.INTER_CUBIC,
            Core.BORDER_REPLICATE,
            Scalar(0.0, 0.0, 0.0),
        )
        matrix.release()
        return deskewed
    }
}
