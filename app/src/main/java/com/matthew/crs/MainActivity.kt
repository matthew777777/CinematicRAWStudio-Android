// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        com.matthew.crs.project.ExportQueue.load(this)
        setContent {
            StudioTheme {
                var open by rememberSaveable { mutableStateOf<String?>(null) }
                var projects by rememberSaveable { mutableStateOf(false) }
                var projectId by rememberSaveable { mutableStateOf<String?>(null) }
                val requested = viewRequest.value
                androidx.compose.runtime.LaunchedEffect(requested) {
                    if (requested != null) { open = requested; projects = false; projectId = null; viewRequest.value = null }
                }
                val uri = open
                if (projectId != null) {
                    BackHandler { projectId = null }
                    com.matthew.crs.project.SequenceScreen(projectId!!, onBack = { projectId = null })
                } else if (projects) {
                    BackHandler { projects = false }
                    com.matthew.crs.project.ProjectsScreen(onBack = { projects = false }, onOpen = { projectId = it })
                } else if (uri == null) {
                    GalleryScreen(onOpen = { open = it.toString() }, onProjects = { projects = true })
                } else {
                    BackHandler { open = null }
                    PlayerScreen(Uri.parse(uri), onBack = { open = null })
                }
            }
        }
    }
}
