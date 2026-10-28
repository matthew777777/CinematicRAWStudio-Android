// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs.project

import androidx.compose.foundation.*
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToLong

@Composable
fun SequenceTimeline(session: ProjectSession, modifier: Modifier = Modifier) {
    val p = session.project
    var zoom by remember { mutableFloatStateOf(1f) }
    val horizontal = rememberScrollState()
    val vertical = rememberScrollState()
    val density = LocalDensity.current
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("TIMELINE", fontSize = 10.sp, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { zoom = (zoom / 1.5f).coerceAtLeast(0.5f) }, contentPadding = PaddingValues(4.dp)) { Text("−", fontSize = 18.sp) }
            Text("%.1f×".format(zoom), fontSize = 10.sp)
            TextButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(16f) }, contentPadding = PaddingValues(4.dp)) { Text("+", fontSize = 18.sp) }
            IconButton(onClick = { session.edit { it.copy(tracks = it.tracks + SequenceTrack(name = "A${it.tracks.count { t -> t.kind == TrackKind.AUDIO } + 1} · Audio", kind = TrackKind.AUDIO)) } },
                enabled = !ExportQueue.running, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.PlaylistAdd, "Add audio track", Modifier.size(18.dp)) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val labelWidth = 92.dp
            val usable = (maxWidth - labelWidth).coerceAtLeast(100.dp)
            val duration = maxOf(p.durationUs, 5_000_000L)
            val contentWidth = (usable * zoom).coerceAtLeast(usable)
            val widthPx = with(density) { contentWidth.toPx() }
            val currentProject by rememberUpdatedState(p)
            val usPerPixel = duration.toDouble() / widthPx
            Column(Modifier.fillMaxSize().verticalScroll(vertical)) {
                Row(Modifier.height(22.dp)) {
                    Spacer(Modifier.width(labelWidth))
                    Box(Modifier.weight(1f).horizontalScroll(horizontal)) {
                        Box(Modifier.width(contentWidth)) {
                            repeat(5) { i -> Text(com.matthew.crs.formatDuration(duration / 1e6 * i / 4), fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.offset(x = contentWidth * (i / 4f) - if (i == 4) 32.dp else 0.dp)) }
                        }
                    }
                }
                p.tracks.forEach { track ->
                    Row(Modifier.fillMaxWidth().height(46.dp)) {
                        Row(Modifier.width(labelWidth).fillMaxHeight().padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(track.name.substringBefore(" ·"), fontSize = 11.sp, modifier = Modifier.weight(1f))
                            if (track.kind == TrackKind.AUDIO) {
                                IconToggleButton(track.solo, { session.edit { it.copy(tracks = it.tracks.map { t -> if (t.id == track.id) t.copy(solo = !t.solo) else t }) } },
                                    enabled = !ExportQueue.running, modifier = Modifier.size(24.dp)) { Text("S", fontSize = 10.sp) }
                            }
                            IconToggleButton(track.muted, { session.edit { it.copy(tracks = it.tracks.map { t -> if (t.id == track.id) t.copy(muted = !t.muted) else t }) } },
                                enabled = !ExportQueue.running, modifier = Modifier.size(26.dp)) {
                                Icon(if (track.kind == TrackKind.VIDEO) { if (track.muted) Icons.Default.VisibilityOff else Icons.Default.Visibility }
                                    else { if (track.muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp }, "${if (track.muted) "Unmute" else "Mute"} ${track.name}", Modifier.size(14.dp))
                            }
                        }
                        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(horizontal)) {
                            Box(Modifier.width(contentWidth).fillMaxHeight().padding(vertical = 2.dp).background(Color(0xFF20242A))
                                .semantics {
                                    contentDescription = "${track.name} timeline"
                                    progressBarRangeInfo = ProgressBarRangeInfo(session.player.index.toFloat(), 0f..maxOf(1, p.frameCount - 1).toFloat())
                                    setProgress { session.player.seek(it.toLong()); true }
                                }
                                .pointerInput(duration, widthPx) {
                                    detectTapGestures { point -> session.player.seek(currentProject.rate.frame((point.x * usPerPixel).toLong())) }
                                }) {
                                p.clips.filter { it.trackId == track.id }.forEach { clip ->
                                    val x = contentWidth * (clip.startUs.toFloat() / duration)
                                    val width = (contentWidth * (clip.durationUs.toFloat() / duration)).coerceAtLeast(4.dp)
                                    val selected = clip.id == session.selectedId
                                    var drag by remember(clip.id) { mutableFloatStateOf(0f) }
                                    var edge by remember(clip.id) { mutableIntStateOf(0) }
                                    val currentClip by rememberUpdatedState(clip)
                                    val tint = if (track.kind == TrackKind.VIDEO) Color(0xFF65563D) else Color(0xFF284C49)
                                    Box(Modifier.offset(x = x).width(width).fillMaxHeight()
                                        .background(if (selected) tint.copy(alpha = 1f) else tint.copy(alpha = 0.7f))
                                        .border(if (selected) 2.dp else 1.dp, if (selected) accent else Color(0xFF46504F))
                                        .semantics { contentDescription = "${p.asset(clip.assetId).name} on ${track.name}"; onClick { session.selectedId = clip.id; true } }
                                        .pointerInput(clip.id) { detectTapGestures { session.selectedId = clip.id } }
                                        .pointerInput(clip.id, widthPx, duration, ExportQueue.running) {
                                            if (!ExportQueue.running) detectDragGestures(onDragStart = { point ->
                                                session.selectedId = clip.id; session.player.pause(); drag = 0f
                                                val margin = 10.dp.toPx()
                                                edge = if (size.width > margin * 3 && point.x < margin) -1 else if (size.width > margin * 3 && point.x > size.width - margin) 1 else 0
                                            }, onDragCancel = { drag = 0f }, onDragEnd = {
                                                val c = currentClip
                                                val delta = currentProject.rate.snap(kotlin.math.abs(drag * usPerPixel).roundToLong()) * if (drag < 0) -1 else 1
                                                session.edit { project -> when (edge) {
                                                    -1 -> project.trim(c.id, c.inUs + delta, c.inUs + c.durationUs)
                                                    1 -> project.trim(c.id, c.inUs, c.inUs + c.durationUs + delta)
                                                    else -> project.move(c.id, (c.startUs + delta).coerceAtLeast(0))
                                                } }; drag = 0f
                                            }) { change, amount -> change.consume(); drag += amount.x }
                                        }) {
                                        Text(p.asset(clip.assetId).name.removeSuffix(".craw"), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            fontSize = 10.sp, color = Color.White, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 8.dp))
                                        if (selected) {
                                            Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(16.dp).background(accent))
                                            Box(Modifier.align(Alignment.CenterEnd).width(3.dp).height(16.dp).background(accent))
                                        }
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
