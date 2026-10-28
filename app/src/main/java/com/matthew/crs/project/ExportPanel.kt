// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs.project

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*

@Composable
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
fun ExportPanel(prepare: (suspend () -> SequenceProject)? = null, beforeStart: suspend () -> Unit = {}, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf<SequenceProject?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var queued by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("export", 0) }
    var settings by remember { mutableStateOf(ExportSettings(
        codec = runCatching { ExportVideoCodec.valueOf(prefs.getString("codec", "AVC")!!) }.getOrDefault(ExportVideoCodec.AVC),
        height = prefs.getInt("height", 0), bitrateMbps = prefs.getInt("bitrate", 24), audio = prefs.getBoolean("audio", true))) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { ExportQueue.load(context) }
        try { if (prepare != null) project = withContext(Dispatchers.IO) { prepare() }.validated() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error.message ?: "Cannot prepare export" }
    }
    val destination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val snapshot = project
        if (uri != null && snapshot != null) scope.launch {
            submitting = true; failure = null
            try {
                beforeStart()
                runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                ExportQueue.enqueue(context, snapshot, settings, uri.toString())
                prefs.edit().putString("codec", settings.codec.name).putInt("height", settings.height)
                    .putInt("bitrate", settings.bitrateMbps).putBoolean("audio", settings.audio).apply()
                queued = true
            } catch (error: Exception) { failure = error.message ?: "Cannot queue export" }
            finally { submitting = false }
        }
    }
    if (prepare == null) {
        // Nothing to export from here: the job list lives on the Exports tab.
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    ModalBottomSheet(onDismissRequest = { if (!submitting) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Export video", style = MaterialTheme.typography.titleLarge)
            val p = project
            if (queued) {
                Text("Added to Exports. It keeps running when you leave the app; progress is in the notification and on the Exports tab.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                return@Column
            }
            if (p == null && failure == null) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text("Preparing edited clip and audio…")
            }
            if (p != null) {
                Text("${p.name} · ${p.frameCount} frames · ${"%.3f".format(p.rate.fps).trimEnd('0').trimEnd('.')} fps",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("PRESET", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                val chosen = ExportPreset.entries.firstOrNull { it.settings.copy(audio = settings.audio) == settings }
                ExportPreset.entries.forEach { preset ->
                    val selected = chosen == preset && !custom
                    Surface(onClick = { settings = preset.settings.copy(audio = settings.audio); custom = false }, shape = MaterialTheme.shapes.small,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(preset.title, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                Text(preset.note, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                TextButton(onClick = { custom = !custom }) {
                    Icon(if (custom) Icons.Default.ExpandLess else Icons.Default.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(if (custom) "Hide custom settings" else "Custom settings")
                }
                if (custom || chosen == null) {
                    Text("Codec", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExportVideoCodec.entries.forEach { codec ->
                            FilterChip(selected = settings.codec == codec, onClick = { settings = settings.copy(codec = codec) }, label = { Text(codec.label) })
                        }
                    }
                    Text("Resolution", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0 to "Project / source", 2160 to "2160p", 1080 to "1080p", 720 to "720p", 540 to "540p").forEach { (h, label) ->
                            FilterChip(selected = settings.height == h, onClick = { settings = settings.copy(height = h) }, label = { Text(label) })
                        }
                    }
                    Text("Bitrate · ${settings.bitrateMbps} Mbps", style = MaterialTheme.typography.labelLarge)
                    Slider(value = settings.bitrateMbps.toFloat(), onValueChange = { settings = settings.copy(bitrateMbps = it.toInt().coerceIn(2, 150)) }, valueRange = 2f..150f)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Include mixed audio", Modifier.weight(1f)); Switch(settings.audio, { settings = settings.copy(audio = it) })
                }
                val result = remember(p, settings) { runCatching { settings.output(p) } }
                val out = result.getOrNull()
                val encoder by produceState<String?>(null, p, settings) { value = withContext(Dispatchers.IO) { settings.encoder(p) } }
                if (out != null) Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${out.width} × ${out.height} · ${settings.codec.label} · Rec.709 ${if (settings.codec.tenBit) "10-bit" else "8-bit"}", fontSize = 13.sp)
                        Text("About %s".format(sizeText(settings.bitrateMbps * p.durationUs / 8_000_000.0)) + " · " + ExportSpeed.estimate(context, p), fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        settings.requested(p).let { (rw, rh) ->
                            if (rw != out.width || rh != out.height)
                                Text("$rw × $rh exceeds this device's ${settings.codec.label} encoder; exporting at ${out.width} × ${out.height} (same aspect).",
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                if (encoder == null) Text("These settings are not supported by a device encoder. Choose another codec, resolution, or bitrate.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                Button(onClick = { destination.launch(p.name.replace(Regex("[^a-zA-Z0-9 _-]"), "_") + ".mp4") },
                    enabled = encoder != null && p.clips.isNotEmpty() && !submitting,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(52.dp)) {
                    Icon(Icons.Default.FileUpload, null); Spacer(Modifier.width(8.dp)); Text("Choose destination & export")
                }
                if (encoder != null) Text("Encoder · $encoder", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            failure?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
    }
}

private fun sizeText(mb: Double) = if (mb >= 1000) "%.1f GB".format(mb / 1000) else "%.0f MB".format(mb)

/** Export presets: common deliveries, all from the same MLV-App-graded frames. */
enum class ExportPreset(val title: String, val note: String, val settings: ExportSettings) {
    Uhd10("UHD 10-bit HEVC", "2160p · HEVC Main10 · 100 Mbps · grading master", ExportSettings(ExportVideoCodec.HEVC10, 2160, 100)),
    Source10("Source 10-bit master", "Full source size (fitted to the encoder) · HEVC Main10 · 150 Mbps", ExportSettings(ExportVideoCodec.HEVC10, 0, 150)),
    Share1080("1080p H.264 for sharing", "Plays everywhere · 16 Mbps", ExportSettings(ExportVideoCodec.AVC, 1080, 16)),
    Small1080("1080p HEVC small", "Half the size of H.264 · 10 Mbps", ExportSettings(ExportVideoCodec.HEVC, 1080, 10)),
    Preview720("720p preview", "Quick review copy · H.264 · 6 Mbps", ExportSettings(ExportVideoCodec.AVC, 720, 6)),
}

/**
 * Export time from this device's own measurements: every frame is graded at
 * source resolution, so speed is kept as source pixels per second (the last
 * completed video export) and scaled to the project.
 */
object ExportSpeed {
    private const val PREFS = "export_speed"
    fun sourcePixels(project: SequenceProject): Long =
        project.clips.filter { project.track(it.trackId).kind == TrackKind.VIDEO }.maxOfOrNull { c -> project.asset(c.assetId).let { it.width.toLong() * it.height } } ?: 0L
    fun record(context: Context, project: SequenceProject, seconds: Double) {
        val px = sourcePixels(project) * project.frameCount
        if (px > 0 && seconds > 1) context.getSharedPreferences(PREFS, 0).edit().putFloat("pixelsPerSecond", (px / seconds).toFloat()).apply()
    }
    fun estimate(context: Context, project: SequenceProject): String {
        val pps = context.getSharedPreferences(PREFS, 0).getFloat("pixelsPerSecond", 0f)
        if (pps <= 0f) return "time measured on the first export"
        val seconds = sourcePixels(project) * project.frameCount / pps
        return if (seconds < 90) "about %.0f s".format(seconds) else "about %.0f min".format(seconds / 60)
    }
}
