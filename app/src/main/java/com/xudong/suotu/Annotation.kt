package com.xudong.suotu

import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The drawing tools. */
enum class AnnotationTool(val labelRes: Int) {
    BRUSH(R.string.annotate_tool_brush),
    RECT(R.string.annotate_tool_rect),
    CIRCLE(R.string.annotate_tool_circle),
    ARROW(R.string.annotate_tool_arrow),
    TEXT(R.string.annotate_tool_text),

    /** Pixelate a region, for hiding phone numbers and faces. */
    BLUR(R.string.annotate_tool_blur),
}

/**
 * Palette offered for annotations, as 0xAARRGGBB.
 *
 * Red first because it is the overwhelmingly common choice for pointing at something,
 * and the set stays small: a long palette turns a two-second markup into a decision.
 */
object AnnotationPalette {
    val colors = listOf(
        0xFFE53935L, // red
        0xFFFFB300L, // amber
        0xFF43A047L, // green
        0xFF1E88E5L, // blue
        0xFF000000L, // black
        0xFFFFFFFFL, // white
    )

    val default = colors.first()
}

/** Stroke widths, as a fraction of image width so they scale with the image. */
object AnnotationThickness {
    /** Thin enough to point precisely, at 1260px ≈ 4px. */
    const val MIN = 0.003f

    /** Heavy enough to redact or emphasise, at 1260px ≈ 25px. */
    const val MAX = 0.020f

    const val DEFAULT = 0.006f

    /** Text size as a fraction of image width, mapped from the thickness slider. */
    fun textSizeFor(thickness: Float): Float = thickness * 6f
}

/**
 * One annotation.
 *
 * Coordinates are normalised 0..1 against the SOURCE image, never pixels. That is what
 * lets the same annotation render correctly on the small preview, on the full-resolution
 * composite, and at any output width the slider is moved to. Storing pixels would mean
 * re-deriving every shape whenever the width changed.
 */
sealed interface Annotation {
    val color: Long
    val thickness: Float

    /** Bounding box, used for hit-testing and for the selection outline. */
    fun bounds(): CropRect

    data class Freehand(
        val points: List<Offset>,
        override val color: Long,
        override val thickness: Float,
    ) : Annotation {
        override fun bounds(): CropRect {
            if (points.isEmpty()) return CropRect.FULL
            var l = 1f; var t = 1f; var r = 0f; var b = 0f
            points.forEach {
                l = min(l, it.x); t = min(t, it.y)
                r = max(r, it.x); b = max(b, it.y)
            }
            return CropRect(l, t, r, b)
        }
    }

    data class Rect(
        val start: Offset,
        val end: Offset,
        override val color: Long,
        override val thickness: Float,
    ) : Annotation {
        override fun bounds() = normalisedBounds(start, end)
    }

    data class Ellipse(
        val start: Offset,
        val end: Offset,
        override val color: Long,
        override val thickness: Float,
    ) : Annotation {
        override fun bounds() = normalisedBounds(start, end)
    }

    data class Arrow(
        val from: Offset,
        val to: Offset,
        override val color: Long,
        override val thickness: Float,
    ) : Annotation {
        override fun bounds() = normalisedBounds(from, to)
    }

    data class Text(
        val at: Offset,
        val text: String,
        override val color: Long,
        override val thickness: Float,
    ) : Annotation {
        override fun bounds(): CropRect {
            // Approximate: the true extent needs a measured text layout, which is not
            // available in the model layer. Generous enough to be tappable.
            val size = AnnotationThickness.textSizeFor(thickness)
            val w = (text.length * size * 0.6f).coerceAtMost(1f)
            return CropRect(
                at.x, (at.y - size).coerceAtLeast(0f),
                (at.x + w).coerceAtMost(1f), (at.y + size * 0.4f).coerceAtMost(1f),
            )
        }
    }

    /** Pixelated region. Carries no colour, but keeps the interface uniform. */
    data class Blur(
        val start: Offset,
        val end: Offset,
        override val color: Long = 0,
        override val thickness: Float = 0f,
    ) : Annotation {
        override fun bounds() = normalisedBounds(start, end)
    }
}

