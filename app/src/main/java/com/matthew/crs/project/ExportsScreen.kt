// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs.project

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Every export job: running and queued first, then finished ones, newest first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportsScreen() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { ExportQueue.load(context) }
    val active = ExportQueue.jobs.filter { it.status == "Exporting" || it.status == "Queued" }
    val finished = ExportQueue.jobs.filter { it !in active }.asReversed()
    var message by remember { mutableStateOf<String?>(null) }
    // Retry of an MP4 whose partial file was removed: choose where to write it again.
    var retrying by remember { mutableStateOf<String?>(null) }
    val newDestination = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val id = retrying; retrying = null
        if (uri != null && id != null) ExportQueue.retry(context, id, uri.toString())
    }
    Scaffold(topBar = {
        TopAppBar(windowInsets = WindowInsets.displayCutout.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal), title = { Text("Exports") }, actions = {
            if (finished.isNotEmpty()) TextButton(onClick = { ExportQueue.clearFinished(context) }) { Text("Clear finished") }
        })
    }) { pad ->
        if (ExportQueue.jobs.isEmpty()) Column(Modifier.padding(pad).fillMaxSize(), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Movie, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
            Text("No exports yet", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            Text("Export a take or a project; it keeps running when you leave the app.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
        } else LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (active.isNotEmpty()) item { Label("IN PROGRESS") }
            items(active, key = { it.id }) { job -> JobCard(job, onCancel = { ExportQueue.cancel(context, job.id) }) }
            if (finished.isNotEmpty()) item { Label("FINISHED") }
            items(finished, key = { it.id }) { job ->
                JobCard(job,
                    onRetry = {
                        if (ExportQueue.outputExists(context, job)) ExportQueue.retry(context, job.id)
                        else { retrying = job.id; newDestination.launch(job.project.name.replace(Regex("[^a-zA-Z0-9 _-]"), "_") + ".mp4") }
                    },
                    onOpen = {
                        val dng = job.settings.output == ExportOutput.ORIGINAL_CINEMA_DNG
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(job.destination),
                            if (dng) android.provider.DocumentsContract.Document.MIME_TYPE_DIR else "video/mp4")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                            .onFailure { message = if (dng) "No file manager can open the folder" else "No video player is available" }
                    },
                    onShare = {
                        runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4")
                            .putExtra(Intent.EXTRA_STREAM, Uri.parse(job.destination)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share export")) }
                    },
                    onRemove = { ExportQueue.remove(context, job.id) })
            }
        }
    }
    message?.let { AlertDialog(onDismissRequest = { message = null }, text = { Text(it) },
        confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }) }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 10.sp, letterSpacing = 0.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, start = 4.dp))
}

@Composable
private fun JobCard(job: ExportJob, onCancel: (() -> Unit)? = null, onRetry: (() -> Unit)? = null,
    onOpen: (() -> Unit)? = null, onShare: (() -> Unit)? = null, onRemove: (() -> Unit)? = null) {
    val dng = job.settings.output == ExportOutput.ORIGINAL_CINEMA_DNG
    val out = if (dng) null else job.settings.output(job.project)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (dng) Icons.Default.Folder else Icons.Default.Movie, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(job.project.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (dng) "Original CinemaDNG · ${job.project.assets.first().width} × ${job.project.assets.first().height}"
                        else "${job.settings.codec.label} · ${out!!.width} × ${out.height} · ${job.settings.bitrateMbps} Mbps",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusChip(job.status)
            }
            if (job.status == "Exporting") {
                LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant)
                Text("${(job.progress * 100).toInt()} % · ${job.message}", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            } else if (job.message.isNotEmpty()) Text(job.message, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp),
                color = if (job.status == "Failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (onCancel != null) Action("Cancel", Icons.Default.Close, onCancel)
                if (job.status == "Completed") {
                    if (onOpen != null) Action(if (dng) "Open folder" else "Play", if (dng) Icons.Default.FolderOpen else Icons.Default.PlayArrow, onOpen)
                    if (onShare != null && !dng) Action("Share", Icons.Default.Share, onShare)
                }
                if (job.status in listOf("Failed", "Interrupted", "Cancelled") && onRetry != null) Action("Retry", Icons.Default.Refresh, onRetry)
                Spacer(Modifier.weight(1f))
                if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Default.DeleteOutline, "Remove from list", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun StatusChip(status: String) {
    val (fg, bg) = when (status) {
        "Completed" -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primaryContainer
        "Failed", "Interrupted" -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
        "Exporting" -> MaterialTheme.colorScheme.onPrimary to MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceVariant
    }
    Surface(shape = MaterialTheme.shapes.small, color = bg) {
        Text(status.uppercase(), color = fg, fontSize = 9.sp, letterSpacing = 0.5.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun Action(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))) {
        Icon(icon, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(label, fontSize = 12.sp)
    }
}
