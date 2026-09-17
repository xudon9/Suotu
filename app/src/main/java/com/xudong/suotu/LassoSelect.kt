package com.xudong.suotu

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.draw.drawWithContent
import kotlin.math.max
import kotlin.math.min

/**
 * Where an image actually lands inside a box under `ContentScale.Fit`.
 *
 * The image is letterboxed and centred, so touch coordinates are NOT a linear mapping
 * of the box: everything must be shifted by the letterbox offset and divided by the
 * displayed extent. Getting this wrong silently skews every selection, so it lives in
 * one place and is unit-tested (tools/test_lasso_mapping.py).
 */
data class FittedImage(
    val offsetX: Float,
    val offsetY: Float,
    val displayWidth: Float,
    val displayHeight: Float,
) {
    /** Box coordinates -> 0..1 within the image, or null if outside the image. */
    fun toNormalised(x: Float, y: Float): Offset? {
        if (displayWidth <= 0f || displayHeight <= 0f) return null
        val nx = (x - offsetX) / displayWidth
        val ny = (y - offsetY) / displayHeight
        if (nx < -0.05f || nx > 1.05f || ny < -0.05f || ny > 1.05f) return null
        return Offset(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))
    }

    /** 0..1 within the image -> box coordinates, for drawing. */
    fun toBox(nx: Float, ny: Float) =
        Offset(offsetX + nx * displayWidth, offsetY + ny * displayHeight)

    companion object {
        fun fit(boxW: Float, boxH: Float, imgW: Int, imgH: Int): FittedImage {
            if (imgW <= 0 || imgH <= 0 || boxW <= 0f || boxH <= 0f) {
                return FittedImage(0f, 0f, boxW, boxH)
            }
            val scale = min(boxW / imgW, boxH / imgH)
            val dw = imgW * scale
            val dh = imgH * scale
            return FittedImage((boxW - dw) / 2f, (boxH - dh) / 2f, dw, dh)
        }
    }
}

@Composable
fun rememberLassoState(): LassoState = remember { LassoState() }

/** Live path being drawn, exposed so the same state can be drawn and hit-tested. */
class LassoState {
    var points by mutableStateOf<List<Offset>>(emptyList())
        internal set

    val isDrawing: Boolean get() = points.isNotEmpty()

    internal fun clear() {
        points = emptyList()
    }
}

/**
 * Draw-to-select overlay.
 *
 * Kept as an explicit composable-modifier pair rather than a wrapper layout so it can
 * sit directly on the existing preview image without changing that layout.
 */
@Composable
fun Modifier.drawLasso(
    state: LassoState,
    imageWidth: Int,
    imageHeight: Int,
    enabled: Boolean,
    minSizeFraction: Float = 0.04f,
    onSelected: (CropRect) -> Unit,
): Modifier {
    val onSelectedState = rememberUpdatedState(onSelected)
    val enabledState = rememberUpdatedState(enabled)

    return this
        .pointerInput(imageWidth, imageHeight) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!enabledState.value) return@awaitEachGesture

                val fit = FittedImage.fit(
                    size.width.toFloat(), size.height.toFloat(),
                    imageWidth, imageHeight,
                )

                val start = fit.toNormalised(down.position.x, down.position.y)
                    ?: return@awaitEachGesture

                val collected = mutableListOf(start)
                var moved = false

                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break

                    if (change.positionChange() != Offset.Zero) {
                        moved = true
                        // Consume only once actually dragging, so a plain tap still
                        // reaches the click handler (fullscreen preview).
                        change.consume()
                        fit.toNormalised(change.position.x, change.position.y)
                            ?.let { collected.add(it) }
                        state.points = collected.toList()
                    }
                }

                if (!moved || collected.size < 3) {
                    state.clear()
                    return@awaitEachGesture
                }

                // Minimal enclosing rectangle of everything drawn.
                var left = 1f
                var top = 1f
                var right = 0f
                var bottom = 0f
                collected.forEach { p ->
                    left = min(left, p.x)
                    top = min(top, p.y)
                    right = max(right, p.x)
                    bottom = max(bottom, p.y)
                }

                state.clear()

                // Ignore accidental scribbles: too small to be a deliberate selection.
                if (right - left < minSizeFraction || bottom - top < minSizeFraction) {
                    return@awaitEachGesture
                }

                onSelectedState.value(CropRect(left, top, right, bottom).clamped())
            }
        }
        .drawWithContent {
            drawContent()
            val pts = state.points
            if (pts.size < 2) return@drawWithContent

            val fit = FittedImage.fit(
                size.width, size.height, imageWidth, imageHeight
            )

            // The stroke the finger is tracing.
            val path = Path()
            pts.forEachIndexed { i, p ->
                val o = fit.toBox(p.x, p.y)
                if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            drawPath(
                path,
                Color.White,
                style = Stroke(width = 4f),
            )

            // Live preview of the rectangle that will actually be used, so the outcome
            // is visible before the finger lifts.
            var l = 1f; var t = 1f; var r = 0f; var b = 0f
            pts.forEach {
                l = min(l, it.x); t = min(t, it.y)
                r = max(r, it.x); b = max(b, it.y)
            }
            val tl = fit.toBox(l, t)
            val br = fit.toBox(r, b)
            drawRect(
                Color.White.copy(alpha = 0.9f),
                topLeft = tl,
                size = Size(br.x - tl.x, br.y - tl.y),
                style = Stroke(
                    width = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
                ),
            )
            // Dim outside the pending rectangle.
            val shade = Color.Black.copy(alpha = 0.35f)
            drawRect(shade, Offset(0f, 0f), Size(size.width, tl.y))
            drawRect(shade, Offset(0f, br.y), Size(size.width, size.height - br.y))
            drawRect(shade, Offset(0f, tl.y), Size(tl.x, br.y - tl.y))
            drawRect(shade, Offset(br.x, tl.y), Size(size.width - br.x, br.y - tl.y))
        }
}

/**
 * Compose a selection made on an ALREADY CROPPED preview with the existing crop.
 *
 * Without this, drawing a second selection would be interpreted against the full image
 * and jump somewhere unrelated. Keeping selections relative lets the user narrow down
 * repeatedly, which is how a crop tool is expected to behave.
 */
fun CropRect.compose(selection: CropRect): CropRect = CropRect(
    left = left + selection.left * width,
    top = top + selection.top * height,
    right = left + selection.right * width,
    bottom = top + selection.bottom * height,
).clamped()
