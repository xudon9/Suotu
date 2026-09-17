package com.xudong.suotu

/**
 * A crop region in normalised 0..1 coordinates of the source image.
 *
 * Normalised rather than pixel-based on purpose: the engine decodes with subsampling,
 * so the pixel dimensions are not known when the user draws the box, and the same rect
 * must stay valid at any decode scale.
 */
data class CropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    val isFullFrame: Boolean
        get() = left <= 0.001f && top <= 0.001f &&
            right >= 0.999f && bottom >= 0.999f

    /** Keep the box inside the image and never smaller than [MIN_SIZE]. */
    fun clamped(): CropRect {
        var l = left.coerceIn(0f, 1f - MIN_SIZE)
        var t = top.coerceIn(0f, 1f - MIN_SIZE)
        var r = right.coerceIn(l + MIN_SIZE, 1f)
        var b = bottom.coerceIn(t + MIN_SIZE, 1f)
        if (r - l < MIN_SIZE) l = (r - MIN_SIZE).coerceAtLeast(0f)
        if (b - t < MIN_SIZE) t = (b - MIN_SIZE).coerceAtLeast(0f)
        return CropRect(l, t, r, b)
    }

    /** Move by a delta without resizing, stopping at the edges. */
    fun translated(dx: Float, dy: Float): CropRect {
        val w = width
        val h = height
        val l = (left + dx).coerceIn(0f, 1f - w)
        val t = (top + dy).coerceIn(0f, 1f - h)
        return CropRect(l, t, l + w, t + h)
    }

    companion object {
        /** Never let the box collapse to nothing. */
        const val MIN_SIZE = 0.05f

        val FULL = CropRect(0f, 0f, 1f, 1f)
    }
}

/**
 * Output width choices.
 *
 * The slider is the real control; these are quick jumps to the widths that the OCR
 * measurements identified as meaningful (see [Preset] and the project README).
 */
object SizeRange {
    /** Below ~400px even large text starts to break down. */
    const val MIN_WIDTH = 320

    /** Beyond a phone screen's width there is nothing to gain for sharing. */
    const val MAX_WIDTH = 1440

    const val DEFAULT_WIDTH = 720
    const val STEP = 20

    /** Quick-jump widths, each tied to a named preset. */
    val quickPicks: List<Preset> = Preset.entries

    /**
     * Byte budget for a given width.
     *
     * Interpolated from the measured presets so the quality search has a sensible
     * target at any slider position; it acts as a safety net for photo-like content
     * rather than a hard cap for text.
     */
    fun budgetFor(width: Int): Int {
        val sorted = Preset.entries.sortedBy { it.targetWidth }
        val first = sorted.first()
        val last = sorted.last()
        if (width <= first.targetWidth) return first.budgetBytes
        if (width >= last.targetWidth) {
            // Above the largest preset, scale with area — bytes grow roughly with pixels.
            val factor = (width.toFloat() / last.targetWidth).let { it * it }
            return (last.budgetBytes * factor).toInt()
        }
        val hi = sorted.first { it.targetWidth >= width }
        val lo = sorted.last { it.targetWidth <= width }
        if (hi.targetWidth == lo.targetWidth) return hi.budgetBytes
        val t = (width - lo.targetWidth).toFloat() /
            (hi.targetWidth - lo.targetWidth).toFloat()
        return (lo.budgetBytes + t * (hi.budgetBytes - lo.budgetBytes)).toInt()
    }
}
