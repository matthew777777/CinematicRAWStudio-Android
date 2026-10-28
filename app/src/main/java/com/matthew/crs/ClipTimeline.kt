// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*

/** Keep the sampled source frames alive when the editor changes orientation. */
@Composable
fun rememberTimelineFrames(uri: Uri): List<Bitmap> {
    val context = LocalContext.current
    val thumbnails by produceState<List<Bitmap>>(emptyList(), uri) {
        // A separate reader keeps thumbnail generation away from the playback decoder.
        withContext(Dispatchers.IO) {
            delay(700)
            CrawClip.open(context, uri)?.use { clip ->
                val bitmap = clip.newPreviewBitmap()
                try {
                    val count = minOf(8, clip.info.frames)
                    val samples = ArrayList<Bitmap>()
                    repeat(count) { n ->
                        ensureActive()
                        val index = if (count <= 1) 0 else ((clip.info.frames - 1).toLong() * n / (count - 1)).toInt()
                        if (clip.render(index, bitmap)) {
                            samples += Bitmap.createScaledBitmap(bitmap, 160,
                                (160L * bitmap.height / bitmap.width).toInt().coerceAtLeast(1), true)
                            value = samples.toList()
                        }
                        yield()
                    }
                } finally { bitmap.recycle() }
            }
        }
    }
    return thumbnails
}

@Composable
fun ClipTimeline(e: PlayerEngine, thumbnails: List<Bitmap>, onSeek: (Int) -> Unit, compact: Boolean) {
    val scope = rememberCoroutineScope()
    val maxFrame = (e.clipInfo.frames - 1).coerceAtLeast(0)
    var pending by remember(e) { mutableStateOf<Int?>(null) }
    val displayed = pending ?: e.index
    val currentSeek by rememberUpdatedState(onSeek)
    fun seek(frame: Int) { pending = frame.coerceIn(0, maxFrame); currentSeek(pending!!) }
    LaunchedEffect(e.index) { if (pending == e.index) pending = null }
    val accent = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(if (compact) 32.dp else 44.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(timecode(displayed, e.clipInfo.fps), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                color = accent, modifier = Modifier.weight(1f))
            IconButton(onClick = { seek(displayed - 1) }, enabled = displayed > 0, modifier = Modifier.size(if (compact) 32.dp else 40.dp)) {
                Icon(Icons.Default.SkipPrevious, "Previous frame", Modifier.size(20.dp))
            }
            FilledIconButton(onClick = {
                pending = null
                scope.launch(Dispatchers.IO) { if (e.playing) e.pause() else e.play() }
            }, modifier = Modifier.size(if (compact) 28.dp else 36.dp)) {
                Icon(if (e.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (e.playing) "Pause" else "Play")
            }
            IconButton(onClick = { seek(displayed + 1) }, enabled = displayed < maxFrame, modifier = Modifier.size(if (compact) 32.dp else 40.dp)) {
                Icon(Icons.Default.SkipNext, "Next frame", Modifier.size(20.dp))
            }
            Text(timecode(maxFrame, e.clipInfo.fps), fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                color = secondary, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Box(Modifier.fillMaxWidth().height(if (compact) 28.dp else 60.dp).padding(vertical = if (compact) 2.dp else 4.dp)
            .background(Color(0xFF252B32))
            .semantics {
                contentDescription = "Clip timeline, frame ${displayed + 1} of ${e.clipInfo.frames}"
                progressBarRangeInfo = ProgressBarRangeInfo(displayed.toFloat(), 0f..maxOf(1, maxFrame).toFloat(), maxOf(0, maxFrame - 1))
                setProgress { seek(it.toInt()); true }
            }
            .pointerInput(maxFrame) {
                detectTapGestures { p -> seek((p.x / size.width * maxFrame).toInt()) }
            }
            .pointerInput(maxFrame) {
                detectDragGestures(onDragStart = { p -> seek((p.x / size.width * maxFrame).toInt()) }) { change, _ ->
                    change.consume()
                    seek((change.position.x / size.width * maxFrame).toInt())
                }
            }) {
            Row(Modifier.fillMaxSize()) {
                thumbnails.forEach { bitmap ->
                    Image(bitmap.asImageBitmap(), null, Modifier.weight(1f).fillMaxHeight(), contentScale = ContentScale.Crop)
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                // Ruler marks and a frame-accurate playhead over real source thumbnails.
                for (tick in 0..20) {
                    val x = size.width * tick / 20
                    drawLine(Color.White.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, if (tick % 5 == 0) 9.dp.toPx() else 4.dp.toPx()))
                }
                val x = (size.width * displayed / maxOf(1, maxFrame)).coerceIn(1.dp.toPx(), size.width - 1.dp.toPx())
                drawRect(Color.Black.copy(alpha = 0.23f))
                drawLine(accent, Offset(x, 0f), Offset(x, size.height), 2.dp.toPx())
                drawCircle(accent, 4.dp.toPx(), Offset(x, 4.dp.toPx()))
            }
        }
        if (!compact) Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${displayed + 1} / ${e.clipInfo.frames}", fontSize = 9.sp, color = secondary)
            Spacer(Modifier.weight(1f))
            Text("${e.clipInfo.width} × ${e.clipInfo.height}  ·  %.2f fps".format(e.clipInfo.fps), fontSize = 9.sp, color = secondary)
        }
    }
}
