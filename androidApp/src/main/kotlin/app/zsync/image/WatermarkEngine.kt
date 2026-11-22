package app.zsync.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws a text watermark onto a copy of [source].
 *
 * The function is pure with respect to the bitmap: [source] is never modified or recycled, and the
 * returned bitmap is the only allocation that survives the call. It needs no Context and performs
 * no file or MediaStore I/O.
 *
 * The text is white with a one-pixel black outline. Its distance from the image edge is 2% of the
 * shorter side. If the measured text is wider than 90% of the image, the font size shrinks until it
 * fits (or until the minimum readable size is reached). Only the output bitmap is allocated; any
 * temporary bitmap needed for a hardware source is recycled before returning.
 */
fun addWatermark(
    source: Bitmap,
    text: String,
    position: String = "bottom-right",
    opacity: Float = 0.7f,
): Bitmap {
    require(!source.isRecycled) { "source bitmap is recycled" }
    val width = source.width
    val height = source.height
    require(width > 0 && height > 0) { "source bitmap is empty" }

    val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    output.density = source.density
    val canvas = Canvas(output)
    drawSource(source, canvas)

    if (text.isBlank()) return output

    val margin = min(width, height) * EDGE_MARGIN_RATIO
    val maxTextWidth = width * MAX_TEXT_WIDTH_RATIO
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        color = Color.WHITE
        style = Paint.Style.FILL
        textAlign = Paint.Align.LEFT
        isSubpixelText = true
        alpha = (opacity.coerceIn(0f, 1f) * 255f).roundToInt()
    }

    var textSize = max(MIN_TEXT_SIZE, min(width, height) * BASE_TEXT_SIZE_RATIO)
    paint.textSize = textSize
    var measured = paint.measureText(text)
    var shrinkAttempts = 0
    while (measured > maxTextWidth && textSize > MIN_TEXT_SIZE && shrinkAttempts < MAX_SHRINK_ATTEMPTS) {
        textSize = (textSize * (maxTextWidth / measured)).coerceAtLeast(MIN_TEXT_SIZE)
        paint.textSize = textSize
        measured = paint.measureText(text)
        shrinkAttempts += 1
    }

    val metrics = paint.fontMetrics
    val textHeight = metrics.descent - metrics.ascent
    val normalized = position.trim().lowercase(Locale.ROOT)
    val top = when {
        normalized.startsWith("top") -> margin
        normalized.startsWith("bottom") -> height - margin - textHeight
        else -> (height - textHeight) / 2f
    }
    val left = when {
        normalized.endsWith("left") -> margin
        normalized.endsWith("right") -> width - margin - measured
        else -> (width - measured) / 2f
    }
    val baseline = top - metrics.ascent

    // Stroke first, fill second: the white glyphs stay crisp inside the one-pixel black outline.
    val stroke = Paint(paint).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 1f
        strokeJoin = Paint.Join.ROUND
    }
    val fill = Paint(paint).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    canvas.drawText(text, left, baseline, stroke)
    canvas.drawText(text, left, baseline, fill)

    return output
}

private fun drawSource(source: Bitmap, canvas: Canvas) {
    // A hardware bitmap cannot be read by a software Canvas, so copy it for the draw and recycle
    // that intermediate immediately. Ordinary software bitmaps are drawn without an extra copy.
    val drawable = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false) ?: error("cannot read hardware bitmap")
    } else {
        source
    }
    try {
        canvas.drawBitmap(drawable, 0f, 0f, null)
    } finally {
        if (drawable !== source) drawable.recycle()
    }
}

private const val EDGE_MARGIN_RATIO = 0.02f
private const val BASE_TEXT_SIZE_RATIO = 0.045f
private const val MAX_TEXT_WIDTH_RATIO = 0.9f
private const val MIN_TEXT_SIZE = 4f
private const val MAX_SHRINK_ATTEMPTS = 16
