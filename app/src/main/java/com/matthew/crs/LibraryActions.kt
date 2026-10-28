// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs

import android.content.Context
import android.net.Uri
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matthew.crs.project.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A one-take project at the take's own size and rate, graded with [receipt] (Library and editor export). */
fun singleTakeProject(context: Context, uri: Uri, receipt: Receipt): SequenceProject {
    val asset = ProjectMedia.importRaw(context, uri)
    val project = SequenceProject(name = asset.name.removeSuffix(".craw"), rate = FrameRate.fromFps(asset.fps),
        width = asset.width and 1.inv(), height = asset.height and 1.inv()).append(asset)
    return project.copy(clips = project.clips.map { c ->
        if (project.track(c.trackId).kind == TrackKind.VIDEO) c.copy(receipt = receipt) else c
    }).validated()
}

enum class TakeSort(val title: String) { Newest("Newest first"), Oldest("Oldest first"), Name("Name"), Largest("Largest first"), Longest("Longest first") }
enum class TakeFilter(val title: String) { All("All takes"), Bayer("Bayer RAW"), Rgb("Binned RGB"), Audio("With audio") }

/** Sort and filter choices for the Library grid. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SortFilterSheet(sort: TakeSort, filter: TakeFilter, onSort: (TakeSort) -> Unit, onFilter: (TakeFilter) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("SORT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                TakeSort.entries.forEach { s -> FilterChip(selected = sort == s, onClick = { onSort(s) }, label = { Text(s.title) }) }
            }
            Text("SHOW", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                TakeFilter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { onFilter(f) }, label = { Text(f.title) }) }
            }
        }
    }
}

/** Everything known about one take: file, picture, capture, audio. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TakeInfoSheet(take: Take, info: ClipInfo?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // As-shot white balance as the editor shows it: MLV-App's kelvin/tint for the take's AsShotNeutral.
    val asShot by produceState<Pair<Double, Double>?>(null, take.uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                CrawClip.open(context, take.uri)?.use { clip ->
                    if (clip.info.rgb || clip.info.neutral.size != 3) null
                    else MlvGrade(clip).use { g -> g.findAsShot(clip.info.neutral.map { it.toFloat() }.toFloatArray()) }
                }
            }.getOrNull()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Text(take.name.removeSuffix(".craw"), style = MaterialTheme.typography.titleMedium)
            Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(take.modified)), fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            if (info == null) { Text("Reading take…", color = MaterialTheme.colorScheme.onSurfaceVariant); return@Column }
            Group("PICTURE")
            Row2("Resolution", "${info.width} × ${info.height}${if (info.rgb) " (binned RGB)" else ""}")
            if (info.bitDepth > 0) Row2("Bit depth", "${info.bitDepth}-bit${if (info.rgb) "" else " Bayer"}")
            if (info.codecName.isNotEmpty()) Row2("Codec", "CRAW-${info.codecName}")
            Row2("Frame rate", "%.3f".format(info.fps).trimEnd('0').trimEnd('.') + " fps")
            Row2("Duration", "${formatDuration(info.durationSeconds)} · ${info.frames} frames")
            Group("CAPTURE")
            val camera = listOf(info.make, info.model).filter { it.isNotBlank() && it != "Unknown" }.joinToString(" ")
            Row2("Camera", camera.ifEmpty { info.camera })
            if (info.iso > 0) Row2("ISO", "${info.iso}")
            if (info.exposureSeconds > 0) Row2("Shutter", shutter(info.exposureSeconds))
            asShot?.let { (k, t) -> Row2("White balance", "%.0f K · tint %+.1f (as shot)".format(k, t)) }
            Group("AUDIO")
            Row2("Audio", if (info.hasAudio) "${info.audioRate / 1000.0} kHz · ${info.audioBits}-bit · ${if (info.audioChannels == 1) "mono" else "${info.audioChannels} ch"}" else "None")
            Group("FILE")
            Row2("Size", "%.2f GB".format(take.bytes / 1e9))
            if (info.recovered) Row2("State", "Recovered (recording was interrupted)")
        }
    }
}

private fun shutter(seconds: Double): String =
    if (seconds >= 0.5) "%.1f s".format(seconds) else "1/%d s".format(Math.round(1 / seconds))

@Composable
private fun Group(title: String) {
    Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
}

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
        Text(value, fontSize = 13.sp, fontFamily = if (value.firstOrNull()?.isDigit() == true) FontFamily.Monospace else null)
    }
}

/** Long-press actions on a take. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TakeActionsSheet(take: Take, onDismiss: () -> Unit, onEdit: () -> Unit, onInfo: () -> Unit, onExport: () -> Unit,
    onExportDng: () -> Unit, onAddToProject: () -> Unit, onDelete: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text(take.name.removeSuffix(".craw"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            Action(Icons.Default.Tune, "Edit", onEdit)
            Action(Icons.Default.VideoLibrary, "Add to project", onAddToProject)
            Action(Icons.Default.FileUpload, "Export video", onExport)
            Action(Icons.Default.Folder, "Export CinemaDNG", onExportDng)
            Action(Icons.Default.Info, "Info", onInfo)
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Action(Icons.Default.DeleteOutline, "Delete take", onDelete, danger = true)
        }
    }
}

@Composable
private fun Action(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, danger: Boolean = false) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (danger) color else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp)); Text(label, color = color)
    }
}

/** Adds [take] (with its saved grade) to an existing project or a new one. */
@Composable
fun AddToProjectDialog(take: Take, onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val projects by produceState<List<SequenceProject>?>(null) { value = withContext(Dispatchers.IO) { ProjectStore.list(context) } }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun add(target: SequenceProject?) {
        busy = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val receipt = ReceiptStore.load(context, take.uri.toString()) ?: Receipt()
                    val asset = ProjectMedia.importRaw(context, take.uri)
                    val project = (target ?: SequenceProject(name = take.name.removeSuffix(".craw"), rate = FrameRate.fromFps(asset.fps)))
                        .append(asset, receipt = receipt)
                    ProjectStore.save(context, project)
                    project.id
                }
            }.onSuccess(onAdded).onFailure { error = it.message ?: "Cannot add the take"; busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Add to project") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            when {
                busy -> Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text("Adding…") }
                projects == null -> CircularProgressIndicator(Modifier.size(20.dp))
                else -> {
                    TextButton(onClick = { add(null) }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("New project") }
                    projects!!.forEach { p -> TextButton(onClick = { add(p) }) { Text(p.name) } }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        }
    }, confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
}
