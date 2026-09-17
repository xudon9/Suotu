package com.xudong.suotu

import android.graphics.Bitmap
import android.graphics.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Annotation editor: brush, rectangle, circle, arrow, text and pixelate.
 *
 * Layout follows the same rule the crop editor had to learn: the canvas takes the
 * LEFTOVER space of a bounded column so the toolbars and action row are always on
 * screen, whatever the image's aspect ratio.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AnnotationEditor(
    image: ImageBitmap,
    state: AnnotationState,
    onStateChange: (AnnotationState) -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    var tool by remember { mutableStateOf(AnnotationTool.BRUSH) }
    var color by remember { mutableStateOf(AnnotationPalette.default) }
    // Transparent by default: an outlined shape is the common case, and a filled one
    // would hide whatever it is pointing at.
    var fillColor by remember { mutableStateOf(COLOR_TRANSPARENT) }
    var picking by remember { mutableStateOf<PickTarget?>(null) }
    var thickness by remember { mutableStateOf(AnnotationThickness.DEFAULT) }
    var confirmClear by remember { mutableStateOf(false) }

    // In-progress gesture, drawn live but not yet committed.
    var draftPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var draftStart by remember { mutableStateOf<Offset?>(null) }
    var draftEnd by remember { mutableStateOf<Offset?>(null) }

    // Pending text placement, held while the input dialog is open.
    var textAt by remember { mutableStateOf<Offset?>(null) }
    var textValue by remember { mutableStateOf("") }

    val stateRef = rememberUpdatedState(state)
    val onStateChangeRef = rememberUpdatedState(onStateChange)
    val toolRef = rememberUpdatedState(tool)
    val colorRef = rememberUpdatedState(color)
    val fillRef = rememberUpdatedState(fillColor)
    val thicknessRef = rememberUpdatedState(thickness)

    // The bitmap the blur tool samples, so the live preview shows real mosaic pixels.
    val androidBitmap: Bitmap = remember(image) { image.asAndroidBitmap() }
    DisposableEffect(image) {
        onDispose {
            AnnotationRenderer.invalidateBlurCache()
            CommittedOverlayCache.clear()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.annotate_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        val ratio = image.width.toFloat() / image.height.toFloat()

        // The canvas takes the leftover space but is CAPPED, so it cannot squeeze the
        // toolbars below it to zero height. Without the cap, a tall screenshot claimed
        // everything and the colour palette was laid out at zero height — present in
        // the tree, invisible on screen.
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(max = 420.dp),
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

                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(image) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()

                                val fit = FittedImage.fit(
                                    size.width.toFloat(), size.height.toFloat(),
                                    image.width, image.height,
                                )
                                val start = fit.toNormalised(
                                    down.position.x, down.position.y
                                ) ?: return@awaitEachGesture

                                val activeTool = toolRef.value
                                var last = start
                                val collected = mutableListOf(start)
                                var moved = false

                                draftStart = start
                                draftEnd = start
                                if (activeTool == AnnotationTool.BRUSH) {
                                    draftPoints = collected.toList()
                                }

                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val change = event.changes
                                        .firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    change.consume()

                                    fit.toNormalised(
                                        change.position.x, change.position.y
                                    )?.let { p ->
                                        if (p != last) moved = true
                                        last = p
                                        draftEnd = p
                                        if (activeTool == AnnotationTool.BRUSH) {
                                            collected.add(p)
                                            draftPoints = collected.toList()
                                        }
                                    }
                                }

                                val current = stateRef.value
                                val c = colorRef.value
                                val t = thicknessRef.value
                                val f = fillRef.value

                                // A tap (no drag) selects instead of drawing, which is
                                // how the object model stays reachable after the fact.
                                if (!moved) {
                                    draftPoints = emptyList()
                                    draftStart = null
                                    draftEnd = null
                                    if (activeTool == AnnotationTool.TEXT) {
                                        textAt = start
                                        textValue = ""
                                    } else {
                                        onStateChangeRef.value(
                                            current.select(current.hitTest(start))
                                        )
                                    }
                                    return@awaitEachGesture
                                }

                                val committed: Annotation? = when (activeTool) {
                                    AnnotationTool.BRUSH ->
                                        if (collected.size >= 2) {
                                            Annotation.Freehand(collected.toList(), c, t)
                                        } else null

                                    AnnotationTool.RECT ->
                                        if (isDeliberateDrag(start, last)) {
                                            Annotation.Rect(start, last, c, t, f)
                                        } else null

                                    AnnotationTool.CIRCLE ->
                                        if (isDeliberateDrag(start, last)) {
                                            Annotation.Ellipse(start, last, c, t, f)
                                        } else null

                                    AnnotationTool.ARROW ->
                                        if (isDeliberateDrag(start, last)) {
                                            Annotation.Arrow(start, last, c, t)
                                        } else null

                                    // Text is placed by tap, handled above.
                                    AnnotationTool.TEXT -> null
                                }

                                draftPoints = emptyList()
                                draftStart = null
                                draftEnd = null
                                if (committed != null) {
                                    onStateChangeRef.value(current.add(committed))
                                }
                            }
                        }
                        .drawWithContent {
                            drawContent()
                            val fit = FittedImage.fit(
                                size.width, size.height, image.width, image.height
                            )

                            // Committed annotations are rendered ONCE into an overlay
                            // bitmap and then blitted, rather than re-rendered every
                            // frame.
                            //
                            // Re-rendering cost grew with the number of blur shapes:
                            // each one clips and blits a full-canvas blurred bitmap, so
                            // one shape was fine but the second and third made a drag
                            // visibly lag. The overlay only changes when the committed
                            // list changes, which is never during a drag.
                            val overlay = committedOverlay(
                                stateRef.value.items,
                                fit.displayWidth.toInt(),
                                fit.displayHeight.toInt(),
                                androidBitmap,
                            )

                            drawContext.canvas.nativeCanvas.let { native ->
                                val save = native.save()
                                native.translate(fit.offsetX, fit.offsetY)
                                if (overlay != null) {
                                    native.drawBitmap(overlay, 0f, 0f, null)
                                }

                                // The gesture in flight.
                                val draft = buildDraft(
                                    toolRef.value,
                                    draftPoints,
                                    draftStart,
                                    draftEnd,
                                    colorRef.value,
                                    thicknessRef.value,
                                    fillRef.value,
                                )
                                if (draft != null) {
                                    // A blur-painted draft is shown as an OUTLINE while
                                    // the finger is down. A Gaussian pass costs tens of
                                    // milliseconds, so blurring every frame made the
                                    // drag lag behind the finger; an outline that tracks
                                    // it exactly feels responsive, and the real blur
                                    // appears the moment the finger lifts.
                                    val isBlurDraft = colorRef.value.isBlur() ||
                                        fillRef.value.isBlur()
                                    if (isBlurDraft) {
                                        AnnotationRenderer.drawBlurPlaceholder(
                                            native,
                                            draft,
                                            fit.displayWidth.toInt(),
                                            fit.displayHeight.toInt(),
                                        )
                                    } else {
                                        AnnotationRenderer.render(
                                            native,
                                            listOf(draft),
                                            fit.displayWidth.toInt(),
                                            fit.displayHeight.toInt(),
                                            sourceForBlur = androidBitmap,
                                        )
                                    }
                                }
                                native.restoreToCount(save)
                            }

                            // Selection outline, drawn in Compose so it is clearly UI
                            // chrome rather than part of the image.
                            stateRef.value.selectedIndex
                                ?.let { stateRef.value.items.getOrNull(it) }
                                ?.bounds()
                                ?.let { b ->
                                    val tl = fit.toBox(b.left, b.top)
                                    val br = fit.toBox(b.right, b.bottom)
                                    val pad = 6f
                                    drawRect(
                                        Color(0xFF1E88E5),
                                        topLeft = Offset(tl.x - pad, tl.y - pad),
                                        size = Size(
                                            br.x - tl.x + pad * 2,
                                            br.y - tl.y + pad * 2,
                                        ),
                                        style = Stroke(
                                            width = 3f,
                                            pathEffect = PathEffect.dashPathEffect(
                                                floatArrayOf(12f, 8f)
                                            ),
                                        ),
                                    )
                                }
                        },
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Tools.
        //
        // FlowRow, not Row: six chips do not fit across a 1080px phone, and a plain Row
        // silently pushed the last one (Blur) off-screen entirely.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AnnotationTool.entries.forEach { t ->
                FilterChip(
                    selected = t == tool,
                    onClick = { tool = t },
                    label = {
                        Text(
                            stringResource(t.labelRes),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Colour. Shapes have TWO (stroke and fill); everything else has one.
        run {
            val hasFill = tool == AnnotationTool.RECT || tool == AnnotationTool.CIRCLE
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ColorButton(
                    labelRes = R.string.color_stroke,
                    color = color,
                    onClick = { picking = PickTarget.STROKE },
                )
                if (hasFill) {
                    ColorButton(
                        labelRes = R.string.color_fill,
                        color = fillColor,
                        onClick = { picking = PickTarget.FILL },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // Thickness
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.annotate_thickness),
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = thickness,
                onValueChange = { thickness = it },
                valueRange = AnnotationThickness.MIN..AnnotationThickness.MAX,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }

        // Actions. FlowRow for the same reason: with a selection active there are five
        // controls here, which overflow a narrow screen.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(
                onClick = { onStateChange(state.undo()) },
                enabled = !state.isEmpty,
            ) { Text(stringResource(R.string.annotate_undo)) }

            TextButton(
                onClick = { confirmClear = true },
                enabled = !state.isEmpty,
            ) { Text(stringResource(R.string.annotate_clear)) }

            // Only offered when a shape is actually selected.
            if (state.selectedIndex != null) {
                TextButton(onClick = { onStateChange(state.removeSelected()) }) {
                    Text(stringResource(R.string.remove))
                }
            }

            OutlinedButton(onClick = onCancel) {
                Text(stringResource(R.string.annotate_cancel))
            }
            Button(onClick = onApply) {
                Text(stringResource(R.string.annotate_apply))
            }
        }
    }

    // Colour picker, shared by the stroke and fill wells.
    picking?.let { target ->
        ColorPickerDialog(
            title = stringResource(
                if (target == PickTarget.STROKE) R.string.color_pick_stroke
                else R.string.color_pick_fill
            ),
            initial = if (target == PickTarget.STROKE) color else fillColor,
            // A shape with no stroke AND no fill would be invisible, so transparency is
            // only offered where it still leaves something to see.
            allowTransparent = target == PickTarget.FILL ||
                !fillColor.isTransparent(),
            // Blur paints with the image itself, so it only makes sense where there is
            // an area or a stroke to fill. An arrow or text drawn in blur would be
            // invisible against what it is annotating.
            allowBlur = tool != AnnotationTool.ARROW && tool != AnnotationTool.TEXT,
            onDismiss = { picking = null },
            onPick = { picked ->
                if (target == PickTarget.STROKE) color = picked else fillColor = picked
                picking = null
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text(stringResource(R.string.annotate_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onStateChange(state.clear())
                }) { Text(stringResource(R.string.annotate_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    textAt?.let { at ->
        AlertDialog(
            onDismissRequest = { textAt = null },
            title = { Text(stringResource(R.string.annotate_tool_text)) },
            text = {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = { textValue = it },
                    label = { Text(stringResource(R.string.annotate_text_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (textValue.isNotBlank()) {
                            onStateChange(
                                state.add(
                                    Annotation.Text(at, textValue.trim(), color, thickness)
                                )
                            )
                        }
                        textAt = null
                    },
                    enabled = textValue.isNotBlank(),
                ) { Text(stringResource(R.string.annotate_text_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { textAt = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * Cached render of the COMMITTED annotations, at preview size.
 *
 * Rebuilt only when the item list or the canvas size changes. During a drag neither
 * changes, so each frame becomes a single bitmap blit regardless of how many blur
 * shapes are already on the canvas — which is what stopped the second and third blur
 * rectangle from making the drag lag.
 */
private object CommittedOverlayCache {
    private var items: List<Annotation>? = null
    private var width = 0
    private var height = 0
    private var source: Bitmap? = null
    private var bitmap: Bitmap? = null

    fun get(
        items: List<Annotation>,
        width: Int,
        height: Int,
        source: Bitmap,
    ): Bitmap? {
        if (items.isEmpty() || width < 1 || height < 1) return null

        val cached = bitmap
        if (cached != null && !cached.isRecycled &&
            this.width == width && this.height == height &&
            this.source === source && this.items == items
        ) {
            return cached
        }

        cached?.recycle()
        val fresh = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        AnnotationRenderer.render(
            Canvas(fresh), items, width, height, sourceForBlur = source
        )
        this.items = items
        this.width = width
        this.height = height
        this.source = source
        bitmap = fresh
        return fresh
    }

    fun clear() {
        bitmap?.recycle()
        bitmap = null
        items = null
        source = null
    }
}

private fun committedOverlay(
    items: List<Annotation>,
    width: Int,
    height: Int,
    source: Bitmap,
): Bitmap? = CommittedOverlayCache.get(items, width, height, source)

/** Which colour the picker is currently editing. */
private enum class PickTarget { STROKE, FILL }

/**
 * Colour well with a label, opening the full picker.
 *
 * Shows a checkerboard when the colour is transparent, so "no fill" is visibly a state
 * rather than looking like a rendering glitch.
 */
@Composable
private fun ColorButton(labelRes: Int, color: Long, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            stringResource(labelRes),
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.size(6.dp))
        Box(
            Modifier
                .size(30.dp)
                .background(
                    if (color.isTransparent()) Color(0xFFDDDDDD) else Color(color.toInt()),
                    CircleShape,
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (color.isTransparent()) {
                Text("\u2571", style = MaterialTheme.typography.bodyMedium, color = Color.Red)
            }
        }
    }
}

/** The in-flight gesture as a renderable annotation, or null if there is nothing yet. */
private fun buildDraft(
    tool: AnnotationTool,
    points: List<Offset>,
    start: Offset?,
    end: Offset?,
    color: Long,
    thickness: Float,
    fill: Long,
): Annotation? {
    if (tool == AnnotationTool.BRUSH) {
        return if (points.size >= 2) {
            Annotation.Freehand(points, color, thickness)
        } else null
    }
    val s = start ?: return null
    val e = end ?: return null
    return when (tool) {
        AnnotationTool.RECT -> Annotation.Rect(s, e, color, thickness, fill)
        AnnotationTool.CIRCLE -> Annotation.Ellipse(s, e, color, thickness, fill)
        AnnotationTool.ARROW -> Annotation.Arrow(s, e, color, thickness)
        else -> null
    }
}
