package com.xudong.suotu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Draws annotations onto an Android [Canvas].
 *
 * Deliberately uses the platform Canvas rather than Compose's DrawScope so the SAME
 * code path renders both the on-screen overlay and the final flattened bitmap. Two
 * renderers would drift, and the user would discover the difference only after sending.
 *
 * All geometry arrives normalised (0..1) and is scaled by the target canvas size, so one
 * annotation set renders correctly at preview size and at full resolution.
 */
object AnnotationRenderer {

    /** Arrowhead length as a multiple of stroke width. */
    private const val ARROWHEAD_SCALE = 4.5f

    /** Arrowhead half-angle in radians (~26°), a proportioned V rather than a spike. */
    private const val ARROWHEAD_ANGLE = 0.45f

    /**
     * Render [items] over [canvas], sized [width] x [height] pixels.
     *
     * @param sourceForBlur pixels sampled for the pixelate tool. Required because blur
     *        must read the underlying image, which the canvas itself cannot provide.
     */
    fun render(
        canvas: Canvas,
        items: List<Annotation>,
        width: Int,
        height: Int,
        sourceForBlur: Bitmap? = null,
    ) {
        // Scale strokes by width only: using the diagonal or height would make the same
        // annotation visually thicker on a tall screenshot than a wide one.
        val scale = width.toFloat()

        items.forEach { item ->
            when (item) {
                is Annotation.Freehand -> drawFreehand(canvas, item, width, height, scale)
                is Annotation.Rect -> drawRect(canvas, item, width, height, scale)
                is Annotation.Ellipse -> drawEllipse(canvas, item, width, height, scale)
                is Annotation.Arrow -> drawArrow(canvas, item, width, height, scale)
                is Annotation.Text -> drawText(canvas, item, width, height, scale)
                is Annotation.Blur ->
                    drawBlur(canvas, item, width, height, sourceForBlur)
            }
        }
    }

