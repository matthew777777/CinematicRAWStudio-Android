// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** SMPTE-style HH:MM:SS:FF for frame [index]. */
fun timecode(index: Int, fps: Double): String {
    val f = maxOf(1, Math.round(fps).toInt())
    val s = index / f
    return "%02d:%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60, index % f)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(uri: Uri, onBack: () -> Unit) {
    val context = LocalContext.current
    var clip by remember { mutableStateOf<CrawClip?>(null) }
    var failed by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(uri) {
        val c = withContext(Dispatchers.IO) { CrawClip.open(context, uri) }
        if (c == null) failed = true else clip = c
    }
    var engine by remember { mutableStateOf<PlayerEngine?>(null) }
    LaunchedEffect(clip) {
        val c = clip ?: return@LaunchedEffect
        // Bayer takes render through RawLens's Vulkan RCD pipeline; RGB
        // (binned) takes and GPU failures use the CPU developer.
        val gpu = withContext(Dispatchers.IO) {
            if (c.info.rgb || !GpuPipeline.init(context)) null
            else runCatching { GpuFrameRenderer(c, c.info.width, c.info.height) }
                .onFailure { android.util.Log.w("PlayerScreen", "GPU renderer unavailable", it) }.getOrNull()
        }
        engine = PlayerEngine(c, gpu).also { e -> ReceiptStore.load(context, uri.toString())?.let { e.receipt = it } }
    }
    // Release exactly what this effect saw: reading the state inside onDispose
    // would close the clip that just replaced it.
    DisposableEffect(engine) {
        val e = engine
        val c = clip
        onDispose { e?.release(); if (e != null) c?.close() }
    }
    DisposableEffect(Unit) { onDispose { clip?.close() } }
    LaunchedEffect(engine) { engine?.let { withContext(Dispatchers.IO) { it.show(0) } } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".craw") ?: "Take", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (engine?.canGrade == true) TextButton(onClick = { editing = !editing; if (editing) engine?.pause() }) {
                        Text(if (editing) "Done" else "Edit")
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Box(Modifier.fillMaxWidth().weight(if (editing) 0.8f else 1f).background(Color.Black), contentAlignment = Alignment.Center) {
                val e = engine
                when {
                    failed -> Text("Cannot open this take", color = Color.Gray)
                    e == null -> CircularProgressIndicator()
                    else -> e.frame?.let { bmp ->
                        // Recomposes on each new frame: the engine alternates two bitmaps.
                        key(e.index) { Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    }
                }
            }
            engine?.let { e ->
                if (editing) {
                    // Re-render the paused frame through MLV-App after edits settle.
                    LaunchedEffect(e.receipt) {
                        kotlinx.coroutines.delay(120)
                        withContext(Dispatchers.IO) {
                            ReceiptStore.save(context, uri.toString(), e.receipt)
                            e.show(e.index)
                        }
                    }
                    EditorPanel(e.receipt, e.asShotKelvin, onChange = { e.receipt = it }, modifier = Modifier.fillMaxWidth().weight(1f))
                } else {
                    Transport(e)
                }
            }
        }
    }
}

@Composable
private fun Transport(e: PlayerEngine) {
    val i = e.index
    val clipInfo = e.clipInfo
    var scrub by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(timecode(i, clipInfo.fps), fontFamily = FontFamily.Monospace, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text(
                "${clipInfo.width}×${clipInfo.height}${if (clipInfo.rgb) " RGB" else ""} · %.2f fps".format(clipInfo.fps) +
                    (if (clipInfo.iso > 0) " · ISO ${clipInfo.iso}" else "") +
                    " · ${e.renderPath}" + (if (e.playing) " · %.0f fps".format(e.shownFps) else ""),
                fontSize = 11.sp, color = Color.Gray,
            )
        }
        Slider(
            value = scrub ?: i.toFloat(),
            onValueChange = { scrub = it; e.seek(it.toInt()) },
            onValueChangeFinished = { scrub = null },
            valueRange = 0f..maxOf(1, clipInfo.frames - 1).toFloat(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { e.seek(i - 1) }) { Icon(Icons.Default.SkipPrevious, "Previous frame") }
            FilledIconButton(onClick = { if (e.playing) e.pause() else e.play() }, modifier = Modifier.size(56.dp)) {
                Icon(if (e.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (e.playing) "Pause" else "Play")
            }
            IconButton(onClick = { e.seek(i + 1) }) { Icon(Icons.Default.SkipNext, "Next frame") }
        }
        if (e.renderPath == "GPU RCD") {
            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("player", android.content.Context.MODE_PRIVATE) }
            LaunchedEffect(e) { e.fullResPlayback = prefs.getBoolean("fullResPlayback", false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Playback", fontSize = 12.sp, modifier = Modifier.width(120.dp))
                listOf(false to "½ res", true to "Full").forEach { (full, label) ->
                    FilterChip(
                        selected = e.fullResPlayback == full,
                        onClick = {
                            if (e.fullResPlayback != full) {
                                val wasPlaying = e.playing
                                if (wasPlaying) e.pause()
                                e.fullResPlayback = full
                                prefs.edit().putBoolean("fullResPlayback", full).apply()
                                if (wasPlaying) e.play()
                            }
                        },
                        label = { Text(label) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
        }
        if (clipInfo.recovered) Text("Recovered take (recording did not finish cleanly)", fontSize = 11.sp, color = Color(0xFFE8A33D))
        if (!clipInfo.hasAudio) Text("No audio in this take", fontSize = 11.sp, color = Color.Gray)
    }
}
