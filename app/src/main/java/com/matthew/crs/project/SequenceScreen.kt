// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs.project

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.matthew.crs.*
import kotlinx.coroutines.*
import kotlin.math.roundToLong

@Composable
fun SequenceScreen(id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    var loaded by remember(id) { mutableStateOf<SequenceProject?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id) {
        try { loaded = withContext(Dispatchers.IO) { ProjectStore.load(context, id) } }
        catch (failure: Exception) { error = failure.message ?: "Cannot open project" }
    }
    val initial = loaded
    if (initial == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (error == null) CircularProgressIndicator() else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(error!!); TextButton(onClick = onBack) { Text("Back to projects") }
        }
    } else {
        val session = remember(id) { ProjectSession(context.applicationContext, initial) }
        DisposableEffect(session) { onDispose { session.close() } }
        SequenceEditor(session, onBack)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SequenceEditor(s: ProjectSession, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val window = (context as? android.app.Activity)?.window
    DisposableEffect(landscape, window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (landscape) controller?.hide(WindowInsetsCompat.Type.statusBars()) else controller?.show(WindowInsetsCompat.Type.statusBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.statusBars()) }
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, s) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) s.player.pause() }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(ExportQueue.running) { if (ExportQueue.running) s.player.pause() }
    val p = s.project
    val selected = s.selected
    var inspect by rememberSaveable { mutableStateOf(true) }
    var export by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var title by remember(p.id) { mutableStateOf(p.name) }
    var audioTarget by remember { mutableStateOf(p.tracks.firstOrNull { it.kind == TrackKind.AUDIO && it.name.startsWith("A2") }?.id ?: p.tracks.first { it.kind == TrackKind.AUDIO }.id) }
    var relink by remember { mutableStateOf<SourceAsset?>(null) }
    fun import(uris: List<Uri>, audio: Boolean) {
        if (s.importing || ExportQueue.running) return
        s.importing = true; s.player.pause()
        scope.launch {
            try {
                val assets = withContext(Dispatchers.IO) {
                    uris.map { uri ->
                        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        if (audio) ProjectMedia.importAudio(context, uri) else ProjectMedia.importRaw(context, uri)
                    }
                }
                val receipts = withContext(Dispatchers.IO) { assets.associate { it.id to (ReceiptStore.load(context, it.uri) ?: Receipt()) } }
                s.edit { original -> assets.fold(original) { project, asset -> project.append(asset, if (audio) audioTarget else null, receipt = receipts.getValue(asset.id)) } }
                s.selectedId = s.project.clips.lastOrNull { if (audio) it.trackId == audioTarget else s.project.track(it.trackId).kind == TrackKind.VIDEO }?.id
                inspect = true
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { s.error = failure.message ?: "Cannot import media" }
            finally { s.importing = false }
        }
    }
    val addVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { import(it, false) }
    val addAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { import(it, true) }
    val relinkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val old = relink
        if (uri != null && old != null) {
            s.importing = true
            scope.launch {
                try {
                    runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    val replacement = withContext(Dispatchers.IO) { if (old.kind == AssetKind.CRAW) ProjectMedia.importRaw(context, uri) else ProjectMedia.importAudio(context, uri) }
                    s.edit { project -> project.copy(assets = project.assets.filterNot { it.id == old.id } + replacement,
                        clips = project.clips.map { if (it.assetId == old.id) it.copy(assetId = replacement.id) else it }) }
                } catch (failure: Exception) { s.error = failure.message ?: "Cannot relink source" }
                finally { s.importing = false; relink = null }
            }
        }
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (landscape) 36.dp else 48.dp) {
    Scaffold(topBar = {
        Surface {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).padding(end = 16.dp)
                .height(if (landscape) 36.dp else 48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, enabled = !s.importing) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to projects") }
                Column(Modifier.weight(1f).clickable { rename = true }) {
                    Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    if (!landscape) Text(if (s.saved) "PROJECT · SAVED" else "PROJECT · SAVING…", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { s.undo() }, enabled = s.undo.isNotEmpty() && !ExportQueue.running) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo timeline edit") }
                IconButton(onClick = { s.redo() }, enabled = s.redo.isNotEmpty() && !ExportQueue.running) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo timeline edit") }
                IconButton(onClick = { s.player.pause(); export = true }, enabled = p.clips.isNotEmpty() && !s.importing) { Icon(Icons.Default.FileUpload, "Export sequence") }
                IconToggleButton(inspect, { inspect = it }) { Icon(Icons.Default.Tune, "Toggle clip inspector") }
            }
        }
    }) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            val availableHeight = maxHeight
            val availableWidth = maxWidth
            var timelineDp by remember(landscape) { mutableFloatStateOf(if (landscape) 112f else 190f) }
            val density = LocalDensity.current
            // Landscape tracks may take 65 % of the height; portrait keeps 40 % so
            // the preview (and the inspector, when open) stay usable.
            val maxTimeline = availableHeight * (if (landscape) 0.65f else 0.4f)
            @Composable fun Timeline() {
                Box(Modifier.fillMaxWidth().height(12.dp).background(MaterialTheme.colorScheme.outlineVariant).pointerInput(landscape, availableHeight) {
                    detectVerticalDragGestures { change, delta ->
                        change.consume(); timelineDp = (timelineDp - delta / density.density).coerceIn(92f, maxOf(92f, maxTimeline.value))
                    }
                }, contentAlignment = Alignment.Center) { Box(Modifier.width(40.dp).height(3.dp).background(MaterialTheme.colorScheme.onSurfaceVariant)) }
                SequenceTimeline(s, Modifier.fillMaxWidth().height(minOf(timelineDp.dp, maxTimeline)))
            }
            @Composable fun Monitor(modifier: Modifier) {
                Column(modifier) {
                    Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF070809)), contentAlignment = Alignment.Center) {
                        val bitmap = s.player.frame
                        if (bitmap != null) key(bitmap, s.player.index) { Image(bitmap.asImageBitmap(), "Sequence preview, frame ${s.player.index + 1}", Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit) }
                        if (p.clips.isEmpty()) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.VideoLibrary, null, tint = MaterialTheme.colorScheme.primary)
                            Text("Add takes to start your sequence", Modifier.padding(12.dp))
                        }
                        if (s.importing) Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Spacer(Modifier.width(12.dp)); Text("Importing media…") }
                        }
                        s.player.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.align(Alignment.BottomCenter).background(MaterialTheme.colorScheme.surface).padding(12.dp), fontSize = 12.sp) }
                    }
                    Row(Modifier.fillMaxWidth().height(36.dp).background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(timecode(s.player.index.toInt(), p.rate.fps), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        IconButton(onClick = { s.player.seek(s.player.index - 1) }, enabled = s.player.index > 0, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.SkipPrevious, "Previous sequence frame", Modifier.size(18.dp)) }
                        FilledIconButton(onClick = { if (s.player.playing) s.player.pause() else s.player.play() }, enabled = p.clips.isNotEmpty() && !ExportQueue.running && !s.importing,
                            modifier = Modifier.size(28.dp)) { Icon(if (s.player.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (s.player.playing) "Pause sequence" else "Play sequence") }
                        IconButton(onClick = { s.player.seek(s.player.index + 1) }, enabled = s.player.index < p.frameCount - 1, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.SkipNext, "Next sequence frame", Modifier.size(18.dp)) }
                        Text(timecode((p.frameCount - 1).coerceAtLeast(0).toInt(), p.rate.fps), fontSize = 10.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                        if (landscape) {
                            IconButton(onClick = { library = true }, enabled = !s.importing && !ExportQueue.running, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Add, "Takes", Modifier.size(18.dp)) }
                            IconButton(onClick = { addAudio.launch(arrayOf("audio/*")) }, enabled = !s.importing && !ExportQueue.running, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.AudioFile, "Audio", Modifier.size(18.dp)) }
                            IconButton(onClick = { selected?.let { c -> s.edit { it.split(c.id, p.rate.time(s.player.index)) } } }, enabled = selected != null && !ExportQueue.running, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ContentCut, "Split at playhead", Modifier.size(18.dp)) }
                        }
                    }
                    if (!landscape) Row(Modifier.fillMaxWidth().height(36.dp).background(MaterialTheme.colorScheme.surface), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { library = true }, enabled = !s.importing && !ExportQueue.running) { Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Text("Takes", fontSize = 11.sp) }
                        TextButton(onClick = { addAudio.launch(arrayOf("audio/*")) }, enabled = !s.importing && !ExportQueue.running) { Icon(Icons.Default.AudioFile, null, Modifier.size(16.dp)); Text("Audio", fontSize = 11.sp) }
                        var chooseTrack by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { chooseTrack = true }) { Text(p.track(audioTarget).name.substringBefore(" ·"), fontSize = 11.sp); Icon(Icons.Default.ExpandMore, null, Modifier.size(14.dp)) }
                            DropdownMenu(chooseTrack, { chooseTrack = false }) { p.tracks.filter { it.kind == TrackKind.AUDIO }.forEach { t ->
                                DropdownMenuItem(text = { Text(t.name) }, onClick = { audioTarget = t.id; chooseTrack = false })
                            } }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { selected?.let { c -> s.edit { it.split(c.id, p.rate.time(s.player.index)) } } }, enabled = selected != null && !ExportQueue.running,
                            modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ContentCut, "Split at playhead", Modifier.size(18.dp)) }
                    }
                    if (landscape) Timeline()
                }
            }
            val showInspector = inspect && selected != null
            if (landscape) Row(Modifier.fillMaxSize()) {
                Monitor(Modifier.weight(1f).fillMaxHeight())
                if (showInspector) {
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ClipInspector(s, { relink = it; relinkPicker.launch(arrayOf("*/*")) }, Modifier.width((availableWidth * 0.3f).coerceIn(240.dp, 300.dp)).fillMaxHeight())
                }
            } else Column(Modifier.fillMaxSize()) {
                // Portrait: preview takes what is left; timeline and inspector have
                // bounded heights so neither squeezes the other or the picture away.
                Monitor(Modifier.fillMaxWidth().weight(1f).heightIn(min = 160.dp))
                Timeline()
                if (showInspector) {
                    HorizontalDivider()
                    ClipInspector(s, { relink = it; relinkPicker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().height(availableHeight * 0.3f))
                }
            }
        }
    }
    }
    if (library) LibraryTakesDialog(onDismiss = { library = false }, onPick = { library = false; import(it, false) }, onBrowse = { library = false; addVideo.launch(arrayOf("*/*")) })
    if (export) ExportPanel(prepare = { s.project }, beforeStart = { s.player.pause() }, onDismiss = { export = false })
    s.error?.let { message -> AlertDialog(onDismissRequest = { s.error = null }, title = { Text("Project") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { s.error = null }) { Text("OK") } }) }
    if (rename) AlertDialog(onDismissRequest = { rename = false }, title = { Text("Project name") }, text = { OutlinedTextField(title, { title = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { s.edit { it.copy(name = title.trim()) }; rename = false }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { rename = false }) { Text("Cancel") } })
}

