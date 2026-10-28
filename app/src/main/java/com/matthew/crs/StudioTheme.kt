// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Palette shared with RawLens res/values/colors.xml: cozy dark + sea sand.
@Composable
fun StudioTheme(content: @Composable () -> Unit) {
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
    ), content = content)
}
