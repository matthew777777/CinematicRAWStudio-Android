// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MLV-App's curve widgets (Curves.cpp, HueVsDiagram.cpp): tap adds a point,
 * drag moves it between its neighbours, double tap removes it (not the end
 * points). The drawn line is MLV-App's own interpolation of the points.
 */
@Composable
fun CurveEditor(r: Receipt, onChange: (Receipt) -> Unit) {
    var hue by remember { mutableStateOf(false) }
    var channel by remember { mutableIntStateOf(0) }
    FlowRow {
        FilterChip(selected = !hue, onClick = { hue = false; channel = 0 }, label = { Text("Gradation", fontSize = 12.sp) })
        Spacer(Modifier.width(6.dp))
        FilterChip(selected = hue, onClick = { hue = true; channel = 0 }, label = { Text("Hue vs", fontSize = 12.sp) })
        TextButton(onClick = {
            onChange(if (hue) r.copy(hueVs = r.hueVs.toMutableList().also { it[channel] = Curve.HUE_IDENTITY })
                     else r.copy(gradation = r.gradation.toMutableList().also { it[channel] = Curve.GRADATION_IDENTITY }))
        }) { Text("Reset curve", fontSize = 12.sp) }
    }
    val names = if (hue) listOf("Hue/Hue", "Hue/Sat", "Hue/Luma", "Luma/Sat") else listOf("Luma", "Red", "Green", "Blue")
    val colors = if (hue) List(4) { MaterialTheme.colorScheme.primary } else listOf(Color.White, Color(0xFFE05050), Color(0xFF50C050), Color(0xFF5080F0))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        names.forEachIndexed { i, n -> FilterChip(selected = channel == i, onClick = { channel = i }, label = { Text(n, fontSize = 11.sp) }) }
    }
    val curve = if (hue) r.hueVs[channel] else r.gradation[channel]
    val kind = if (hue) 1 else 0
    // y mapping: gradation 0..1, hue-vs -1..1 around the middle (as MLV-App draws it).
    fun toY(v: Float, h: Float) = if (hue) h / 2 - v * h / 2 else h - v * h
    fun fromY(py: Float, h: Float) = if (hue) ((h / 2 - py) / (h / 2)).coerceIn(-1f, 1f) else ((h - py) / h).coerceIn(0f, 1f)
    fun update(c: Curve) = onChange(
        if (hue) r.copy(hueVs = r.hueVs.toMutableList().also { it[channel] = c })
        else r.copy(gradation = r.gradation.toMutableList().also { it[channel] = c }))
    val currentCurve by rememberUpdatedState(curve)
    val line = remember(curve, kind) { MlvGrade.interpolate(kind, curve, 200) }
    var dragging by remember { mutableIntStateOf(-1) }
    Box(Modifier.fillMaxWidth().aspectRatio(if (hue) 2f else 1.4f).padding(vertical = 6.dp).background(Color(0xFF202024))) {
        Canvas(Modifier.fillMaxSize()
            .pointerInput(kind, channel) {
                detectTapGestures(
                    onTap = { p ->
                        val c = currentCurve
                        val x = (p.x / size.width).coerceIn(0f, 1f)
                        if (c.xs.indices.any { kotlin.math.abs(c.xs[it] * size.width - p.x) < 24 }) return@detectTapGestures
                        val i = c.xs.indexOfFirst { it > x }.let { if (it < 0) c.xs.size else it }
                        val y = fromY(p.y, size.height.toFloat())
                        update(Curve(c.xs.toMutableList().apply { add(i, x) }, c.ys.toMutableList().apply { add(i, y) }))
                    },
                    onDoubleTap = { p ->
                        val c = currentCurve
                        val i = c.xs.indices.minByOrNull { kotlin.math.abs(c.xs[it] * size.width - p.x) } ?: return@detectTapGestures
                        if (i == 0 || i == c.xs.lastIndex || c.xs.size <= 2) return@detectTapGestures
                        update(Curve(c.xs.toMutableList().apply { removeAt(i) }, c.ys.toMutableList().apply { removeAt(i) }))
                    },
                )
            }
            .pointerInput(kind, channel) {
                detectDragGestures(
                    onDragStart = { p ->
                        val c = currentCurve
                        dragging = c.xs.indices.minByOrNull {
                            val dx = c.xs[it] * size.width - p.x; val dy = toY(c.ys[it], size.height.toFloat()) - p.y
                            dx * dx + dy * dy
                        } ?: -1
                    },
                    onDragEnd = { dragging = -1 },
                    onDrag = { change, _ ->
                        val c = currentCurve
                        val i = dragging
                        if (i < 0 || i >= c.xs.size) return@detectDragGestures
                        val lo = if (i == 0) 0f else c.xs[i - 1] + 0.01f
                        val hi = if (i == c.xs.lastIndex) 1f else c.xs[i + 1] - 0.01f
                        val x = (change.position.x / size.width).coerceIn(lo, maxOf(lo, hi))
                        update(Curve(c.xs.toMutableList().also { it[i] = x },
                            c.ys.toMutableList().also { it[i] = fromY(change.position.y, size.height.toFloat()) }))
                    },
                )
            }) {
            val w = size.width; val h = size.height
            for (k in 1..3) {
                drawLine(Color(0xFF34343A), Offset(w * k / 4, 0f), Offset(w * k / 4, h))
                drawLine(Color(0xFF34343A), Offset(0f, h * k / 4), Offset(w, h * k / 4))
            }
            line?.let { ys ->
                val path = Path()
                ys.forEachIndexed { i, v ->
                    val vy = if (hue) v.coerceIn(-1f, 1f) else v.coerceIn(0.0001f, 1f)
                    val px = i / ys.size.toFloat() * w; val py = toY(vy, h)
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(path, colors[channel], style = Stroke(width = 4f))
            }
            curve.xs.indices.forEach { drawCircle(colors[channel], 12f, Offset(curve.xs[it] * w, toY(curve.ys[it], h))) }
        }
    }
}

/** Per-take receipts (MLV-App keeps one receipt per clip in its session). */
object ReceiptStore {
    private fun file(context: android.content.Context, key: String) =
        java.io.File(java.io.File(context.filesDir, "receipts").apply { mkdirs() }, Integer.toHexString(key.hashCode()) + ".json")

    fun load(context: android.content.Context, key: String): Receipt? =
        runCatching { file(context, key).takeIf { it.exists() }?.readText()?.let(Receipt::fromJson) }.getOrNull()

    fun save(context: android.content.Context, key: String, r: Receipt) {
        runCatching { file(context, key).writeText(r.toJson()) }
    }
}
