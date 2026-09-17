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
    /**
     * One blurred copy, kept between renders.
     *
     * Cached at the TARGET canvas size, not the source size. That matters twice over:
     * blurring 496x1103 preview pixels instead of 1260x2800 source pixels is ~6x less
     * work, and the subsequent draw becomes a 1:1 blit rather than a scaled one.
     */
    private class BlurCacheEntry(
        val source: Bitmap,
        val radius: Int,
        val width: Int,
        val height: Int,
        val blurred: Bitmap,
    )

    private var blurCache: BlurCacheEntry? = null

    /** Drop the cached blur, e.g. when the edited image changes. */
    fun invalidateBlurCache() {
        blurCache?.blurred?.recycle()
        blurCache = null
    }

    /**
     * A blurred copy of [source] already scaled to [targetW] x [targetH].
     *
     * Pre-scaling is the fix for a specific lag: [paintWithBlur] previously drew the
     * full-resolution blurred bitmap SCALED into the canvas, once per blur shape per
     * frame. One such blit was tolerable, which is why the first shape felt fine, but
     * each additional shape added another ~1.4M-pixel scaled read to every frame of the
     * drag — so the second and third shapes made it lag.
     */
    private fun cachedBlur(
        source: Bitmap,
        radius: Float,
        targetW: Int,
        targetH: Int,
    ): Bitmap? {
        if (targetW < 1 || targetH < 1) return null
        val key = radius.toInt().coerceAtLeast(1)
        val hit = blurCache
        if (hit != null && hit.source === source && hit.radius == key &&
            hit.width == targetW && hit.height == targetH && !hit.blurred.isRecycled
        ) {
            return hit.blurred
        }
        hit?.blurred?.recycle()

        // Downscale first, then blur: the blur cost is linear in pixel count, and the
        // result is drawn at this size anyway.
        val scaled = if (source.width == targetW && source.height == targetH) {
            source
        } else {
            Bitmap.createScaledBitmap(source, targetW, targetH, true)
        }
        val fresh = GaussianBlur.blur(scaled, radius)
        if (scaled !== source && scaled !== fresh) scaled.recycle()

        blurCache = BlurCacheEntry(source, key, targetW, targetH, fresh)
        return fresh
    }

    /**
     * @param sourceForBlur pixels sampled for blur-painted shapes. Required because
     *        blur must read the underlying image, which the canvas cannot provide.
     * @param draftOutlineOnly when true, blur-painted shapes are drawn as a plain
     *        outline instead of being blurred. Used for the shape currently under the
     *        finger: a Gaussian pass per frame cannot keep up with a drag, and an
     *        outline that tracks the finger exactly reads as more responsive than a
     *        correct blur that lags behind it.
     */
    fun render(
        canvas: Canvas,
        items: List<Annotation>,
        width: Int,
        height: Int,
        sourceForBlur: Bitmap? = null,
        draftOutlineOnly: Boolean = false,
    ) {
        // Scale strokes by width only: using the diagonal or height would make the same
        // annotation visually thicker on a tall screenshot than a wide one.
        val scale = width.toFloat()

        fun blurred(thickness: Float): Bitmap? {
            if (draftOutlineOnly) return null
            val src = sourceForBlur ?: return null
            // Radius is derived from the TARGET width so the visual blur matches what
            // the same slider setting produces in the flattened output.
            return cachedBlur(
                src, GaussianBlur.radiusFor(thickness, width), width, height
            )
        }

        items.forEach { item ->
            when (item) {
                is Annotation.Freehand ->
                    drawFreehand(canvas, item, width, height, scale, ::blurred)

                is Annotation.Rect ->
                    drawRect(canvas, item, width, height, scale, ::blurred)

                is Annotation.Ellipse ->
                    drawEllipse(canvas, item, width, height, scale, ::blurred)

                is Annotation.Arrow -> drawArrow(canvas, item, width, height, scale)
                is Annotation.Text -> drawText(canvas, item, width, height, scale)

                // The dedicated blur tool is now just a rectangle painted with blur.
                is Annotation.Blur -> drawRect(
                    canvas,
                    Annotation.Rect(
                        item.start, item.end,
                        color = COLOR_TRANSPARENT,
                        thickness = item.thickness,
                        fillColor = COLOR_BLUR,
                    ),
                    width, height, scale, ::blurred,
                )
            }
        }
    }

    /**
     * Placeholder outline for a blur shape being dragged.
     *
     * Dashed and neutral so it is obviously provisional rather than a drawn stroke.
     */
    fun drawBlurPlaceholder(
        canvas: Canvas,
        item: Annotation,
        width: Int,
        height: Int,
    ) {
        val b = item.bounds()
        val paint = Paint().apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(14f, 10f), 0f)
        }
        val shade = Paint().apply {
            color = 0x40000000
            style = Paint.Style.FILL
        }

        when (item) {
            is Annotation.Ellipse -> {
                val oval = RectF(
                    b.left * width, b.top * height, b.right * width, b.bottom * height
                )
                canvas.drawOval(oval, shade)
                canvas.drawOval(oval, paint)
            }

            is Annotation.Freehand -> {
                // A stroke has no interior worth shading; the path itself is the hint.
                val path = Path()
                item.points.forEachIndexed { i, p ->
                    val x = p.x * width
                    val y = p.y * height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(
                    path,
                    Paint().apply {
                        color = 0xCCFFFFFF.toInt()
                        style = Paint.Style.STROKE
                        strokeWidth = item.thickness * width
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                        isAntiAlias = true
                    },
                )
            }

            else -> {
                val l = b.left * width
                val t = b.top * height
                val r = b.right * width
                val bo = b.bottom * height
                canvas.drawRect(l, t, r, bo, shade)
                canvas.drawRect(l, t, r, bo, paint)
            }
        }
    }

    /**
     * Paint [path] with pixels taken from [blurredSource].
     *
     * Clipping to the path and drawing the blurred bitmap is what lets ANY shape act as
     * a blur: the shape decides where, the bitmap decides what. Drawing a blurred copy
     * of the whole image and masking it also keeps the blur continuous across shapes,
     * whereas blurring each region separately would show seams at the edges.
     */
    private fun paintWithBlur(
        canvas: Canvas,
        blurredSource: Bitmap,
        path: Path,
        width: Int,
        height: Int,
    ) {
        val save = canvas.save()
        canvas.clipPath(path)
        // The cached bitmap is already the canvas size, so this is a 1:1 blit. The
        // src/dst rects are still passed explicitly: relying on a bare drawBitmap at
        // (0,0) is what previously made shapes sample the image's top-left corner
        // whenever the two sizes differed.
        canvas.drawBitmap(
            blurredSource,
            Rect(0, 0, blurredSource.width, blurredSource.height),
            Rect(0, 0, width, height),
            null,
        )
        canvas.restoreToCount(save)
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
        blurred: (Float) -> Bitmap?,
    ) {
        if (item.points.size < 2) return
        val path = Path()
        item.points.forEachIndexed { i, p ->
            val x = p.x * w
            val y = p.y * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        if (item.color.isBlur()) {
            // A stroke has no interior to clip, so the line is converted to an outline
            // path of its own width and that region is painted with blurred pixels.
            val stroked = Path()
            strokePaint(0xFF000000L, item.thickness * scale)
                .getFillPath(path, stroked)
            blurred(item.thickness)?.let { paintWithBlur(canvas, it, stroked, w, h) }
            return
        }

        if (item.color.isTransparent()) return
        canvas.drawPath(path, strokePaint(item.color, item.thickness * scale))
    }

    private fun fillPaint(color: Long) = Paint().apply {
        this.color = color.toInt()
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private fun drawRect(
        canvas: Canvas,
        item: Annotation.Rect,
        w: Int,
        h: Int,
        scale: Float,
        blurred: (Float) -> Bitmap?,
    ) {
        val b = item.bounds()
        val l = b.left * w
        val t = b.top * h
        val r = b.right * w
        val bo = b.bottom * h

        // Fill first so the stroke sits on top of it, as any drawing tool would.
        if (item.fillColor.isBlur()) {
            val p = Path().apply { addRect(l, t, r, bo, Path.Direction.CW) }
            blurred(item.thickness)?.let { paintWithBlur(canvas, it, p, w, h) }
        } else if (!item.fillColor.isTransparent()) {
            canvas.drawRect(l, t, r, bo, fillPaint(item.fillColor))
        }

        if (item.color.isBlur()) {
            val outline = Path().apply { addRect(l, t, r, bo, Path.Direction.CW) }
            val stroked = Path()
            strokePaint(0xFF000000L, item.thickness * scale)
                .getFillPath(outline, stroked)
            blurred(item.thickness)?.let { paintWithBlur(canvas, it, stroked, w, h) }
        } else if (!item.color.isTransparent()) {
            canvas.drawRect(l, t, r, bo, strokePaint(item.color, item.thickness * scale))
        }
    }

    private fun drawEllipse(
        canvas: Canvas,
        item: Annotation.Ellipse,
        w: Int,
        h: Int,
        scale: Float,
        blurred: (Float) -> Bitmap?,
    ) {
        val b = item.bounds()
        val oval = RectF(b.left * w, b.top * h, b.right * w, b.bottom * h)

        if (item.fillColor.isBlur()) {
            val p = Path().apply { addOval(oval, Path.Direction.CW) }
            blurred(item.thickness)?.let { paintWithBlur(canvas, it, p, w, h) }
        } else if (!item.fillColor.isTransparent()) {
            canvas.drawOval(oval, fillPaint(item.fillColor))
        }

        if (item.color.isBlur()) {
            val outline = Path().apply { addOval(oval, Path.Direction.CW) }
            val stroked = Path()
            strokePaint(0xFF000000L, item.thickness * scale)
                .getFillPath(outline, stroked)
            blurred(item.thickness)?.let { paintWithBlur(canvas, it, stroked, w, h) }
        } else if (!item.color.isTransparent()) {
            canvas.drawOval(oval, strokePaint(item.color, item.thickness * scale))
        }
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
        // Blur is not offered for arrows: a blurred arrow is invisible against the
        // thing it is pointing at. Transparent likewise draws nothing.
        if (item.color.isTransparent() || item.color.isBlur()) return

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
        if (item.color.isTransparent() || item.color.isBlur()) return
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
        thickness: Float,
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

        // Block size comes from the thickness slider, so the control the user is holding
        // visibly changes coarseness.
        //
        // It is still CAPPED by the region's shorter side. A fixed block count applied
        // to a wide, thin selection (one line of text) produced blocks far taller than
        // the glyphs: averaging then happened mostly vertically and the tops and bottoms
        // of letters survived as readable fragments. Keeping the cap preserves the
        // property that matters — a redaction that merely looks blocky is worse than
        // none, because it is trusted.
        val t = ((thickness - AnnotationThickness.MIN) /
            (AnnotationThickness.MAX - AnnotationThickness.MIN)).coerceIn(0f, 1f)
        // 0.4%..4% of image width, so coarseness scales with the image rather than with
        // whatever pixel dimensions this canvas happens to have.
        //
        // The low end is deliberately fine. A measured sweep showed that with a higher
        // floor the cap below swallowed the top half of the slider's travel — every
        // position from the midpoint up produced an identical 40px block, so the control
        // appeared broken. NOTE: at the finest settings small text can remain partly
        // legible; that is the user's choice, not an accident.
        val requested = w * (0.004f + t * 0.036f)
        val maxUseful = minOf(rectW, rectH) / 2f
        val blockPx = requested.coerceIn(2f, maxUseful.coerceAtLeast(2f))
        val smallW = (rectW / blockPx).toInt().coerceAtLeast(1)
        val smallH = (rectH / blockPx).toInt().coerceAtLeast(1)

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
        // ARGB_8888 regardless of the source config: a 16-bit PNG decodes as RGBA_F16,
        // which Canvas cannot draw into on every device, and the extra precision is
        // worthless once the result is encoded as WebP or JPEG.
        val out = source.copy(Bitmap.Config.ARGB_8888, true) ?: return source
        // Blur samples the ORIGINAL pixels: sampling the canvas being drawn into would
        // let one annotation pixelate another.
        render(Canvas(out), items, out.width, out.height, sourceForBlur = source)
        return out
    }
}