/** Bounding box of two corners, in either drag direction. */
private fun normalisedBounds(a: Offset, b: Offset) = CropRect(
    min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y),
)

/**
 * The annotation document: an ordered list plus a selection.
 *
 * An object model rather than a flattened bitmap, so undo can remove a single shape and
 * a shape can be selected after the fact. Flattening as you draw would make both
 * impossible.
 */
data class AnnotationState(
    val items: List<Annotation> = emptyList(),
    val selectedIndex: Int? = null,
) {
    val isEmpty: Boolean get() = items.isEmpty()

    fun add(item: Annotation) = copy(items = items + item, selectedIndex = null)

    /** Remove the most recent item. */
    fun undo() = if (items.isEmpty()) this else copy(
        items = items.dropLast(1),
        selectedIndex = null,
    )

    fun clear() = AnnotationState()

    fun removeSelected(): AnnotationState {
        val i = selectedIndex ?: return this
        if (i !in items.indices) return copy(selectedIndex = null)
        return copy(
            items = items.filterIndexed { index, _ -> index != i },
            selectedIndex = null,
        )
    }

    fun select(index: Int?) = copy(selectedIndex = index)

    /**
     * Topmost annotation under [point], or null.
     *
     * Iterates in reverse so the most recently drawn shape wins, matching what the user
     * sees. [slop] widens the target so thin strokes stay tappable.
     */
    fun hitTest(point: Offset, slop: Float = 0.03f): Int? {
        for (i in items.indices.reversed()) {
            if (hits(items[i], point, slop)) return i
        }
        return null
    }

    private fun hits(item: Annotation, p: Offset, slop: Float): Boolean = when (item) {
        // Freehand: near any sampled point. Cheap and accurate enough for selection.
        is Annotation.Freehand ->
            item.points.any { hypot((it.x - p.x), (it.y - p.y)) < slop }

        // Arrow: near the segment, not merely inside its bounding box — a diagonal
        // arrow's box is mostly empty space and would steal taps from other shapes.
        is Annotation.Arrow ->
            distanceToSegment(p, item.from, item.to) < slop

        // Outlined shapes: near the border, so the interior stays available for
        // selecting whatever is underneath.
        is Annotation.Rect -> nearRectBorder(item.bounds(), p, slop)
        is Annotation.Ellipse -> nearRectBorder(item.bounds(), p, slop)

        // Filled/opaque items: anywhere inside.
        is Annotation.Blur -> contains(item.bounds(), p)
        is Annotation.Text -> contains(item.bounds(), p)
    }

    private fun contains(r: CropRect, p: Offset) =
        p.x >= r.left && p.x <= r.right && p.y >= r.top && p.y <= r.bottom

    private fun nearRectBorder(r: CropRect, p: Offset, slop: Float): Boolean {
        val insideOuter = p.x >= r.left - slop && p.x <= r.right + slop &&
            p.y >= r.top - slop && p.y <= r.bottom + slop
        if (!insideOuter) return false
        val insideInner = p.x > r.left + slop && p.x < r.right - slop &&
            p.y > r.top + slop && p.y < r.bottom - slop
        return !insideInner
    }

    /** Perpendicular distance from [p] to segment [a]-[b]. */
    private fun distanceToSegment(p: Offset, a: Offset, b: Offset): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val lenSq = dx * dx + dy * dy
        if (lenSq < 1e-9f) return hypot(p.x - a.x, p.y - a.y)
        // Project p onto the segment, clamped to its extent.
        val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lenSq).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }
}

/** Minimum drag before a shape is committed, to ignore stray taps. */
const val ANNOTATION_MIN_DRAG = 0.012f

/** True if the drag covered enough distance to be a deliberate shape. */
fun isDeliberateDrag(a: Offset, b: Offset): Boolean =
    abs(b.x - a.x) > ANNOTATION_MIN_DRAG || abs(b.y - a.y) > ANNOTATION_MIN_DRAG
