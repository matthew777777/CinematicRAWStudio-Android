// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 256-bin R, G, B counts of [bitmap] (sampled from a 256 px wide copy: display only). */
private fun histogram(bitmap: Bitmap): Array<IntArray>? = runCatching {
    val w = 256; val h = maxOf(1, bitmap.height * w / maxOf(1, bitmap.width))
    val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
    val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
    if (small !== bitmap) small.recycle()
    val out = Array(3) { IntArray(256) }
    for (c in px) { out[0][c shr 16 and 255]++; out[1][c shr 8 and 255]++; out[2][c and 255]++ }
    out
}.getOrNull()

/** RGB histogram of the shown frame, drawn as three translucent areas. */
@Composable
fun HistogramOverlay(bitmap: Bitmap?, modifier: Modifier = Modifier) {
    val bins by produceState<Array<IntArray>?>(null, bitmap) {
        value = bitmap?.let { b -> withContext(Dispatchers.Default) { histogram(b) } }
    }
    Canvas(modifier.background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(6.dp)) {
        val b = bins ?: return@Canvas
        // Clipped ends (0 and 255) are excluded from the scale so they do not flatten the rest.
        val peak = b.maxOf { ch -> (1..254).maxOf { ch[it] } }.coerceAtLeast(1).toFloat()
        val colors = listOf(Color(0xFFFF5A5A), Color(0xFF5AE07A), Color(0xFF5A9BFF))
        for (c in 0..2) {
            val path = Path().apply {
                moveTo(0f, size.height)
                for (i in 0..255) lineTo(i / 255f * size.width, size.height * (1f - (b[c][i] / peak).coerceAtMost(1f)))
                lineTo(size.width, size.height); close()
            }
            drawPath(path, colors[c].copy(alpha = 0.55f), blendMode = BlendMode.Plus)
        }
    }
}

/**
 * Before/after: [original] left of the divider, [graded] right of it. Drag
 * anywhere to move the divider. Both are drawn fitted (letterboxed) in the box.
 */
@Composable
fun SplitCompare(original: Bitmap, graded: Bitmap, modifier: Modifier = Modifier) {
    var fraction by remember { mutableFloatStateOf(0.5f) }
    val accent = androidx.compose.material3.MaterialTheme.colorScheme.primary
    Box(modifier) {
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            detectHorizontalDragGestures { change, dx -> change.consume(); fraction = (fraction + dx / size.width).coerceIn(0f, 1f) }
        }) {
            val scale = minOf(size.width / graded.width, size.height / graded.height)
            val w = graded.width * scale; val h = graded.height * scale
            val left = (size.width - w) / 2; val top = (size.height - h) / 2
            val dst = IntSize(w.toInt(), h.toInt()); val at = IntOffset(left.toInt(), top.toInt())
            val x = left + w * fraction
            clipRect(right = x) { drawImage(original.asImageBitmap(), dstOffset = at, dstSize = dst) }
            clipRect(left = x) { drawImage(graded.asImageBitmap(), dstOffset = at, dstSize = dst) }
            drawLine(Color.White, Offset(x, top), Offset(x, top + h), 2.dp.toPx())
            drawCircle(Color.White, 14.dp.toPx(), Offset(x, size.height / 2))
            drawCircle(accent, 11.dp.toPx(), Offset(x, size.height / 2))
        }
        Label("Before", Modifier.align(Alignment.TopStart))
        Label("After", Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun Label(text: String, modifier: Modifier) {
    Text(text, fontSize = 11.sp, color = Color.White, modifier = modifier.padding(12.dp)
        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
}
