// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs.project

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
fun ExportPanel(prepare: (suspend () -> SequenceProject)? = null, beforeStart: suspend () -> Unit = {}, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf<SequenceProject?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var queue by remember { mutableStateOf(prepare == null) }
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
                queue = true
            } catch (error: Exception) { failure = error.message ?: "Cannot queue export" }
            finally { submitting = false }
        }
    }
    Dialog(onDismissRequest = { if (!submitting) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f).widthIn(max = 620.dp).fillMaxHeight(0.92f), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (queue) "Export jobs" else "Export video", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (prepare != null) TextButton(onClick = { queue = !queue }) { Text(if (queue) "Settings" else "Jobs (${ExportQueue.jobs.count { it.status == "Queued" || it.status == "Exporting" }})") }
                    IconButton(onClick = onDismiss, enabled = !submitting) { Icon(Icons.Default.Close, "Close export panel") }
                }
                HorizontalDivider()
                if (queue) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        if (ExportQueue.jobs.isEmpty()) Text("No export jobs yet", Modifier.padding(vertical = 24.dp))
                        ExportQueue.jobs.asReversed().forEach { job ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                                Text(job.project.name, style = MaterialTheme.typography.titleSmall)
                                val out = job.settings.output(job.project)
                                Text("${if (job.settings.output == ExportOutput.ORIGINAL_CINEMA_DNG) "Original CinemaDNG · ${job.project.assets.first().width} × ${job.project.assets.first().height}" else "${job.settings.codec.label} · ${out.width} × ${out.height}"} · ${job.status}", fontSize = 11.sp)
                                Text(job.message, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (job.status == "Exporting") LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                                Row {
                                    if (job.status in listOf("Queued", "Exporting")) TextButton(onClick = { ExportQueue.cancel(context, job.id) }) { Text("Cancel") }
                                    if (job.status in listOf("Failed", "Interrupted", "Cancelled")) TextButton(onClick = { ExportQueue.retry(context, job.id) }) { Text("Retry") }
                                    if (job.status == "Completed") TextButton(onClick = {
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(job.destination), if (job.settings.output == ExportOutput.ORIGINAL_CINEMA_DNG) android.provider.DocumentsContract.Document.MIME_TYPE_DIR else "video/mp4")
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { failure = "No video player is available" }
                                    }) { Text(if (job.settings.output == ExportOutput.ORIGINAL_CINEMA_DNG) "Open folder" else "Open video") }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                } else {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val p = project
                        if (p == null && failure == null) { CircularProgressIndicator(Modifier.padding(16.dp)); Text("Preparing edited clip and audio…") }
                        if (p != null) {
                            Text(p.name, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
                            Text("${p.frameCount} frames · %.3f fps · %d audio tracks".format(p.rate.fps, p.tracks.count { it.kind == TrackKind.AUDIO }), fontSize = 11.sp)
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
                            Text("Quality / bitrate", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(12 to "Standard", 24 to "High", 50 to "Maximum").forEach { (rate, label) ->
                                    FilterChip(selected = settings.bitrateMbps == rate, onClick = { settings = settings.copy(bitrateMbps = rate) }, label = { Text(label) })
                                }
                            }
                            Text("${settings.bitrateMbps} Mbps", color = MaterialTheme.colorScheme.primary)
                            Slider(value = settings.bitrateMbps.toFloat(), onValueChange = { settings = settings.copy(bitrateMbps = it.toInt().coerceIn(2, 150)) }, valueRange = 2f..150f)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Include mixed audio", Modifier.weight(1f)); Switch(settings.audio, { settings = settings.copy(audio = it) })
                            }
                            Text("Color · Rec.709 SDR / ${if (settings.codec.tenBit) "10-bit" else "8-bit"}", style = MaterialTheme.typography.labelLarge)
                            Text("Includes all clip edits, timeline cuts, and enabled audio tracks. Output frame rate matches the source/project.", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val result = remember(p, settings) { runCatching { settings.output(p) } }
                            val out = result.getOrNull()
                            val encoder by produceState<String?>(null, p, settings) { value = withContext(Dispatchers.IO) { settings.encoder(p) } }
                            if (out != null) Text("${out.width} × ${out.height} · estimated %.1f MB".format(settings.bitrateMbps * p.durationUs / 8_000_000.0), fontSize = 11.sp)
                            if (encoder == null) Text("These settings are not supported by a device encoder. Choose another codec, resolution, or bitrate.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                            else Text("Encoder · $encoder", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { destination.launch(p.name.replace(Regex("[^a-zA-Z0-9 _-]"), "_") + ".mp4") },
                                enabled = encoder != null && p.clips.isNotEmpty() && !submitting,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Icon(Icons.Default.FileUpload, null); Spacer(Modifier.width(8.dp)); Text("Choose destination & add to queue")
                            }
                        }
                    }
                }
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
    }
}
