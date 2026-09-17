package com.xudong.suotu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalContext

/**
 * Fixed-size icon action for the bottom bar.
 *
 * Replaces the previous row of weighted text buttons, which broke in two ways on a real
 * phone: five Chinese labels could not fit across 1260px, so each was clipped to a
 * single glyph (标注 and 标准 became indistinguishable), and the row overflowed the
 * bottom of the screen. Icons are a FIXED size, so the row's width no longer depends on
 * how long a translated label happens to be.
 */
@Composable
fun IconAction(
    iconRes: Int,
    labelRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    emphasised: Boolean = false,
    caption: String? = null,
) {
    val context = LocalContext.current
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        emphasised -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val background = when {
        emphasised && enabled -> MaterialTheme.colorScheme.primary
        else -> Color.Transparent
    }

    Column(
        modifier = modifier
            .width(ACTION_WIDTH)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            color = background,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.size(ACTION_WIDTH, 40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = ImageVector.vectorResource(iconRes),
                    contentDescription = context.getString(labelRes),
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        // Label kept to one short line: it is a hint under a recognisable icon, not the
        // primary affordance, so clipping it is harmless rather than fatal.
        Text(
            caption ?: stringResource(labelRes),
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
            modifier = Modifier.width(ACTION_WIDTH),
        )
    }
}

/**
 * Bottom action bar.
 *
 * Evenly spaced fixed-width items rather than weighted ones, so adding a sixth action
 * or a longer translation cannot squeeze the others into illegibility.
 */
@Composable
fun ActionBar(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom,
    ) {
        content()
    }
}

/** Fixed width for every action, so the bar's layout is label-independent. */
private val ACTION_WIDTH = 56.dp
