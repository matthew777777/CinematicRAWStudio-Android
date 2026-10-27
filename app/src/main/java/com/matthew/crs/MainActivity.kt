// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFE8A33D), background = Color(0xFF0E0E10), surface = Color(0xFF17171A),
            )) {
                var open by rememberSaveable { mutableStateOf<String?>(null) }
                val uri = open
                if (uri == null) {
                    GalleryScreen(onOpen = { open = it.toString() })
                } else {
                    BackHandler { open = null }
                    PlayerScreen(Uri.parse(uri), onBack = { open = null })
                }
            }
        }
    }
}
