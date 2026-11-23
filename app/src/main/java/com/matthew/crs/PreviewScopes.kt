// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlin.math.ln1p
import kotlin.math.sqrt

private enum class ScopeMode(val label: String) {
    Off("Off"), Histogram("RGB histogram"), Waveform("Luma waveform")
}

private const val Columns = 160
private const val Levels = 101

private fun scopePixels(frame: Bitmap, mode: ScopeMode): ScopeData {
    val scale = minOf(1.0, Columns.toDouble() / maxOf(frame.width, frame.height))
    val width = (frame.width * scale).toInt().coerceAtLeast(1)
    val height = (frame.height * scale).toInt().coerceAtLeast(1)
    val sample = Bitmap.createScaledBitmap(frame, width, height, false)
    val pixels = IntArray(width * height)
    try {
        sample.getPixels(pixels, 0, width, 0, 0, width, height)
    } finally {
        if (sample !== frame) sample.recycle()
    }
    if (mode == ScopeMode.Histogram) {
        val bins = Array(3) { IntArray(256) }
        for (pixel in pixels) {
            bins[0][pixel ushr 16 and 255]++
            bins[1][pixel ushr 8 and 255]++
            bins[2][pixel and 255]++
        }
        return ScopeData.Histogram(bins)
    }

    // Match RawLens's dark scope plate, level lines and density-based traces.
    val counts = IntArray(width * Levels)
    for ((index, pixel) in pixels.withIndex()) {
        val r = pixel ushr 16 and 255
        val g = pixel ushr 8 and 255
        val b = pixel and 255
        val level = (2126 * r + 7152 * g + 722 * b + 12750) / 25500
        counts[(Levels - 1 - level) * width + index % width]++
    }
    val raster = IntArray(counts.size) { index ->
        val alpha = (255 * sqrt((counts[index] * 5f / height).coerceAtMost(1f))).toInt()
        (alpha shl 24) or 0xBCF9DE
    }
    return ScopeData.Waveform(Bitmap.createBitmap(raster, width, Levels, Bitmap.Config.ARGB_8888).asImageBitmap())
}

private sealed interface ScopeData {
    data class Histogram(val bins: Array<IntArray>) : ScopeData
    data class Waveform(val image: ImageBitmap) : ScopeData
}

/** Place inside a preview Box. Analysis samples only the displayed, post-grade image. */
@Composable
internal fun BoxScope.PreviewScopes(frame: () -> Bitmap?) {
    var mode by rememberSaveable { mutableStateOf(ScopeMode.Off) }
    var menu by remember { mutableStateOf(false) }
    val latestFrame by rememberUpdatedState(frame)
    Box(Modifier.align(Alignment.TopEnd)) {
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Default.Analytics, "Video scopes", tint = if (mode == ScopeMode.Off) Color.White
                else MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            ScopeMode.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { mode = option; menu = false },
                )
            }
        }
    }
    if (mode == ScopeMode.Off) return
    key(mode) {
        val data by produceState<ScopeData?>(null) {
            snapshotFlow { latestFrame() }.conflate().collect { bitmap ->
                value = bitmap?.let {
                    withContext(Dispatchers.Default) {
                        try {
                            scopePixels(it, mode)
                        } catch (_: IllegalStateException) {
                            null
                        }
                    }
                }
                delay(250)
            }
        }
        Surface(
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).width(160.dp),
            shape = MaterialTheme.shapes.small,
            color = Color(0xFF080808),
            contentColor = Color.White,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)),
        ) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                Text(mode.label, style = MaterialTheme.typography.labelSmall)
                Canvas(Modifier.fillMaxWidth().height(55.dp).padding(vertical = 3.dp)) {
                    val plot = data
                    if (mode == ScopeMode.Waveform) {
                        for (quarter in 0..4) {
                            val y = size.height * quarter / 4
                            drawLine(Color.White.copy(alpha = 0.18f), Offset(0f, y), Offset(size.width, y))
                        }
                        if (plot is ScopeData.Waveform && size.width >= 1 && size.height >= 1) {
                            drawImage(plot.image, dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                                filterQuality = FilterQuality.None)
                        }
                    } else if (plot is ScopeData.Histogram) {
                        val peak = ln1p(plot.bins.maxOf { bins -> (1..254).maxOf { bins[it] } }.coerceAtLeast(1).toFloat())
                        val colors = listOf(Color(0xFFFF6B62), Color(0xFF3DDC84), Color(0xFF5AA9FF))
                        for ((channel, bins) in plot.bins.withIndex()) {
                            val trace = Path()
                            for ((i, count) in bins.withIndex()) {
                                val x = size.width * i / 255
                                val y = size.height * (1f - (ln1p(count.toFloat()) / peak).coerceAtMost(1f))
                                if (i == 0) trace.moveTo(x, y) else trace.lineTo(x, y)
                            }
                            val fill = Path().apply {
                                addPath(trace)
                                lineTo(size.width, size.height)
                                lineTo(0f, size.height)
                                close()
                            }
                            drawPath(fill, colors[channel].copy(alpha = 0.28f), blendMode = BlendMode.Plus)
                            drawPath(trace, colors[channel], style = Stroke(1.dp.toPx()))
                        }
                    }
                }
            }
        }
    }
}
