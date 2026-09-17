package com.xudong.suotu

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Which part of the crop box a drag is manipulating. */
private enum class Handle { TL, TR, BL, BR, LEFT, RIGHT, TOP, BOTTOM, INSIDE, NONE }

/** Finger-sized grab radius for the handles. */
private val GRAB_RADIUS = 28.dp

/**
 * Interactive crop box drawn over [image].
 *
 * Emits normalised coordinates so the result stays valid regardless of display size or
 * the decode scale used later.
 *
 * Three things here are deliberate, each having been a bug:
 *
 *  1. The grab radius is converted from dp to *pixels* and then to a normalised
 *     distance per axis. An earlier version divided a dp number by a pixel width, which
 *     collapsed the horizontal grab zone to a few pixels while leaving the vertical one
 *     wide — on a tall screenshot that made corners unhittable and turned nearly every
 *     drag into a vertical resize.
 *  2. The gesture detector is NOT keyed on the crop rect. Re-keying rebuilt the detector
 *     on every change, cancelling the gesture mid-drag; the live value is read through
 *     [rememberUpdatedState] instead.
 *  3. Touches are consumed, so the enclosing vertical scroll container cannot steal a
 *     vertical drag.
 */
@Composable
fun CropEditor(
    image: ImageBitmap,
    crop: CropRect,
    onCropChange: (CropRect) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    // Read inside the gesture loop without restarting it.
    val cropState = rememberUpdatedState(crop)
    val onCropChangeState = rememberUpdatedState(onCropChange)
    val grabPx = with(LocalDensity.current) { GRAB_RADIUS.toPx() }

    // A tall screenshot must not push the buttons off-screen. The canvas is bounded by
    // BOTH available dimensions and letterboxed, and the action row is pinned outside
    // the image area — an earlier version used fillMaxWidth().aspectRatio(), which on a
    // 1260x2800 shot produced a 2400px-tall canvas and buried the buttons below the fold
    // (made worse by disabling the parent scroll to protect the drag gestures).
    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.crop_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        val ratio = image.width.toFloat() / image.height.toFloat()

        // weight(1f) gives the canvas the leftover space, so the row below is always
        // laid out first and stays visible whatever the image shape.
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .aspectRatio(ratio)
                    .background(MaterialTheme.colorScheme.previewBackdrop),
            ) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )

                var activeHandle by remember { mutableStateOf(Handle.NONE) }

                Box(
                    Modifier
                        // Fills the letterboxed image area, so touch coordinates map
                        // directly onto the pixels being cropped.
                        .fillMaxSize()
                    // Not keyed on `crop`: see note 2 above.
                    .pointerInput(grabPx) {
                        awaitEachGesture {
                            // Consume the press so the parent scroll cannot claim it.
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()

                            // Grab distance in normalised units, per axis. Converting
                            // separately is what keeps horizontal and vertical handles
                            // equally easy to hit on a non-square image.
                            val slopX = (grabPx / size.width).coerceIn(0.02f, 0.25f)
                            val slopY = (grabPx / size.height).coerceIn(0.02f, 0.25f)

                            activeHandle = pickHandle(
                                cropState.value,
                                down.position.x / size.width,
                                down.position.y / size.height,
                                slopX,
                                slopY,
                            )
                            if (activeHandle == Handle.NONE) return@awaitEachGesture

                            // Track the drag manually so every move is consumed.
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                val change = event.changes.firstOrNull { it.id == down.id }
                                    ?: break
                                if (!change.pressed) break

                                val delta = change.positionChange()
                                change.consume()

                                if (delta != Offset.Zero) {
                                    onCropChangeState.value(
                                        applyDrag(
                                            cropState.value,
                                            activeHandle,
                                            delta.x / size.width,
                                            delta.y / size.height,
                                        )
                                    )
                                }
                            }
                            activeHandle = Handle.NONE
                        }
                    }
                    .drawWithContent {
                        drawContent()
                        val c = cropState.value
                        val l = c.left * size.width
                        val t = c.top * size.height
                        val r = c.right * size.width
                        val b = c.bottom * size.height

                        // Dim everything outside the selection.
                        val shade = Color.Black.copy(alpha = 0.5f)
                        drawRect(shade, Offset(0f, 0f), Size(size.width, t))
                        drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
                        drawRect(shade, Offset(0f, t), Size(l, b - t))
                        drawRect(shade, Offset(r, t), Size(size.width - r, b - t))

                        // Selection border.
                        drawRect(
                            Color.White,
                            Offset(l, t),
                            Size(r - l, b - t),
                            style = Stroke(width = 3f),
                        )

                        // Thirds guides.
                        val guide = Color.White.copy(alpha = 0.35f)
                        for (i in 1..2) {
                            val x = l + (r - l) * i / 3f
                            val y = t + (b - t) * i / 3f
                            drawLine(guide, Offset(x, t), Offset(x, b), 1f)
                            drawLine(guide, Offset(l, y), Offset(r, y), 1f)
                        }

                        // Corner grips, drawn inward so they never clip off-canvas.
                        val arm = 30f
                        val thick = 6f
                        fun corner(cx: Float, cy: Float, sx: Int, sy: Int) {
                            drawRect(
                                Color.White,
                                Offset(if (sx > 0) cx else cx - arm, cy - thick / 2),
                                Size(arm, thick),
                            )
                            drawRect(
                                Color.White,
                                Offset(cx - thick / 2, if (sy > 0) cy else cy - arm),
                                Size(thick, arm),
                            )
                        }
                        corner(l, t, 1, 1)
                        corner(r, t, -1, 1)
                        corner(l, b, 1, -1)
                        corner(r, b, -1, -1)

                        // Edge grips: a short bar mid-edge, so the side handles are
                        // visibly grabbable rather than an invisible hit zone.
                        val midBar = 40f
                        drawRect(
                            Color.White,
                            Offset(l - thick / 2, (t + b) / 2 - midBar / 2),
                            Size(thick, midBar),
                        )
                        drawRect(
                            Color.White,
                            Offset(r - thick / 2, (t + b) / 2 - midBar / 2),
                            Size(thick, midBar),
                        )
                        drawRect(
                            Color.White,
                            Offset((l + r) / 2 - midBar / 2, t - thick / 2),
                            Size(midBar, thick),
                        )
                        drawRect(
                            Color.White,
                            Offset((l + r) / 2 - midBar / 2, b - thick / 2),
                            Size(midBar, thick),
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Pinned action row: laid out before the canvas takes its leftover space, so it
        // is always on screen regardless of image aspect ratio.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { onCropChange(CropRect.FULL) }) {
                Text(stringResource(R.string.crop_full))
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
            Button(onClick = onApply) { Text(stringResource(R.string.crop_apply)) }
        }
    }
}

