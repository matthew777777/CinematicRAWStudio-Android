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
    /** Take Uri delivered by ACTION_VIEW (file managers, adb tests), at launch or later. */
    private val viewRequest = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.data?.let { viewRequest.value = it.toString() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) viewRequest.value = intent?.data?.toString()
        getExternalFilesDir(null)  // app storage for proxies and exports (created on first launch)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFE8A33D), background = Color(0xFF0E0E10), surface = Color(0xFF17171A),
            )) {
                var open by rememberSaveable { mutableStateOf<String?>(null) }
                val requested = viewRequest.value
                androidx.compose.runtime.LaunchedEffect(requested) {
                    if (requested != null) { open = requested; viewRequest.value = null }
                }
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
