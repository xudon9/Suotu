package com.xudong.suotu

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Fallback palette for Android 11 and below, where dynamic color is unavailable.
// Green to match the launcher icon.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1F6F4A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA8F2C8),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF4E6355),
    surfaceVariant = Color(0xFFDCE5DB),
    onSurfaceVariant = Color(0xFF404943),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CD6AD),
    onPrimary = Color(0xFF00391F),
    primaryContainer = Color(0xFF005234),
    onPrimaryContainer = Color(0xFFA8F2C8),
    secondary = Color(0xFFB5CCBB),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFBFC9C1),
)

/**
 * App theme. Follows the system dark-mode setting and, on Android 12+, adopts the
 * user's wallpaper-derived Material You palette.
 */
@Composable
fun SuotuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Status/navigation icons must contrast with the surface we actually draw,
            // otherwise they vanish when the theme flips.
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}

/**
 * Backdrop for the preview image. Screenshots are often light with white margins, so
 * the letterbox area needs to stay distinguishable from the image in both themes.
 */
val ColorScheme.previewBackdrop: Color
    get() = if (surface.luminance() < 0.5f) {
        Color(0xFF1A1C1A)
    } else {
        Color(0xFFF2F2F2)
    }
