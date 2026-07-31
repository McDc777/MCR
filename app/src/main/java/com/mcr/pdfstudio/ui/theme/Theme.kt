package com.mcr.pdfstudio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Vanta-Mc-UI theme.
 *
 * Material's own accents are deliberately neutral here. The design law is that
 * saturated colour appears only on a surface's stroke, so if the primary colour
 * were a hue every switch, slider and chip would fill with it and compete with
 * the strokes. Keeping the palette greyscale leaves the neon edges as the only
 * chroma on screen.
 */

private val Ink = Color(0xFFF4F6FA)
private val InkMuted = Color(0xFFA8AEBC)
private val Backdrop = Color(0xFF0B0C10)

private val VantaScheme = darkColorScheme(
    primary = Ink,
    onPrimary = Color(0xFF14161B),
    primaryContainer = Color(0x1FFFFFFF),
    onPrimaryContainer = Ink,

    secondary = InkMuted,
    onSecondary = Color(0xFF14161B),
    secondaryContainer = Color(0x14FFFFFF),
    onSecondaryContainer = Ink,

    tertiary = InkMuted,
    onTertiary = Color(0xFF14161B),

    // Surfaces stay transparent: every pane is drawn by VantaSurface, and a
    // painted Material surface behind it would block the backdrop.
    background = Backdrop,
    onBackground = Ink,
    surface = Color.Transparent,
    onSurface = Ink,
    surfaceVariant = Color(0x14FFFFFF),
    onSurfaceVariant = InkMuted,
    surfaceContainer = Color(0x14FFFFFF),
    surfaceContainerHigh = Color(0x1FFFFFFF),
    surfaceContainerHighest = Color(0x24FFFFFF),

    outline = Color(0x3DFFFFFF),
    outlineVariant = Color(0x24FFFFFF),

    error = Color(0xFFFF8A93),
    onError = Color(0xFF14161B),

    scrim = Color(0xCC05060A),
)

@Composable
fun McrTheme(forceDark: Boolean = true, content: @Composable () -> Unit) {
    // The glass system is built for a dark backdrop; there is no light variant.
    MaterialTheme(colorScheme = VantaScheme, content = content)
}
