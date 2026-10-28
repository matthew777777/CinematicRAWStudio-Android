// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop

/** SMPTE-style HH:MM:SS:FF for frame [index]. */
fun timecode(index: Int, fps: Double): String {
    val f = maxOf(1, Math.round(fps).toInt())
    val s = index / f
    return "%02d:%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60, index % f)
}

private data class PlayerLoad(val engine: PlayerEngine? = null, val failed: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(uri: Uri, onBack: () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    val window = (context as? android.app.Activity)?.window
    DisposableEffect(landscape, window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (landscape) controller?.hide(WindowInsetsCompat.Type.statusBars())
        else controller?.show(WindowInsetsCompat.Type.statusBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.statusBars()) }
    }
    // One owner opens and closes the session, even when navigation cancels loading.
    val load by produceState(PlayerLoad(), uri) {
        withContext(Dispatchers.IO) {
            var clip: CrawClip? = null
            var player: PlayerEngine? = null
            try {
                clip = CrawClip.open(context, uri)
                val c = clip
                if (c == null) { value = PlayerLoad(failed = true); return@withContext }
                ensureActive()
                val gpu = if (c.info.rgb || !GpuPipeline.init(context)) null else
                    runCatching { GpuFrameRenderer(c, c.info.width, c.info.height) }.getOrNull()
                player = PlayerEngine(c, gpu).also { e ->
                    ReceiptStore.load(context, uri.toString())?.let { e.receipt = it }
                    e.fullResPlayback = context.getSharedPreferences("player", 0).getBoolean("fullResPlayback", false)
                }
                ensureActive()
                value = PlayerLoad(player)
                player.show(0)
                awaitCancellation()
            } finally {
                player?.let {
                    ReceiptStore.save(context, uri.toString(), it.receipt)
                    it.release()
                }
                clip?.close()
            }
        }
    }
    val e = load.engine
    val timelineFrames = if (e != null) rememberTimelineFrames(uri) else emptyList()
    var editing by rememberSaveable(uri.toString()) { mutableStateOf(true) }
    var toolName by rememberSaveable { mutableStateOf(EditTool.Light.name) }
    val tool = EditTool.valueOf(toolName)
    var info by remember { mutableStateOf(false) }
    var export by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var presets by remember { mutableStateOf(false) }
    var histogram by remember { mutableStateOf(context.getSharedPreferences("player", 0).getBoolean("histogram", false)) }
    // Full screen: a tap on the picture hides the bars, scrubber and panels.
    var immersive by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { SettingsClipboard.load(context) }
    LaunchedEffect(com.matthew.crs.project.ExportQueue.running) { if (com.matthew.crs.project.ExportQueue.running) withContext(Dispatchers.IO) { e?.pause() } }
    var reset by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val seeks = remember(e) { Channel<Int>(Channel.CONFLATED) }
    LaunchedEffect(e) {
        val player = e ?: return@LaunchedEffect
        for (frame in seeks) withContext(Dispatchers.IO) { player.seek(frame) }
    }
    DisposableEffect(seeks) { onDispose { seeks.close() } }
    val undo = remember(e) { mutableStateListOf<Receipt>() }
    val redo = remember(e) { mutableStateListOf<Receipt>() }
    var lastEdit by remember(e) { mutableLongStateOf(0L) }
    fun change(next: Receipt) {
        val player = e ?: return
        if (next == player.receipt) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastEdit > 500 || undo.isEmpty()) {
            undo.add(player.receipt)
            if (undo.size > 100) undo.removeAt(0)
        }
        lastEdit = now
        redo.clear()
        player.receipt = next
        if (player.playing) scope.launch(Dispatchers.IO) { player.pause() }
    }
    // Save edits regardless of which panel is visible, including the final edit on exit.
    LaunchedEffect(e, e?.receipt, e?.playing) {
        val player = e ?: return@LaunchedEffect
        val receipt = player.receipt
        delay(150)
        withContext(Dispatchers.IO) { ReceiptStore.save(context, uri.toString(), receipt) }
    }
    // Re-render a paused frame while a control moves: back to back, always with
    // the newest receipt (values that went stale during a render are skipped).
    LaunchedEffect(e) {
        val player = e ?: return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { listOf(player.receipt, player.playing, player.compare, player.sectionsOff, player.split) }
            .drop(1)
            .conflate()
            .collect { state ->
                val playing = state[1] as Boolean
                if (!playing && player.frame != null) withContext(Dispatchers.IO) { player.show(player.index) }
            }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, e) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) e?.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (landscape) 36.dp else 48.dp) {
    Scaffold(topBar = { if (!immersive) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(end = if (landscape) 16.dp else 0.dp).height(if (landscape) 36.dp else 48.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.size(if (landscape) 36.dp else 48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to library") }
                Column(Modifier.weight(1f)) {
                    Text(uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".craw") ?: "Take",
                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    if (!landscape) Text("RAW VIDEO  /  DEVELOP", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(modifier = Modifier.size(if (landscape) 36.dp else 48.dp), enabled = undo.isNotEmpty(), onClick = {
                    e?.let { redo.add(it.receipt); it.receipt = undo.removeAt(undo.lastIndex); lastEdit = 0 }
                }) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo adjustment") }
                IconButton(modifier = Modifier.size(if (landscape) 36.dp else 48.dp), enabled = redo.isNotEmpty(), onClick = {
                    e?.let { undo.add(it.receipt); it.receipt = redo.removeAt(redo.lastIndex); lastEdit = 0 }
                }) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo adjustment") }
                IconToggleButton(modifier = Modifier.size(if (landscape) 36.dp else 48.dp), checked = e?.split == true,
                    enabled = e?.canGrade == true, onCheckedChange = { on -> e?.split = on }) {
                    Icon(Icons.Default.Compare, if (e?.split == true) "Close before/after" else "Before/after split",
                        tint = if (e?.split == true) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                }
                IconButton(modifier = Modifier.size(if (landscape) 36.dp else 48.dp), onClick = { export = true }, enabled = e != null) {
                    Icon(Icons.Default.FileUpload, "Export edited clip")
                }
                Box {
                    IconButton(modifier = Modifier.size(if (landscape) 36.dp else 48.dp), onClick = { menu = true }, enabled = e != null) {
                        Icon(Icons.Default.MoreVert, "More editing options")
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(if (histogram) "Hide histogram" else "Show histogram") },
                            leadingIcon = { Icon(Icons.Default.BarChart, null) }, onClick = {
                                menu = false; histogram = !histogram
                                context.getSharedPreferences("player", 0).edit().putBoolean("histogram", histogram).apply()
                            })
                        DropdownMenuItem(text = { Text("Presets") }, leadingIcon = { Icon(Icons.Default.AutoFixHigh, null) },
                            enabled = e?.canGrade == true, onClick = { menu = false; presets = true })
                        DropdownMenuItem(text = { Text("Copy settings") }, leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                            onClick = { menu = false; e?.let { SettingsClipboard.copy(context, it.receipt) } })
                        DropdownMenuItem(text = { Text("Paste settings") }, leadingIcon = { Icon(Icons.Default.ContentPaste, null) },
                            enabled = SettingsClipboard.copied != null && e?.canGrade == true, onClick = {
                                menu = false; e?.let { p -> SettingsClipboard.copied?.let { lastEdit = 0; change(it.onto(p.receipt)) } }
                            })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Clip information") }, leadingIcon = { Icon(Icons.Default.Info, null) },
                            onClick = { menu = false; info = true })
                        DropdownMenuItem(text = { Text("Reset all adjustments") }, leadingIcon = { Icon(Icons.Default.RestartAlt, null) },
                            enabled = e?.canGrade == true, onClick = { menu = false; reset = true })
                    }
                }
                IconToggleButton(modifier = Modifier.size(if (landscape) 36.dp else 48.dp), checked = editing, enabled = e?.canGrade == true, onCheckedChange = {
                    editing = it
                    if (it) scope.launch(Dispatchers.IO) { e?.pause() }
                }) { Icon(Icons.Default.Tune, if (editing) "Hide adjustments" else "Show adjustments") }
            }
        }
    } }) { pad ->
        BoxWithConstraints(Modifier.padding(pad).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .fillMaxSize()) {
            val showEditor = editing && e?.canGrade == true
            val panelWidth = (maxWidth * 0.30f).coerceIn(240.dp, 300.dp)
            @Composable fun Preview(modifier: Modifier) {
                Box(modifier.background(Color(0xFF080A0C)), contentAlignment = Alignment.Center) {
                    val original = e?.originalFrame
                    when {
                        load.failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.BrokenImage, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Cannot open this take", Modifier.padding(12.dp))
                            TextButton(onClick = onBack) { Text("Back to library") }
                        }
                        e == null || e.frame == null -> CircularProgressIndicator(Modifier.size(28.dp))
                        // Split before/after on a paused frame (drag the divider).
                        e.split && original != null && !e.playing -> SplitCompare(original, e.frame!!, Modifier.fillMaxSize().padding(8.dp))
                        else -> e.frame?.let { bmp ->
                            key(bmp, e.index) { Image(bmp.asImageBitmap(), "Video preview, frame ${e.index + 1}",
                                Modifier.fillMaxSize().padding(8.dp)
                                    // Tap: full screen on/off. Press and hold: the original until release.
                                    .pointerInput(e) {
                                        awaitEachGesture {
                                            awaitFirstDown(requireUnconsumed = false)
                                            val up = withTimeoutOrNull(250) { waitForUpOrCancellation() }
                                            if (up != null) immersive = !immersive
                                            else if (!e.playing && e.canGrade) {
                                                e.compare = true
                                                try { waitForUpOrCancellation() } finally { e.compare = false }
                                            }
                                        }
                                    },
                                contentScale = ContentScale.Fit) }
                        }
                    }
                    if (e?.compare == true) Text("Original", fontSize = 11.sp, color = Color.White,
                        modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                    if (!e?.sectionsOff.isNullOrEmpty() && e?.compare != true) Text("Preview without: ${e!!.sectionsOff.joinToString { it.title }}",
                        fontSize = 11.sp, color = Color.White, maxLines = 1,
                        modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                    if (histogram && e?.frame != null) HistogramOverlay(e.frame, Modifier.align(Alignment.TopEnd).padding(12.dp).size(132.dp, 64.dp))
                    if (immersive) IconButton(onClick = { immersive = false }, modifier = Modifier.align(Alignment.TopStart).padding(4.dp)) {
                        Icon(Icons.Default.FullscreenExit, "Leave full screen", tint = Color.White)
                    }
                    if (e != null && !e.canGrade) Text("Adjustments unavailable for this take", fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.TopCenter).padding(8.dp))
                }
            }
            @Composable fun Panel(modifier: Modifier) {
                e?.let { EditorPanel(it.receipt, it.asShotKelvin, ::change, modifier, tool,
                    sectionsOff = it.sectionsOff, onSectionOff = { s, off -> it.sectionsOff = if (off) it.sectionsOff + s else it.sectionsOff - s }) }
            }
            @Composable fun Strip(compact: Boolean) = ToolStrip(tool, { toolName = it.name; lastEdit = 0 }, compact = compact)
            if (immersive) {
                Preview(Modifier.fillMaxSize())
            } else if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Preview(Modifier.weight(1f).fillMaxWidth())
                        e?.let { ClipTimeline(it, timelineFrames, { frame -> seeks.trySend(frame) }, compact = true) }
                    }
                    if (showEditor) {
                        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(Modifier.width(panelWidth).fillMaxHeight().background(MaterialTheme.colorScheme.surface)) {
                            Strip(compact = true)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Panel(Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                }
            } else {
                // Portrait (Lightroom): picture, scrubber, the category's panel, categories at the bottom.
                Column(Modifier.fillMaxSize()) {
                    Preview(Modifier.fillMaxWidth().weight(if (showEditor) 0.95f else 1f))
                    e?.let { ClipTimeline(it, timelineFrames, { frame -> seeks.trySend(frame) }, compact = false) }
                    if (showEditor) {
                        Panel(Modifier.fillMaxWidth().weight(1f))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Strip(compact = false)
                        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                    }
                }
            }
        }
    }
    }
    if (presets && e != null) PresetsSheet(e.receipt, onApply = { lastEdit = 0; change(it); presets = false }, onDismiss = { presets = false })
    if (export && e != null) {
        val receipt = e.receipt
        com.matthew.crs.project.ExportPanel(prepare = {
            e.pause()
            singleTakeProject(context, uri, receipt)
        }, beforeStart = { withContext(Dispatchers.IO) { e.pause() } }, onDismiss = { export = false })
    }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("Reset all adjustments?") },
        text = { Text("Restore this take’s original settings. You can undo this change.") },
        confirmButton = { TextButton(onClick = { lastEdit = 0; change(Receipt()); reset = false }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { reset = false }) { Text("Cancel") } })
    if (info && e != null) AlertDialog(onDismissRequest = { info = false }, title = { Text("Clip information") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val c = e.clipInfo
            Text("${c.width} × ${c.height}  ·  %.3f fps".format(c.fps))
            Text("${c.frames} frames  ·  ${formatDuration(c.durationSeconds)}")
            if (c.camera.isNotBlank()) Text(c.camera)
            if (c.iso > 0) Text("ISO ${c.iso}")
            Text(if (c.hasAudio) "Audio · ${c.audioRate} Hz · ${c.audioChannels} channels" else "No audio in this take")
            if (c.recovered) Text("Recovered take · recording did not finish cleanly", color = MaterialTheme.colorScheme.primary)
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Playback preview", style = MaterialTheme.typography.titleSmall)
            Text("Adjustments are shown on paused frames.", fontSize = 12.sp)
            if (e.renderPath == "GPU RCD") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(false to "Half resolution", true to "Full resolution").forEach { (full, label) ->
                        FilterChip(selected = e.fullResPlayback == full, onClick = {
                            scope.launch(Dispatchers.IO) {
                                val resume = e.playing
                                e.pause(); e.fullResPlayback = full
                                context.getSharedPreferences("player", 0).edit().putBoolean("fullResPlayback", full).apply()
                                if (resume) e.play()
                            }
                        }, label = { Text(label, fontSize = 11.sp) })
                    }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { info = false }) { Text("Done") } })
}