    private fun strokePaint(color: Long, strokeWidth: Float) = Paint().apply {
        this.color = color.toInt()
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private fun drawFreehand(
        canvas: Canvas,
        item: Annotation.Freehand,
        w: Int,
        h: Int,
        scale: Float,
    ) {
        if (item.points.size < 2) return
        val paint = strokePaint(item.color, item.thickness * scale)
        val path = Path()
        item.points.forEachIndexed { i, p ->
            val x = p.x * w
            val y = p.y * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }

    private fun drawRect(
        canvas: Canvas,
        item: Annotation.Rect,
        w: Int,
        h: Int,
        scale: Float,
    ) {
        val b = item.bounds()
        canvas.drawRect(
            b.left * w, b.top * h, b.right * w, b.bottom * h,
            strokePaint(item.color, item.thickness * scale),
        )
    }

    private fun drawEllipse(
        canvas: Canvas,
        item: Annotation.Ellipse,
        w: Int,
        h: Int,
        scale: Float,
    ) {
        val b = item.bounds()
        canvas.drawOval(
            RectF(b.left * w, b.top * h, b.right * w, b.bottom * h),
            strokePaint(item.color, item.thickness * scale),
        )
    }

    /**
     * Line plus a filled arrowhead.
     *
     * The head is built as a path in the line's own direction rather than by rotating
     * the canvas, which keeps the tip exactly on the drag end point.
     */
    private fun drawArrow(
        canvas: Canvas,
        item: Annotation.Arrow,
        w: Int,
        h: Int,
        scale: Float,
    ) {
        val strokeWidth = item.thickness * scale
        val x1 = item.from.x * w
        val y1 = item.from.y * h
        val x2 = item.to.x * w
        val y2 = item.to.y * h

        val len = hypot(x2 - x1, y2 - y1)
        if (len < 1f) return

        val headLen = (strokeWidth * ARROWHEAD_SCALE).coerceAtMost(len * 0.5f)
        val angle = atan2(y2 - y1, x2 - x1)

        // Stop the shaft just short of the tip so the head's point stays crisp.
        val shaftEndX = x2 - cos(angle) * headLen * 0.6f
        val shaftEndY = y2 - sin(angle) * headLen * 0.6f
        canvas.drawLine(
            x1, y1, shaftEndX, shaftEndY,
            strokePaint(item.color, strokeWidth),
        )

        val fill = Paint().apply {
            color = item.color.toInt()
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val path = Path().apply {
            moveTo(x2, y2)
            lineTo(
                x2 - cos(angle - ARROWHEAD_ANGLE) * headLen,
                y2 - sin(angle - ARROWHEAD_ANGLE) * headLen,
            )
            lineTo(
                x2 - cos(angle + ARROWHEAD_ANGLE) * headLen,
                y2 - sin(angle + ARROWHEAD_ANGLE) * headLen,
            )
            close()
        }
        canvas.drawPath(path, fill)
    }

    /**
     * Text with a contrasting outline.
     *
     * Screenshots have unpredictable backgrounds, so plain coloured text can land on a
     * same-coloured area and vanish. Drawing a halo first guarantees legibility without
     * needing to sample the underlying pixels.
     */
    private fun drawText(
        canvas: Canvas,
        item: Annotation.Text,
        w: Int,
        h: Int,
        scale: Float,
    ) {
        if (item.text.isEmpty()) return
        val size = AnnotationThickness.textSizeFor(item.thickness) * scale
        val x = item.at.x * w
        val y = item.at.y * h

        // Halo in the opposite luminance, so it works on dark and light text alike.
        val isLight = isLightColor(item.color)
        val haloColor = if (isLight) 0xFF000000L else 0xFFFFFFFFL

        val halo = Paint().apply {
            color = haloColor.toInt()
            textSize = size
            style = Paint.Style.STROKE
            strokeWidth = size * 0.16f
            isAntiAlias = true
            isFakeBoldText = true
        }
        val fill = Paint().apply {
            color = item.color.toInt()
            textSize = size
            style = Paint.Style.FILL
            isAntiAlias = true
            isFakeBoldText = true
        }
        canvas.drawText(item.text, x, y, halo)
        canvas.drawText(item.text, x, y, fill)
    }

    /**
     * Pixelate a region by downscaling then upscaling without filtering.
     *
     * Chosen over a Gaussian blur on purpose: a blur is theoretically reversible and
     * has been recovered from screenshots before, whereas averaging blocks genuinely
     * discards the information. For hiding a phone number that difference matters.
     */
    private fun drawBlur(
        canvas: Canvas,
        item: Annotation.Blur,
        w: Int,
        h: Int,
        source: Bitmap?,
    ) {
        val b = item.bounds()
        val left = (b.left * w).toInt().coerceIn(0, w - 1)
        val top = (b.top * h).toInt().coerceIn(0, h - 1)
        val right = (b.right * w).toInt().coerceIn(left + 1, w)
        val bottom = (b.bottom * h).toInt().coerceIn(top + 1, h)
        val rectW = right - left
        val rectH = bottom - top
        if (rectW < 2 || rectH < 2) return

        if (source == null) {
            // No pixels to sample (live overlay): a solid block still communicates
            // "this will be redacted".
            canvas.drawRect(
                left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(),
                Paint().apply {
                    color = 0xFF404040.toInt()
                    style = Paint.Style.FILL
                },
            )
            return
        }

        // ~12 blocks across the region, so the mosaic is coarse regardless of its size.
        val blocks = 12
        val smallW = (rectW / (rectW.toFloat() / blocks)).toInt().coerceAtLeast(2)
        val smallH = (rectH * smallW / rectW).coerceAtLeast(2)

        val region = runCatching {
            Bitmap.createBitmap(source, left, top, rectW, rectH)
        }.getOrNull() ?: return

        val tiny = region.scale(smallW, smallH, filter = true)
        // filter = false on the way back up is what produces hard mosaic blocks
        // instead of a smooth, partially-recoverable blur.
        val mosaic = tiny.scale(rectW, rectH, filter = false)

        canvas.drawBitmap(
            mosaic,
            Rect(0, 0, rectW, rectH),
            Rect(left, top, right, bottom),
            null,
        )

        region.recycle()
        tiny.recycle()
        mosaic.recycle()
    }

    private fun isLightColor(argb: Long): Boolean {
        val r = ((argb shr 16) and 0xFF).toInt()
        val g = ((argb shr 8) and 0xFF).toInt()
        val b = (argb and 0xFF).toInt()
        // Rec. 601 luma: cheap and adequate for a light/dark decision.
        return (0.299 * r + 0.587 * g + 0.114 * b) > 140
    }

    /** Local helper so the renderer does not depend on androidx graphics extensions. */
    private fun Bitmap.scale(w: Int, h: Int, filter: Boolean): Bitmap =
        Bitmap.createScaledBitmap(this, w, h, filter)

    /**
     * Flatten [items] onto a copy of [source].
     *
     * Applied to the FULL-RESOLUTION source before shrinking, so strokes are resampled
     * along with the image and stay proportionate at any output width. Annotating after
     * the shrink would bake in preview-sized strokes.
     */
    fun flatten(source: Bitmap, items: List<Annotation>): Bitmap {
        if (items.isEmpty()) return source
        val out = source.copy(Bitmap.Config.ARGB_8888, true) ?: return source
        // Blur samples the ORIGINAL pixels: sampling the canvas being drawn into would
        // let one annotation pixelate another.
        render(Canvas(out), items, out.width, out.height, sourceForBlur = source)
        return out
    }
}
