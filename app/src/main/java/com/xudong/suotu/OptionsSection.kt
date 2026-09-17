package com.xudong.suotu

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Width / quality / format, collapsed to a single summary line by default.
 *
 * These are set-once-and-forget settings: the width and format are remembered across
 * launches and quality defaults to Auto, so showing three sliders and two chip rows on
 * every open cost roughly a third of the screen for controls that are rarely touched —
 * and pushed the action buttons off the bottom edge entirely.
 *
 * Collapsed, the header still states the *effective* settings, so nothing is hidden,
 * merely folded.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OptionsSection(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    width: Int,
    onWidthChange: (Int) -> Unit,
    onWidthSettled: () -> Unit,
    manualQuality: Int?,
    actualQuality: Int?,
    onQualityChange: (Int?) -> Unit,
    /** Format the engine settled on, shown on the Auto chip. Null before the first run. */
    actualFormat: String?,
    policy: FormatPolicy,
    onPolicyChange: (FormatPolicy) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            // Header doubles as the toggle: the whole row is the touch target, which is
            // more forgiving than a small chevron.
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = ImageVector.vectorResource(R.drawable.ic_action_tune),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.options),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // The summary is what makes collapsing safe: the current settings
                    // stay visible without occupying a control each.
                    Text(
                        stringResource(
                            R.string.options_summary,
                            width,
                            when {
                                manualQuality != null -> stringResource(
                                    R.string.quality_summary_manual, manualQuality
                                )
                                actualQuality != null -> stringResource(
                                    R.string.quality_summary_auto, actualQuality
                                )
                                else -> stringResource(R.string.quality_auto)
                            },
                            // The summary reports the format in EFFECT, so a folded
                            // card still tells you what you are about to send.
                            if (policy == FormatPolicy.AUTO && actualFormat != null) {
                                actualFormat
                            } else {
                                stringResource(policy.labelRes)
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Icon(
                    imageVector = ImageVector.vectorResource(
                        if (expanded) R.drawable.ic_action_expand_less
                        else R.drawable.ic_action_expand_more
                    ),
                    contentDescription = stringResource(
                        if (expanded) R.string.hide_options else R.string.show_options
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Column(
                    Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                ) {
                    // Width
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.width_label),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(R.string.width_value, width),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Slider(
                        value = width.toFloat(),
                        onValueChange = {
                            onWidthChange(
                                (it / SizeRange.STEP).toInt() * SizeRange.STEP
                            )
                        },
                        onValueChangeFinished = onWidthSettled,
                        valueRange = SizeRange.MIN_WIDTH.toFloat()..
                            SizeRange.MAX_WIDTH.toFloat(),
                    )
                    // FlowRow, not Row: five width chips plus their padding can overflow
                    // a narrow screen, and a plain Row would silently push the last one
                    // off the edge — which is exactly how the Blur tool once became
                    // unreachable.
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SizeRange.quickPicks.forEach { preset ->
                            FilterChip(
                                selected = width == preset.targetWidth,
                                onClick = {
                                    onWidthChange(preset.targetWidth)
                                    onWidthSettled()
                                },
                                label = {
                                    Text(
                                        "${preset.targetWidth}",
                                        fontSize = 12.sp,
                                    )
                                },
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    // Quality
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.quality_label),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            when {
                                manualQuality != null -> stringResource(
                                    R.string.quality_manual, manualQuality
                                )
                                actualQuality != null -> stringResource(
                                    R.string.quality_auto_detail, actualQuality
                                )
                                else -> stringResource(R.string.quality_auto)
                            },
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FilterChip(
                            selected = manualQuality == null,
                            onClick = { onQualityChange(null) },
                            label = {
                                Text(
                                    stringResource(R.string.quality_auto),
                                    fontSize = 12.sp,
                                )
                            },
                        )
                        Slider(
                            // Seeded from the value Auto just chose, so switching to
                            // manual does not jump the image to an unrelated quality.
                            value = (manualQuality ?: actualQuality ?: 80).toFloat(),
                            onValueChange = { onQualityChange(it.toInt()) },
                            valueRange = Settings.QUALITY_MIN.toFloat()..
                                Settings.QUALITY_MAX.toFloat(),
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    // Format. The Auto chip names the format it actually chose, so the
                    // outcome is visible without having to read the stats line.
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FormatPolicy.entries.forEach { f ->
                            FilterChip(
                                selected = f == policy,
                                onClick = { onPolicyChange(f) },
                                label = {
                                    Text(
                                        if (f == FormatPolicy.AUTO &&
                                            actualFormat != null
                                        ) {
                                            stringResource(
                                                R.string.format_auto_chosen,
                                                actualFormat,
                                            )
                                        } else {
                                            stringResource(f.labelRes)
                                        },
                                        fontSize = 12.sp,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
