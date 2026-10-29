// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs.project

import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.roundToLong

/** Peak level per 10 ms of an asset's audio (0..1), computed once per asset. */
private object Waveforms {
    const val BUCKET_US = 10_000L
    private val cache = HashMap<String, FloatArray>()
    @Synchronized fun cached(id: String) = cache[id]
    fun load(context: Context, asset: SourceAsset): FloatArray? = cached(asset.id) ?: runCatching {
        WavPcm(ProjectMedia.ensureAudio(context, asset)).use { wav ->
            val perBucket = maxOf(1, (wav.rate * BUCKET_US / 1_000_000L).toInt())
            val buckets = ((wav.frameCount + perBucket - 1) / perBucket).toInt()
            val peaks = FloatArray(buckets)
            val chunk = perBucket * 400
            var at = 0L
            while (at < wav.frameCount) {
                val n = minOf(chunk.toLong(), wav.frameCount - at).toInt()
                val s = wav.read(at, n)
                for (i in 0 until n) {
                    val v = maxOf(abs(s[i * 2]), abs(s[i * 2 + 1]))
                    val b = ((at + i) / perBucket).toInt()
                    if (v > peaks[b]) peaks[b] = v
                }
                at += n
            }
            peaks
        }
    }.getOrNull()?.also { synchronized(this) { cache[asset.id] = it } }
}

/**
 * Multi-track timeline (DaVinci-style): pinch or the slider to zoom, drag a
 * clip to move it or an edge to trim it (live, snapping to clip edges and
 * the playhead with a haptic tick), waveforms on audio clips, and an action
 * bar for the selected clip.
 */
