// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** A bundled component and the licence text that ships with it (assets/licenses). */
private data class Component(val name: String, val use: String, val license: String, val file: String)

private val COMPONENTS = listOf(
    Component("RapidRAW", "Vulkan grading engine · github.com/CyberTimon/RapidRAW", "AGPL-3.0", "rapidraw-AGPL-3.0.txt"),
    Component("Cinematic RAW Studio", "This app", "GPL-3.0-or-later", "GPL-3.0.txt"),
    Component("RawLens", "Vulkan demosaic (coherent MHC) and preview pipeline", "GPL-3.0", "GPL-3.0.txt"),
    Component("CinematicRAWStudio CRAW encoder and decoder", ".CRAW reading and decoding", "Apache-2.0", "Apache-2.0.txt"),
    Component("CRAW notices", "Required notices of the CRAW libraries", "Apache-2.0 NOTICE", "CRAW-NOTICE.txt"),
    Component("OpenColorIO ACES 2.0", "Reference SDR 100-nit Rec.709 / sRGB output transform", "BSD-3-Clause", "ocio-aces2.txt"),
    Component("Google Filament AgX", "AgX Base display transform", "Apache-2.0", "Apache-2.0.txt"),
)

/** About, source and the licences of everything bundled. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
    var open by remember { mutableStateOf<Component?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Cinematic RAW Studio", style = MaterialTheme.typography.titleLarge)
                Text("Version $version", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Free software including the AGPL-3.0 RapidRAW engine and GPL-3.0-or-later app components. It comes with no warranty. " +
                    "The component licences and corresponding source must accompany redistribution.",
                    fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
                Text("LICENCES", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
            }
            COMPONENTS.forEach { c ->
                Row(Modifier.fillMaxWidth().clickable { open = c }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, fontSize = 14.sp)
                        Text("${c.license} · ${c.use}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, "Read the licence", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    open?.let { c ->
        val text = remember(c) { runCatching { context.assets.open("licenses/${c.file}").bufferedReader().use { it.readText() } }.getOrDefault("Licence text missing") }
        Dialog(onDismissRequest = { open = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f), shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(16.dp)) {
                    Text(c.name, style = MaterialTheme.typography.titleMedium)
                    Text(c.license, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp).verticalScroll(rememberScrollState()))
                    TextButton(onClick = { open = null }, modifier = Modifier.align(Alignment.End)) { Text("Close") }
                }
            }
        }
    }
}