@Composable
private fun ClipInspector(s: ProjectSession, relink: (SourceAsset) -> Unit, modifier: Modifier) {
    val c = s.selected ?: return
    val p = s.project; val asset = p.asset(c.assetId); val audio = p.track(c.trackId).kind == TrackKind.AUDIO
    var grade by remember(c.id) { mutableStateOf(false) }
    var resetGrade by remember(c.id) { mutableStateOf(false) }
    var toolName by rememberSaveable { mutableStateOf(EditTool.Light.name) }
    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { grade = false }, modifier = Modifier.weight(1f)) { Text(if (audio) "Audio clip" else "Clip", color = if (!grade) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!audio) TextButton(onClick = { grade = true }, enabled = !asset.rgb, modifier = Modifier.weight(1f)) { Text("Grade", color = if (grade) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (grade && !audio && !asset.rgb) {
            val tool = EditTool.valueOf(toolName)
            TextButton(onClick = { resetGrade = true }, enabled = !ExportQueue.running, modifier = Modifier.align(Alignment.End)) { Text("Reset all", fontSize = 11.sp) }
            ToolStrip(tool, { toolName = it.name }, compact = true)
            EditorPanel(c.receipt, s.player.asShotKelvin, { receipt -> s.edit { project -> project.copy(clips = project.clips.map { if (it.id == c.id) it.copy(receipt = receipt) else it }) } },
                modifier = Modifier.weight(1f).fillMaxWidth(), tool = tool)
        } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Text(asset.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${p.track(c.trackId).name} · ${if (c.linkId == null) "Unlinked" else "Linked camera audio"}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            var sourceIn by remember(c.id, c.inUs) { mutableStateOf(p.rate.frame(c.inUs).toString()) }
            var sourceOut by remember(c.id, c.inUs, c.durationUs) { mutableStateOf(p.rate.frame(c.inUs + c.durationUs).toString()) }
            var position by remember(c.id, c.startUs) { mutableStateOf(p.rate.frame(c.startUs).toString()) }
            OutlinedTextField(position, { position = it }, label = { Text("Timeline start · frame", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            OutlinedTextField(sourceIn, { sourceIn = it }, label = { Text("Source in · frame", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            OutlinedTextField(sourceOut, { sourceOut = it }, label = { Text("Source out · exclusive", fontSize = 11.sp) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            Text("Frame numbers use the project rate. Linked picture and sound move together.", fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = {
                val inside = sourceIn.toLongOrNull(); val outside = sourceOut.toLongOrNull(); val at = position.toLongOrNull()
                if (inside == null || outside == null || at == null || inside < 0 || outside <= inside || at < 0) s.error = "Enter valid non-negative frame numbers; source out must follow source in."
                else s.edit { it.trim(c.id, p.rate.time(inside), p.rate.time(outside)).move(c.id, p.rate.time(at)) }
            }, enabled = !ExportQueue.running, modifier = Modifier.fillMaxWidth()) { Text("Apply trim & position") }
            if (audio) {
                var gain by remember(c.id, c.gain) { mutableFloatStateOf(c.gain) }
                Text("Clip volume · %.0f%%".format(gain * 100), fontSize = 12.sp)
                Slider(gain, { gain = it }, valueRange = 0f..2f, onValueChangeFinished = {
                    s.edit { project -> project.copy(clips = project.clips.map { if (it.id == c.id) it.copy(gain = gain) else it }) }
                }, enabled = !ExportQueue.running)
                Text("Audio track", fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { p.tracks.filter { it.kind == TrackKind.AUDIO }.forEach { t ->
                    FilterChip(selected = c.trackId == t.id, onClick = { s.edit { project -> project.copy(clips = project.clips.map { if (it.id == c.id) it.copy(trackId = t.id) else it }) } }, enabled = !ExportQueue.running,
                        label = { Text(t.name.substringBefore(" ·"), fontSize = 11.sp) })
                } }
            }
            TextButton(onClick = { relink(asset) }, enabled = !s.importing && !ExportQueue.running) { Text("Relink source") }
            TextButton(onClick = { s.edit { it.remove(c.id) } }, enabled = !ExportQueue.running) { Text("Remove linked clip", color = MaterialTheme.colorScheme.error) }
            if (!audio && asset.rgb) Text("This binned RGB take uses its recorded color; RAW grading is unavailable.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }
    }
    if (resetGrade) AlertDialog(onDismissRequest = { resetGrade = false }, title = { Text("Reset clip adjustments?") },
        text = { Text("Restore this clip’s original settings. You can undo this change.") },
        confirmButton = { TextButton(onClick = { s.edit { project -> project.copy(clips = project.clips.map { if (it.id == c.id) it.copy(receipt = Receipt()) else it }) }; resetGrade = false }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { resetGrade = false }) { Text("Cancel") } })

}

@Composable
internal fun LibraryTakesDialog(onDismiss: () -> Unit, onPick: (List<Uri>) -> Unit, onBrowse: () -> Unit, title: String = "Add RAW takes", action: String = "Add") {
    val context = LocalContext.current
    var takes by remember { mutableStateOf<List<Take>?>(null) }
    val selected = remember { mutableStateListOf<String>() }
    LaunchedEffect(Unit) {
        takes = withContext(Dispatchers.IO) {
            context.getSharedPreferences("gallery", 0).getString("tree", null)?.let { runCatching { listTakes(context, Uri.parse(it)) }.getOrDefault(emptyList()) } ?: emptyList()
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.heightIn(max = 360.dp)) {
            TextButton(onClick = onBrowse) { Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp)); Text("Browse files") }
            val list = takes
            if (list == null) CircularProgressIndicator()
            else if (list.isEmpty()) Text("Choose .craw files with Browse files.")
            else Column(Modifier.verticalScroll(rememberScrollState())) { list.forEach { take ->
                Row(Modifier.fillMaxWidth().clickable { if (!selected.remove(take.uri.toString())) selected.add(take.uri.toString()) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(selected.contains(take.uri.toString()), null)
                    Text(take.name, maxLines = 2, fontSize = 12.sp, modifier = Modifier.weight(1f))
                }
            } }
        }
    }, confirmButton = { TextButton(onClick = { onPick(takes.orEmpty().filter { it.uri.toString() in selected }.map { it.uri }) }, enabled = selected.isNotEmpty()) { Text("$action ${selected.size} takes") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
