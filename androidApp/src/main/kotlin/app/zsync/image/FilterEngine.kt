package app.zsync.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Applies a named look to a copy of [source].
 *
 * The function is pure with respect to the bitmap: [source] is never modified or recycled. It needs
 * no Context and performs no file or MediaStore I/O. Unknown styles are logged and return [source]
 * unchanged, as required for forward-compatible callers.
 *
 * ``sharp`` raises contrast with a [ColorMatrix] and then applies a subtle five-point unsharp mask.
 * ``smooth`` lowers contrast and slightly raises saturation, also through a [ColorMatrix].
 * [intensity] is clamped to 0..1 and scales every effect; at 0 the result is an unchanged copy.
 */
fun applyStyle(source: Bitmap, style: String, intensity: Float = 1.0f): Bitmap {
    require(!source.isRecycled) { "source bitmap is recycled" }
    val amount = intensity.coerceIn(0f, 1f)
    return when (style.trim().lowercase(Locale.ROOT)) {
        "sharp" -> renderSharp(source, amount)
        "smooth" -> renderSmooth(source, amount)
        else -> {
            Log.w(TAG, "Unknown style '$style'; returning the original bitmap")
            source
        }
    }
}

private fun renderSharp(source: Bitmap, intensity: Float): Bitmap {
    val contrast = 1f + SHARP_CONTRAST_GAIN * intensity
    val result = drawWithMatrix(source, contrastMatrix(contrast))
    // The kernel is [0, -a, 0; -a, 1+4a, -a; 0, -a, 0], which sharpens without a halo.
    sharpenInPlace(result, SHARP_AMOUNT * intensity)
    return result
}

private fun renderSmooth(source: Bitmap, intensity: Float): Bitmap {
    val contrast = 1f - SMOOTH_CONTRAST_LOSS * intensity
    val saturation = 1f + SMOOTH_SATURATION_GAIN * intensity
    val matrix = ColorMatrix().apply { setSaturation(saturation) }
    matrix.postConcat(contrastMatrix(contrast))
    return drawWithMatrix(source, matrix)
}

/**
 * Renders [source] through [matrix] into a fresh ARGB_8888 bitmap. A hardware source is copied to
 * software memory for the draw and that intermediate copy is recycled immediately.
 */
private fun drawWithMatrix(source: Bitmap, matrix: ColorMatrix): Bitmap {
    val drawable = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false) ?: error("cannot read hardware bitmap")
    } else {
        source
    }
    val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    result.density = source.density
    try {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        Canvas(result).drawBitmap(drawable, 0f, 0f, paint)
    } catch (failed: Throwable) {
        result.recycle()
        throw failed
    } finally {
        if (drawable !== source) drawable.recycle()
    }
    return result
}

/**
 * A contrast-only color matrix. The translate term keeps middle grey at 128 instead of shifting the
 * whole image brighter or darker.
 */
private fun contrastMatrix(contrast: Float): ColorMatrix {
    val translate = 127.5f * (1f - contrast)
    return ColorMatrix(
        floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f,
        ),
    )
}

/**
 * Sharpens [bitmap] in place with a five-point kernel.
 *
 * The bitmap is processed in horizontal bands. Only one band plus a one-row halo is held in memory,
 * and the original bottom row of each band is saved so the next band can still read an unmodified
 * neighbour. That keeps peak memory at two full bitmaps (source and result) instead of allocating a
 * third full-size bitmap for the convolution.
 */
private fun sharpenInPlace(bitmap: Bitmap, strength: Float) {
    if (strength <= 0f) return
    val width = bitmap.width
    val height = bitmap.height
    if (width < 3 || height < 3) return

    val centerWeight = 1f + 4f * strength
    var savedTop: IntArray? = null
    var bandStart = 0
    while (bandStart < height) {
        val bandEnd = min(bandStart + SHARPEN_BAND_HEIGHT - 1, height - 1)
        val readTop = if (bandStart == 0) 0 else bandStart - 1
        val readBottom = min(bandEnd + 1, height - 1)
        val readRows = readBottom - readTop + 1
        val band = IntArray(readRows * width)
        bitmap.getPixels(band, 0, width, 0, readTop, width, readRows)

        // The halo row above this band was overwritten by the previous band's output; restore its
        // original pixels before using it as the "up" neighbour.
        if (bandStart > 0) {
            savedTop?.let { System.arraycopy(it, 0, band, 0, width) }
        }
        val saveOffset = (bandEnd - readTop) * width
        savedTop = band.copyOfRange(saveOffset, saveOffset + width)

        val outputRows = bandEnd - bandStart + 1
        val output = IntArray(outputRows * width)
        for (y in bandStart..bandEnd) {
            val centerRow = y - readTop
            val upRow = if (y == 0) centerRow else centerRow - 1
            val downRow = if (y == height - 1) centerRow else centerRow + 1
            val centerOffset = centerRow * width
            val upOffset = upRow * width
            val downOffset = downRow * width
            val outputOffset = (y - bandStart) * width
            for (x in 0 until width) {
                val center = band[centerOffset + x]
                if ((center ushr 24) == 0) {
                    output[outputOffset + x] = center
                    continue
                }
                val left = if (x > 0) band[centerOffset + x - 1] else center
                val right = if (x < width - 1) band[centerOffset + x + 1] else center
                val up = band[upOffset + x]
                val down = band[downOffset + x]
                output[outputOffset + x] = sharpenPixel(center, left, right, up, down, strength, centerWeight)
            }
        }
        bitmap.setPixels(output, 0, width, 0, bandStart, width, outputRows)
        bandStart = bandEnd + 1
    }
}

private fun sharpenPixel(
    center: Int,
    left: Int,
    right: Int,
    up: Int,
    down: Int,
    strength: Float,
    centerWeight: Float,
): Int {
    val alpha = center ushr 24
    val red = channel(center, 16, left, right, up, down, strength, centerWeight)
    val green = channel(center, 8, left, right, up, down, strength, centerWeight)
    val blue = channel(center, 0, left, right, up, down, strength, centerWeight)
    return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}

private fun channel(
    center: Int,
    shift: Int,
    left: Int,
    right: Int,
    up: Int,
    down: Int,
    strength: Float,
    centerWeight: Float,
): Int {
    val middle = (center shr shift) and 0xFF
    val neighbours = ((left shr shift) and 0xFF) +
        ((right shr shift) and 0xFF) +
        ((up shr shift) and 0xFF) +
        ((down shr shift) and 0xFF)
    return (middle * centerWeight - strength * neighbours).roundToInt().coerceIn(0, 255)
}

private const val TAG = "FilterEngine"
private const val SHARP_CONTRAST_GAIN = 0.18f
private const val SHARP_AMOUNT = 0.12f
private const val SMOOTH_CONTRAST_LOSS = 0.18f
private const val SMOOTH_SATURATION_GAIN = 0.14f
private const val SHARPEN_BAND_HEIGHT = 64
