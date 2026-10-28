// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.io.File

/**
 * [this] grade applied to a take whose own settings are [take]: everything
 * except the RAW corrections, which fix that take's sensor data.
 */
fun Receipt.onto(take: Receipt): Receipt = copy(rawFixesEnabled = take.rawFixesEnabled, verticalStripes = take.verticalStripes,
    badPixels = take.badPixels, bpsMethod = take.bpsMethod, bpiMethod = take.bpiMethod, chromaSmooth = take.chromaSmooth,
    patternNoise = take.patternNoise)

/** Copied settings, shared by the editor and the Library (kept across restarts). */
object SettingsClipboard {
    private const val PREFS = "settings_clipboard"
    var copied by mutableStateOf<Receipt?>(null)
        private set
    private var loaded = false
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        copied = context.getSharedPreferences(PREFS, 0).getString("receipt", null)?.let { runCatching { Receipt.fromJson(it) }.getOrNull() }
    }
    fun copy(context: Context, receipt: Receipt) {
        copied = receipt
        context.getSharedPreferences(PREFS, 0).edit().putString("receipt", receipt.toJson()).apply()
    }
}

data class Preset(val name: String, val receipt: Receipt)

/** Named settings in app storage: files/presets/<name hash>.json {"name", "receipt"}. */
object PresetStore {
    private fun dir(context: Context) = File(context.filesDir, "presets").apply { mkdirs() }
    private fun file(context: Context, name: String) = File(dir(context), Integer.toHexString(name.trim().lowercase().hashCode()) + ".json")
    fun list(context: Context): List<Preset> = dir(context).listFiles().orEmpty().mapNotNull { f ->
        runCatching { JSONObject(f.readText()).let { Preset(it.getString("name"), Receipt.fromJson(it.getString("receipt"))) } }.getOrNull()
    }.sortedBy { it.name.lowercase() }
    fun save(context: Context, name: String, receipt: Receipt) {
        file(context, name).writeText(JSONObject().put("name", name.trim()).put("receipt", receipt.toJson()).toString())
    }
    fun delete(context: Context, name: String) { file(context, name).delete() }
}

/** Presets: apply one (keeps this take's RAW corrections), save the current settings, delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PresetsSheet(current: Receipt, onApply: (Receipt) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var presets by remember { mutableStateOf(PresetStore.list(context)) }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<Preset?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(bottom = 24.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Presets", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { name = ""; naming = true }) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Save current")
                }
            }
            if (presets.isEmpty()) Text("No presets yet. Save the current settings to reuse them on other takes.", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
            presets.forEach { p ->
                Row(Modifier.fillMaxWidth().clickable { onApply(p.receipt.onto(current)) }.padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoFixHigh, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text(p.name, modifier = Modifier.weight(1f))
                    IconButton(onClick = { deleting = p }) { Icon(Icons.Default.DeleteOutline, "Delete preset ${p.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            Text("Applying a preset keeps this take's RAW corrections.", fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        }
    }
    if (naming) AlertDialog(onDismissRequest = { naming = false }, title = { Text("Save preset") }, text = {
        Column {
            OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") })
            if (presets.any { it.name.equals(name.trim(), ignoreCase = true) }) Text("Replaces the preset with this name", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
        PresetStore.save(context, name, current); presets = PresetStore.list(context); naming = false
    }) { Text("Save") } }, dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } })
    deleting?.let { p ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete “${p.name}”?") },
            confirmButton = { TextButton(onClick = { PresetStore.delete(context, p.name); presets = PresetStore.list(context); deleting = null }) {
                Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}
