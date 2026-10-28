// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Palette shared with RawLens res/values/colors.xml: cozy dark + sea sand.
// Shapes follow its drawables: chips 14 dp (control_chip), bottom panels 16 dp
// (control_panel), dialogs and cards 20 dp (quick_panel).
@Composable
fun StudioTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color(0xFFDDC08D), onPrimary = Color(0xFF241B0C),
        primaryContainer = Color(0xFF30291F), onPrimaryContainer = Color(0xFFDDC08D),
        secondary = Color(0xFFDDC08D), secondaryContainer = Color(0xFF30291F),
        onSecondaryContainer = Color(0xFFDDC08D),
        background = Color(0xFF070809), onBackground = Color(0xFFF7F8F4),
        surface = Color(0xFF121417), onSurface = Color(0xFFF7F8F4),
        surfaceVariant = Color(0xFF20242A), onSurfaceVariant = Color(0xFF9DA4A8),
        outline = Color(0xFF45494D), outlineVariant = Color(0xFF292D31),
        surfaceContainer = Color(0xFF121417), surfaceContainerHigh = Color(0xFF1B1F24),
        surfaceContainerHighest = Color(0xFF20242A), surfaceContainerLow = Color(0xFF0D0F11),
        error = Color(0xFFFF7A7A),
    ), shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(14.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(20.dp),
    ), typography = base.copy(
        // RawLens small labels: bold, slightly spaced.
        labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
        labelMedium = base.labelMedium.copy(letterSpacing = 0.3.sp),
    ), content = content)
}
