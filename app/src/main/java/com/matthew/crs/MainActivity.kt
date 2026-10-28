// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import com.matthew.crs.project.ExportQueue

/** Top-level places (bottom navigation); the editor and a project open full screen over them. */
enum class Tab(val title: String) { Library("Library"), Projects("Projects"), Exports("Exports") }

class MainActivity : ComponentActivity() {
    companion object {
        /** Intent extra selecting a tab ("exports" from the export notification). */
        const val EXTRA_TAB = "tab"
    }

    /** Take Uri delivered by ACTION_VIEW (file managers, adb tests), at launch or later. */
    private val viewRequest = mutableStateOf<String?>(null)
    private val tabRequest = mutableStateOf<String?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        intent.data?.let { viewRequest.value = it.toString() }
        intent.getStringExtra(EXTRA_TAB)?.let { tabRequest.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            viewRequest.value = intent?.data?.toString()
            tabRequest.value = intent?.getStringExtra(EXTRA_TAB)
        }
        getExternalFilesDir(null)  // app storage for proxies and exports (created on first launch)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        ExportQueue.load(this)
        setContent {
            StudioTheme {
                var tab by rememberSaveable { mutableStateOf(Tab.Library) }
                var open by rememberSaveable { mutableStateOf<String?>(null) }
                var projectId by rememberSaveable { mutableStateOf<String?>(null) }
                val requested = viewRequest.value
                LaunchedEffect(requested) {
                    if (requested != null) { open = requested; projectId = null; viewRequest.value = null }
                }
                LaunchedEffect(tabRequest.value) {
                    if (tabRequest.value == "exports") { tab = Tab.Exports; open = null; projectId = null }
                    tabRequest.value = null
                }
                // Export progress is a notification (Android 13+ asks once, when the first export starts).
                val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
                LaunchedEffect(ExportQueue.running) {
                    if (ExportQueue.running && android.os.Build.VERSION.SDK_INT >= 33 &&
                        checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                        notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                val uri = open
                when {
                    projectId != null -> {
                        BackHandler { projectId = null }
                        com.matthew.crs.project.SequenceScreen(projectId!!, onBack = { projectId = null })
                    }
                    uri != null -> {
                        BackHandler { open = null }
                        PlayerScreen(Uri.parse(uri), onBack = { open = null })
                    }
                    else -> {
                        if (tab != Tab.Library) BackHandler { tab = Tab.Library }
                        Scaffold(bottomBar = { BottomBar(tab) { tab = it } }) { pad ->
                            Box(Modifier.padding(bottom = pad.calculateBottomPadding()).consumeWindowInsets(pad).fillMaxSize()) {
                                when (tab) {
                                    Tab.Library -> GalleryScreen(onOpen = { open = it.toString() })
                                    Tab.Projects -> com.matthew.crs.project.ProjectsScreen(onOpen = { projectId = it })
                                    Tab.Exports -> com.matthew.crs.project.ExportsScreen()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(selected: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = androidx.compose.ui.unit.Dp(0f)) {
        Tab.entries.forEach { t ->
            NavigationBarItem(selected = selected == t, onClick = { onSelect(t) },
                icon = {
                    val icon = when (t) { Tab.Library -> Icons.Default.PhotoLibrary; Tab.Projects -> Icons.Default.VideoLibrary; Tab.Exports -> Icons.Default.FileUpload }
                    // Running and queued exports, as a badge.
                    val active = if (t == Tab.Exports) ExportQueue.jobs.count { it.status == "Exporting" || it.status == "Queued" } else 0
                    if (active > 0) BadgedBox(badge = { Badge { Text("$active") } }) { Icon(icon, null) } else Icon(icon, null)
                },
                label = { Text(t.title, fontSize = 11.sp) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    indicatorColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary))
        }
    }
}