@Composable
fun SequenceTimeline(session: ProjectSession, modifier: Modifier = Modifier, compact: Boolean = false, onGrade: () -> Unit = {}) {
    val p = session.project
    var zoom by remember { mutableFloatStateOf(1f) }
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary
    val haptics = LocalHapticFeedback.current
    val trackHeight = if (compact) 40.dp else 52.dp
    val selected = session.selectedId?.let { id -> p.clips.firstOrNull { it.id == id } }
    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("TIMELINE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Default.ZoomOut, null, Modifier.padding(start = 10.dp).size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            // Logarithmic zoom 0.5x..16x; pinching the tracks does the same.
            Slider(value = ln(zoom), onValueChange = { zoom = exp(it) }, valueRange = ln(0.5f)..ln(16f),
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp).semantics { contentDescription = "Timeline zoom" })
            Icon(Icons.Default.ZoomIn, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { session.edit { it.copy(tracks = it.tracks + SequenceTrack(name = "A${it.tracks.count { t -> t.kind == TrackKind.AUDIO } + 1} · Audio", kind = TrackKind.AUDIO)) } },
                enabled = !ExportQueue.running, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.PlaylistAdd, "Add audio track", Modifier.size(18.dp)) }
        }
        if (selected != null) ClipActionBar(session, selected, onGrade)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()
            // Two fingers zoom; checked before the scroll and clip gestures see the event.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            val z = event.calculateZoom()
                            if (z != 1f) zoom = (zoom * z).coerceIn(0.5f, 16f)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }) {
            val labelWidth = if (compact) 84.dp else 96.dp
            val usable = (maxWidth - labelWidth).coerceAtLeast(100.dp)
            val duration = maxOf(p.durationUs, 5_000_000L)
            val contentWidth = (usable * zoom).coerceAtLeast(usable)
            val widthPx = with(density) { contentWidth.toPx() }
            val currentProject by rememberUpdatedState(p)
            val usPerPixel = duration.toDouble() / widthPx
            val snapUs = with(density) { 12.dp.toPx() } * usPerPixel
            Column(Modifier.fillMaxSize().verticalScroll(vertical)) {
                Row(Modifier.height(22.dp)) {
                    Spacer(Modifier.width(labelWidth))
                    Box(Modifier.weight(1f).horizontalScroll(horizontal)) {
                        Box(Modifier.width(contentWidth)) {
                            val ticks = (4 * zoom).toInt().coerceIn(4, 40)
                            repeat(ticks + 1) { i -> Text(com.matthew.crs.formatDuration(duration / 1e6 * i / ticks), fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.offset(x = contentWidth * (i / ticks.toFloat()) - if (i == ticks) 32.dp else 0.dp)) }
                        }
                    }
                }
                p.tracks.forEach { track ->
                    Row(Modifier.fillMaxWidth().height(trackHeight)) {
                        TrackHeader(session, track, Modifier.width(labelWidth).fillMaxHeight())
                        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(horizontal)) {
                            Box(Modifier.width(contentWidth).fillMaxHeight().padding(vertical = 2.dp).background(Color(0xFF20242A))
                                .semantics {
                                    contentDescription = "${track.name} timeline"
                                    progressBarRangeInfo = ProgressBarRangeInfo(session.player.index.toFloat(), 0f..maxOf(1, p.frameCount - 1).toFloat())
                                    setProgress { session.player.seek(it.toLong()); true }
                                }
                                .pointerInput(duration, widthPx) {
                                    detectTapGestures { point -> session.selectedId = null; session.player.seek(currentProject.rate.frame((point.x * usPerPixel).toLong())) }
                                }) {
                                p.clips.filter { it.trackId == track.id }.forEach { clip ->
                                    ClipBox(session, clip, track, contentWidth, duration, usPerPixel, snapUs, accent) {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                                val head = contentWidth * (p.rate.time(session.player.index).toFloat() / duration)
                                Box(Modifier.offset(x = head).width(2.dp).fillMaxHeight().background(accent))
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun TrackHeader(session: ProjectSession, track: SequenceTrack, modifier: Modifier) {
    fun toggle(change: (SequenceTrack) -> SequenceTrack) =
        session.edit { it.copy(tracks = it.tracks.map { t -> if (t.id == track.id) change(t) else t }) }
    Row(modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(track.name.substringBefore(" ·"), fontSize = 11.sp, modifier = Modifier.weight(1f))
        if (track.kind == TrackKind.AUDIO) {
            // Solo: only soloed audio tracks are heard.
            Box(Modifier.size(26.dp).background(if (track.solo) Color(0xFFE0C030) else Color.Transparent, MaterialTheme.shapes.extraSmall)
                .clickable(enabled = !ExportQueue.running, onClickLabel = "${if (track.solo) "Unsolo" else "Solo"} ${track.name}") { toggle { it.copy(solo = !it.solo) } },
                contentAlignment = Alignment.Center) { Text("S", fontSize = 11.sp, color = if (track.solo) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        IconToggleButton(track.muted, { toggle { it.copy(muted = !it.muted) } }, enabled = !ExportQueue.running, modifier = Modifier.size(28.dp)) {
            Icon(if (track.kind == TrackKind.VIDEO) { if (track.muted) Icons.Default.VisibilityOff else Icons.Default.Visibility }
                else { if (track.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp }, "${if (track.muted) "Unmute" else "Mute"} ${track.name}",
                Modifier.size(15.dp), tint = if (track.muted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * One clip: tap selects; drag the body to move, an edge to trim. The clip
 * follows the finger; positions snap to other clips' edges and the playhead
 * within 12 dp ([onSnap] fires when a snap engages).
 */
@Composable
private fun ClipBox(session: ProjectSession, clip: SequenceClip, track: SequenceTrack, contentWidth: androidx.compose.ui.unit.Dp,
    duration: Long, usPerPixel: Double, snapUs: Double, accent: Color, onSnap: () -> Unit) {
    val p = session.project
    val context = LocalContext.current
    val selected = clip.id == session.selectedId
    var dragUs by remember(clip.id) { mutableLongStateOf(0L) }  // snapped delta while dragging
    var raw by remember(clip.id) { mutableFloatStateOf(0f) }     // finger delta in px
    var edge by remember(clip.id) { mutableIntStateOf(0) }
    var snapped by remember(clip.id) { mutableStateOf(false) }
    val currentClip by rememberUpdatedState(clip)
    val currentProject by rememberUpdatedState(p)
    val asset = p.asset(clip.assetId)
    // Live geometry while dragging: move shifts both ends, a trim moves one.
    val shownStart = clip.startUs + if (edge != 1) dragUs else 0L
    val shownEnd = clip.endUs + if (edge != -1) dragUs else 0L
    val x = contentWidth * (shownStart.toFloat() / duration)
    val width = (contentWidth * ((shownEnd - shownStart).toFloat() / duration)).coerceAtLeast(4.dp)
    val tint = if (track.kind == TrackKind.VIDEO) Color(0xFF65563D) else Color(0xFF284C49)
    val peaks by produceState(if (track.kind == TrackKind.AUDIO) Waveforms.cached(asset.id) else null, asset.id, track.kind) {
        if (track.kind == TrackKind.AUDIO && value == null) value = withContext(Dispatchers.IO) { Waveforms.load(context, asset) }
    }
    /** Edge positions the dragged edge(s) may snap to: other clips (not linked ones) and the playhead. */
    fun targets(): List<Long> {
        val linked = currentProject.linked(currentClip.id).map { it.id }.toSet()
        return currentProject.clips.filter { it.id !in linked }.flatMap { listOf(it.startUs, it.endUs) } +
            currentProject.rate.time(session.player.index) + 0L
    }
    fun snap(deltaUs: Long): Long {
        val c = currentClip
        val edges = when (edge) { -1 -> listOf(c.startUs); 1 -> listOf(c.endUs); else -> listOf(c.startUs, c.endUs) }
        var best: Long? = null
        for (t in targets()) for (e in edges) {
            val d = t - e
            if (abs(d - deltaUs) <= snapUs && (best == null || abs(d - deltaUs) < abs(best - deltaUs))) best = d
        }
        val now = best != null
        if (now && !snapped) onSnap()
        snapped = now
        return best ?: deltaUs
    }
    Box(Modifier.offset(x = x).width(width).fillMaxHeight()
        .background(if (selected) tint else tint.copy(alpha = 0.7f), MaterialTheme.shapes.extraSmall)
        .border(if (selected) 2.dp else 1.dp, if (selected) accent else Color(0xFF46504F), MaterialTheme.shapes.extraSmall)
        .semantics { contentDescription = "${asset.name} on ${track.name}"; onClick { session.selectedId = clip.id; true } }
        .pointerInput(clip.id) { detectTapGestures { session.selectedId = clip.id } }
        .pointerInput(clip.id, usPerPixel, ExportQueue.running) {
            if (!ExportQueue.running) detectHorizontalDragGestures(onDragStart = { point ->
                session.selectedId = clip.id; session.player.pause(); raw = 0f; dragUs = 0L; snapped = false
                val margin = 14.dp.toPx()
                edge = if (size.width > margin * 3 && point.x < margin) -1 else if (size.width > margin * 3 && point.x > size.width - margin) 1 else 0
            }, onDragCancel = { raw = 0f; dragUs = 0L; edge = 0 }, onDragEnd = {
                val c = currentClip
                val delta = currentProject.rate.snap(abs(dragUs)) * if (dragUs < 0) -1 else 1
                runCatching {
                    session.edit { project -> when (edge) {
                        -1 -> project.trim(c.id, c.inUs + delta, c.inUs + c.durationUs)
                        1 -> project.trim(c.id, c.inUs, c.inUs + c.durationUs + delta)
                        else -> project.move(c.id, (c.startUs + delta).coerceAtLeast(0))
                    } }
                }
                raw = 0f; dragUs = 0L; edge = 0; snapped = false
            }) { change, amount -> change.consume(); raw += amount; dragUs = snap((raw * usPerPixel).roundToLong()) }
        }) {
        val wave = peaks
        if (wave != null) Canvas(Modifier.fillMaxSize().padding(vertical = 4.dp)) {
            val step = 2.dp.toPx()
            val mid = size.height / 2
            val sourceStart = clip.inUs + (if (edge == -1) dragUs else 0L)
            var px = 0f
            while (px < size.width) {
                val b = ((sourceStart + (px * usPerPixel).toLong()) / Waveforms.BUCKET_US).toInt()
                val v = if (b in wave.indices) wave[b] else 0f
                drawLine(Color(0xFF7FD3C8), Offset(px, mid - v * mid), Offset(px, mid + v * mid), 1.dp.toPx())
                px += step
            }
        }
        Text(asset.name.removeSuffix(".craw"), maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontSize = 10.sp, color = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(horizontal = 8.dp, vertical = 2.dp))
        if (selected) {
            Box(Modifier.align(Alignment.CenterStart).width(4.dp).fillMaxHeight(0.6f).background(accent))
            Box(Modifier.align(Alignment.CenterEnd).width(4.dp).fillMaxHeight(0.6f).background(accent))
        }
    }
}

/** Actions for the selected clip: split, trim to the playhead, duplicate, delete, grade. */
@Composable
private fun ClipActionBar(session: ProjectSession, clip: SequenceClip, onGrade: () -> Unit) {
    val p = session.project
    val playhead = p.rate.time(session.player.index)
    val inside = playhead > clip.startUs && playhead < clip.endUs
    val enabled = !ExportQueue.running
    var error by remember { mutableStateOf<String?>(null) }
    fun act(change: (SequenceProject) -> SequenceProject) {
        runCatching { session.edit(change) }.onFailure { error = it.message ?: "Cannot change this clip" }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        // Grade first: it is the most used action and must not be scrolled off-screen.
        if (p.track(clip.trackId).kind == TrackKind.VIDEO && !p.asset(clip.assetId).rgb) Act(Icons.Default.Tune, "Grade", true, onGrade)
        Act(Icons.Default.ContentCut, "Split", enabled && inside) { act { it.split(clip.id, playhead) } }
        // Trim the start or end of the clip to the playhead (linked audio follows).
        Act(Icons.Default.FirstPage, "Trim start", enabled && inside) { act { it.trim(clip.id, clip.inUs + (playhead - clip.startUs), clip.inUs + clip.durationUs) } }
        Act(Icons.Default.LastPage, "Trim end", enabled && inside) { act { it.trim(clip.id, clip.inUs, clip.inUs + (playhead - clip.startUs)) } }
        Act(Icons.Default.ContentCopy, "Duplicate", enabled) {
            // The copy (and its linked audio) goes after the last clip of the clip's track.
            act { project ->
                val end = project.clips.filter { it.trackId == clip.trackId }.maxOf { it.endUs }
                val delta = end - clip.startUs
                val group = newId()
                val copies = project.linked(clip.id).map { c ->
                    c.copy(id = newId(), startUs = c.startUs + delta, linkId = if (c.linkId != null) group else null)
                }
                val blocked = copies.any { c -> project.clips.any { it.trackId == c.trackId && it.startUs < c.endUs && it.endUs > c.startUs } }
                require(!blocked) { "No room after the last clip on a linked track" }
                project.copy(clips = project.clips + copies).validated()
            }
        }
        Act(Icons.Default.DeleteOutline, "Delete", enabled) { act { it.remove(clip.id) }; session.selectedId = null }
    }
    error?.let { msg -> AlertDialog(onDismissRequest = { error = null }, text = { Text(msg) },
        confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } }) }
}

@Composable
private fun Act(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
        Icon(icon, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(label, fontSize = 12.sp)
    }
}