/**
 * Nearest handle to the touch point.
 *
 * Corners win over edges, and an edge only wins if the touch is clearly along that edge
 * rather than near a corner — otherwise, on a tall image, a side grab gets
 * misinterpreted as a corner or a top/bottom resize.
 */
private fun pickHandle(
    crop: CropRect,
    nx: Float,
    ny: Float,
    slopX: Float,
    slopY: Float,
): Handle {
    val dLeft = abs(nx - crop.left)
    val dRight = abs(nx - crop.right)
    val dTop = abs(ny - crop.top)
    val dBottom = abs(ny - crop.bottom)

    val nearLeft = dLeft < slopX
    val nearRight = dRight < slopX
    val nearTop = dTop < slopY
    val nearBottom = dBottom < slopY

    // Corners first, choosing the closest if several qualify.
    val corners = buildList {
        if (nearLeft && nearTop) add(Handle.TL to (dLeft / slopX + dTop / slopY))
        if (nearRight && nearTop) add(Handle.TR to (dRight / slopX + dTop / slopY))
        if (nearLeft && nearBottom) add(Handle.BL to (dLeft / slopX + dBottom / slopY))
        if (nearRight && nearBottom) add(Handle.BR to (dRight / slopX + dBottom / slopY))
    }
    corners.minByOrNull { it.second }?.let { return it.first }

    // Edges, scored in slop-relative units so neither axis is favoured.
    val withinV = ny > crop.top - slopY && ny < crop.bottom + slopY
    val withinH = nx > crop.left - slopX && nx < crop.right + slopX
    val edges = buildList {
        if (nearLeft && withinV) add(Handle.LEFT to dLeft / slopX)
        if (nearRight && withinV) add(Handle.RIGHT to dRight / slopX)
        if (nearTop && withinH) add(Handle.TOP to dTop / slopY)
        if (nearBottom && withinH) add(Handle.BOTTOM to dBottom / slopY)
    }
    edges.minByOrNull { it.second }?.let { return it.first }

    val inside = nx > crop.left && nx < crop.right && ny > crop.top && ny < crop.bottom
    return if (inside) Handle.INSIDE else Handle.NONE
}

private fun applyDrag(crop: CropRect, handle: Handle, dx: Float, dy: Float): CropRect =
    when (handle) {
        Handle.TL -> crop.copy(left = crop.left + dx, top = crop.top + dy)
        Handle.TR -> crop.copy(right = crop.right + dx, top = crop.top + dy)
        Handle.BL -> crop.copy(left = crop.left + dx, bottom = crop.bottom + dy)
        Handle.BR -> crop.copy(right = crop.right + dx, bottom = crop.bottom + dy)
        Handle.LEFT -> crop.copy(left = crop.left + dx)
        Handle.RIGHT -> crop.copy(right = crop.right + dx)
        Handle.TOP -> crop.copy(top = crop.top + dy)
        Handle.BOTTOM -> crop.copy(bottom = crop.bottom + dy)
        Handle.INSIDE -> crop.translated(dx, dy)
        Handle.NONE -> crop
    }.clamped()
