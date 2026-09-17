package com.xudong.suotu

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Transparent is represented as alpha 0 rather than a null colour.
 *
 * Keeping one numeric type for "a colour" and "no colour" means the renderer only has
 * to test alpha, and a transparent value still round-trips through the picker with its
 * hue intact — so turning fill off and on again does not lose the chosen colour.
 */
const val COLOR_TRANSPARENT: Long = 0x00000000L

fun Long.isTransparent(): Boolean = (this ushr 24) == 0L

/** Quick presets, kept short: a long row of swatches slows down a two-second markup. */
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

/**
 * Full HSV colour picker with alpha and an explicit "none".
 *
 * Replaces a fixed six-swatch row. The presets are still offered as a top row because
 * they cover most annotations in one tap, but any colour is now reachable, and
 * transparency is a first-class choice rather than an absent one.
 */
@Composable
fun ColorPickerDialog(
    title: String,
    initial: Long,
    allowTransparent: Boolean,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    // Seed HSV from the incoming colour so reopening the picker does not reset it.
    val seed = if (initial.isTransparent()) AnnotationPalette.default else initial
    val seedHsv = remember(seed) { rgbToHsv(seed) }

    var hue by remember { mutableFloatStateOf(seedHsv[0]) }
    var sat by remember { mutableFloatStateOf(seedHsv[1]) }
    var value by remember { mutableFloatStateOf(seedHsv[2]) }
    var alpha by remember {
        mutableFloatStateOf(if (initial.isTransparent()) 1f else ((initial ushr 24) / 255f))
    }
    var transparent by remember { mutableStateOf(initial.isTransparent()) }

    val current = if (transparent) {
        COLOR_TRANSPARENT
    } else {
        hsvToRgb(hue, sat, value, alpha)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // Presets
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AnnotationPalette.colors.forEach { c ->
                        Swatch(
                            color = c,
                            selected = !transparent && current or 0xFF000000L ==
                                c or 0xFF000000L,
                            onClick = {
                                transparent = false
                                val hsv = rgbToHsv(c)
                                hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                            },
                        )
                    }
                    if (allowTransparent) {
                        TransparentSwatch(
                            selected = transparent,
                            onClick = { transparent = true },
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                // Saturation / value area for the current hue.
                val hueColor = Color(hsvToRgb(hue, 1f, 1f, 1f).toInt())
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(hueColor)
                        .pointerInput(Unit) {
                            fun update(p: Offset) {
                                transparent = false
                                sat = (p.x / size.width).coerceIn(0f, 1f)
                                value = 1f - (p.y / size.height).coerceIn(0f, 1f)
                            }
                            detectTapGestures { update(it) }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                transparent = false
                                sat = (change.position.x / size.width).coerceIn(0f, 1f)
                                value =
                                    1f - (change.position.y / size.height)
                                        .coerceIn(0f, 1f)
                            }
                        },
                ) {
                    // White-to-transparent horizontally, transparent-to-black vertically:
                    // the standard SV square, composed from two gradients.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color.White, Color.Transparent)
                                )
                            )
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.Black)
                                )
                            )
                    )
                    // Crosshair.
                    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                        val cx = sat * size.width
                        val cy = (1f - value) * size.height
                        drawCircle(Color.White, radius = 9f, center = Offset(cx, cy))
                        drawCircle(Color.Black, radius = 6f, center = Offset(cx, cy))
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Hue strip.
                GradientSlider(
                    colors = (0..6).map { Color(hsvToRgb(it * 60f, 1f, 1f, 1f).toInt()) },
                    position = hue / 360f,
                    onPosition = {
                        transparent = false
                        hue = it * 360f
                    },
                )

                Spacer(Modifier.height(10.dp))

                // Alpha strip, from fully transparent to the current colour.
                GradientSlider(
                    colors = listOf(
                        Color(hsvToRgb(hue, sat, value, 0f).toInt()),
                        Color(hsvToRgb(hue, sat, value, 1f).toInt()),
                    ),
                    position = alpha,
                    onPosition = {
                        transparent = false
                        alpha = it
                    },
                    checkered = true,
                )

                Spacer(Modifier.height(14.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.color_preview),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(10.dp))
                    Box(
                        Modifier
                            .size(48.dp, 28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .checkerboard()
                            .background(Color(current.toInt()))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(6.dp),
                            ),
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        if (transparent) {
                            stringResource(R.string.color_none)
                        } else {
                            "#%08X".format(current)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(current) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun GradientSlider(
    colors: List<Color>,
    position: Float,
    onPosition: (Float) -> Unit,
    checkered: Boolean = false,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .then(if (checkered) Modifier.checkerboard() else Modifier)
            .background(Brush.horizontalGradient(colors))
            .pointerInput(Unit) {
                detectTapGestures { onPosition((it.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    onPosition((change.position.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val x = position * size.width
            drawCircle(Color.White, radius = 10f, center = Offset(x, size.height / 2))
            drawCircle(Color.Black, radius = 7f, center = Offset(x, size.height / 2))
        }
    }
}

@Composable
private fun Swatch(color: Long, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(if (selected) 34.dp else 28.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(color.toInt()))
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(50),
            )
            .clickable(onClick = onClick),
    )
}

/** "No colour", drawn as a checkerboard with a diagonal strike. */
@Composable
private fun TransparentSwatch(selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(if (selected) 34.dp else 28.dp)
            .clip(RoundedCornerShape(50))
            .checkerboard()
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(50),
            )
            .clickable(onClick = onClick),
    ) {
        Canvas(Modifier.fillMaxWidth().height(34.dp)) {
            drawLine(
                Color.Red,
                Offset(size.width * 0.2f, size.height * 0.8f),
                Offset(size.width * 0.8f, size.height * 0.2f),
                strokeWidth = 3f,
            )
        }
    }
}

/** Grey checkerboard, the conventional way to show transparency. */
private fun Modifier.checkerboard(): Modifier = this.background(Color(0xFFDDDDDD))

/** @return [hue 0..360, saturation 0..1, value 0..1] */
private fun rgbToHsv(argb: Long): FloatArray {
    val r = ((argb ushr 16) and 0xFF).toInt() / 255f
    val g = ((argb ushr 8) and 0xFF).toInt() / 255f
    val b = (argb and 0xFF).toInt() / 255f
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min

    val h = when {
        d == 0f -> 0f
        max == r -> 60f * (((g - b) / d) % 6f)
        max == g -> 60f * (((b - r) / d) + 2f)
        else -> 60f * (((r - g) / d) + 4f)
    }
    return floatArrayOf(
        if (h < 0) h + 360f else h,
        if (max == 0f) 0f else d / max,
        max,
    )
}

private fun hsvToRgb(hue: Float, sat: Float, value: Float, alpha: Float): Long {
    val h = ((hue % 360f) + 360f) % 360f
    val c = value * sat
    val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
    val m = value - c
    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    val a = (alpha * 255).toInt().coerceIn(0, 255).toLong()
    val r = ((r1 + m) * 255).toInt().coerceIn(0, 255).toLong()
    val g = ((g1 + m) * 255).toInt().coerceIn(0, 255).toLong()
    val b = ((b1 + m) * 255).toInt().coerceIn(0, 255).toLong()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
