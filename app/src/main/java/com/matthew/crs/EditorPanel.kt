// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.semantics.*
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * MLV-App's edit controls with MLV-App's ranges, defaults and value labels
 * (platform/qt/MainWindow.ui); values are receipt slider units, converted
 * to MLV-App's processing API by [Receipt.apiValues].
 */
enum class EditTool(val title: String, val icon: ImageVector) {
    Light("Light", Icons.Default.LightMode),
    Color("Color", Icons.Default.Palette),
    Curves("Curves", Icons.AutoMirrored.Filled.ShowChart),
    Detail("Detail", Icons.Default.Details),
    Profile("Profile", Icons.Default.Style),
    Raw("RAW", Icons.Default.Memory),
    Lens("Lens", Icons.Default.Camera),
}

@Composable
fun ToolStrip(selected: EditTool, onSelect: (EditTool) -> Unit, modifier: Modifier = Modifier,
    compact: Boolean = false) {
    if (compact) {
        var expanded by remember { mutableStateOf(false) }
        Row(modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onSelect(EditTool.entries[selected.ordinal - 1]) },
                enabled = selected.ordinal > 0, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.ChevronLeft, "Previous adjustment tool")
            }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Choose adjustment tool" },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                    Icon(selected.icon, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(selected.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.ExpandMore, null, Modifier.size(16.dp))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                    modifier = Modifier.heightIn(max = 280.dp)) {
                    EditTool.entries.forEach { tool ->
                        DropdownMenuItem(text = { Text(tool.title) },
                            leadingIcon = { Icon(tool.icon, null, Modifier.size(18.dp)) },
                            trailingIcon = { if (tool == selected) Icon(Icons.Default.Check, null, Modifier.size(18.dp)) },
                            onClick = { expanded = false; onSelect(tool) })
                    }
                }
            }
            IconButton(onClick = { onSelect(EditTool.entries[selected.ordinal + 1]) },
                enabled = selected.ordinal < EditTool.entries.lastIndex, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.ChevronRight, "Next adjustment tool")
            }
        }
    } else {
        Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            EditTool.entries.forEach { tool ->
                FilterChip(selected = selected == tool, onClick = { onSelect(tool) },
                    leadingIcon = { Icon(tool.icon, null, Modifier.size(16.dp)) },
                    label = { Text(tool.title, fontSize = 12.sp) })
            }
        }
    }
}

@Composable
fun EditorPanel(r: Receipt, asShotKelvin: Double?, onChange: (Receipt) -> Unit,
    modifier: Modifier = Modifier, tool: EditTool = EditTool.Light) {
    val scroll = key(tool) { rememberScrollState() }
    Column(modifier.background(MaterialTheme.colorScheme.surface).verticalScroll(scroll)
        .padding(horizontal = 16.dp, vertical = 4.dp)) {
        when (tool) {
            EditTool.Light -> {
                Section("Light & tone")
                Knob("Exposure", r.exposure, -400..400, defaultValue = Receipt().exposure, label = "%.2f".format(r.exposure / 100.0)) { onChange(r.copy(exposure = it)) }
                Knob("Contrast", r.contrast, -100..100, defaultValue = Receipt().contrast) { onChange(r.copy(contrast = it)) }
                Knob("Pivot", r.pivot, 0..100, defaultValue = Receipt().pivot) { onChange(r.copy(pivot = it)) }
                Knob("Clarity", r.clarity, -100..100, defaultValue = Receipt().clarity) { onChange(r.copy(clarity = it)) }
                Knob("Shadows", r.shadows, -100..100, defaultValue = Receipt().shadows) { onChange(r.copy(shadows = it)) }
                Knob("Highlights", r.highlights, -100..100, defaultValue = Receipt().highlights) { onChange(r.copy(highlights = it)) }

            }
            EditTool.Color -> {
                Section("White balance & color")
                val kelvin = if (r.temperature < 0) (asShotKelvin ?: 6000.0).toInt() else r.temperature
                Knob("Temperature", kelvin, 2000..10000, defaultValue = (asShotKelvin ?: 6000.0).toInt(), onReset = { onChange(r.copy(temperature = -1)) }, label = if (r.temperature < 0) "$kelvin K (as shot)" else "$kelvin K") {
                    onChange(r.copy(temperature = it))
                }
                Knob("Tint", r.tint, -100..100, defaultValue = Receipt().tint) { onChange(r.copy(tint = it, temperature = if (r.temperature < 0) kelvin else r.temperature)) }
                Knob("Vibrance", r.vibrance, -100..100, defaultValue = Receipt().vibrance) { onChange(r.copy(vibrance = it)) }
                Knob("Saturation", r.saturation, -100..100, defaultValue = Receipt().saturation) { onChange(r.copy(saturation = it)) }
                Section("Toning")
                Knob("Tone", r.tone, 0..255, defaultValue = Receipt().tone) { onChange(r.copy(tone = it)) }
                Knob("Toning strength", r.toningStrength, 0..100, defaultValue = Receipt().toningStrength) { onChange(r.copy(toningStrength = it)) }

            }
            EditTool.Curves -> {
                Section("Curves")
                CurveEditor(r, onChange)

                Section("Contrast curve")
                Knob("Dark strength", r.ds, 0..100, defaultValue = Receipt().ds) { onChange(r.copy(ds = it)) }
                Knob("Dark range", r.dr, 0..100, defaultValue = Receipt().dr) { onChange(r.copy(dr = it)) }
                Knob("Light strength", r.ls, 0..100, defaultValue = Receipt().ls) { onChange(r.copy(ls = it)) }
                Knob("Light range", r.lr, 0..100, defaultValue = Receipt().lr) { onChange(r.copy(lr = it)) }
                Knob("Lighten", r.lightening, 0..100, defaultValue = Receipt().lightening) { onChange(r.copy(lightening = it)) }

            }
            EditTool.Detail -> {
                Section("Details")
                Knob("Sharpen", r.sharpen, 0..100, defaultValue = Receipt().sharpen) { onChange(r.copy(sharpen = it)) }
                Knob("Sharpen masking", r.shMasking, 0..100, defaultValue = Receipt().shMasking) { onChange(r.copy(shMasking = it)) }
                Toggle("Chroma separation", r.chromaSeparation) { onChange(r.copy(chromaSeparation = it)) }
                Knob("Chroma blur", r.chromaBlur, 0..10, defaultValue = Receipt().chromaBlur) { onChange(r.copy(chromaBlur = it)) }
                Knob("Denoise (median)", r.denoiserStrength, 0..100, defaultValue = Receipt().denoiserStrength) { onChange(r.copy(denoiserStrength = it)) }
                Knob("RBF denoise luma", r.rbfDenoiserLuma, 0..100, defaultValue = Receipt().rbfDenoiserLuma) { onChange(r.copy(rbfDenoiserLuma = it)) }
                Knob("RBF denoise chroma", r.rbfDenoiserChroma, 0..100, defaultValue = Receipt().rbfDenoiserChroma) { onChange(r.copy(rbfDenoiserChroma = it)) }
                Knob("RBF denoise range", r.rbfDenoiserRange, 0..100, defaultValue = Receipt().rbfDenoiserRange) { onChange(r.copy(rbfDenoiserRange = it)) }

            }
            EditTool.Profile -> {
                Section("Profile")
                Choice(listOf(1 to "Standard", 2 to "Tonemapped", 3 to "Film", 4 to "Alexa Log-C", 5 to "Cineon Log", 6 to "Sony S-Log3"), r.profile) {
                    onChange(r.copy(profile = it))
                }
                Knob("Gamma", r.gamma, 1..400, defaultValue = Receipt().gamma, label = "%.2f".format(r.gamma / 100.0)) { onChange(r.copy(gamma = it)) }
                Text("Camera matrix", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Choice(listOf(0 to "Off", 1 to "On", 2 to "Danne"), r.camMatrixUsed) { onChange(r.copy(camMatrixUsed = it)) }
                Toggle("AgX", r.agx) { onChange(r.copy(agx = it)) }
                Toggle("Creative adjustments", r.creativeAdjustments) { onChange(r.copy(creativeAdjustments = it)) }
                Toggle("Highlight reconstruction", r.highlightReconstruction) { onChange(r.copy(highlightReconstruction = it)) }

            }
            EditTool.Raw -> {
                Section("RAW correction")
                Toggle("Raw fixes", r.rawFixesEnabled) { onChange(r.copy(rawFixesEnabled = it)) }
                Text("Vertical stripes", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Choice(listOf(0 to "Off", 1 to "Normal", 2 to "Force"), r.verticalStripes) { onChange(r.copy(verticalStripes = it)) }
                Text("Bad pixels", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Choice(listOf(0 to "Off", 1 to "Auto", 2 to "Force"), r.badPixels) { onChange(r.copy(badPixels = it)) }
                if (r.badPixels != 0) Choice(listOf(0 to "Normal search", 1 to "Aggressive"), r.bpsMethod) { onChange(r.copy(bpsMethod = it)) }
                Text("Chroma smooth", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Choice(listOf(0 to "Off", 1 to "2x2", 2 to "3x3", 3 to "5x5"), r.chromaSmooth) { onChange(r.copy(chromaSmooth = it)) }
                Text("Pattern noise", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Choice(listOf(0 to "Off", 1 to "On"), r.patternNoise) { onChange(r.copy(patternNoise = it)) }

            }
            EditTool.Lens -> {
                Section("Lens")
                Knob("CA desaturate", r.caDesaturate, 0..100, defaultValue = Receipt().caDesaturate) { onChange(r.copy(caDesaturate = it)) }
                Knob("Vignette strength", r.vignetteStrength, -100..100, defaultValue = Receipt().vignetteStrength) { onChange(r.copy(vignetteStrength = it)) }
                Knob("Vignette radius", r.vignetteRadius, 0..100, defaultValue = Receipt().vignetteRadius) { onChange(r.copy(vignetteRadius = it)) }
                Knob("Vignette shape", r.vignetteShape, 0..100, defaultValue = Receipt().vignetteShape) { onChange(r.copy(vignetteShape = it)) }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}

/** Relative 1:1 drags avoid jumps; numeric entry and reset share receipt units. */
@Composable
private fun Knob(name: String, value: Int, range: IntRange, defaultValue: Int = 0,
    label: String = value.toString(), onReset: (() -> Unit)? = null, onValue: (Int) -> Unit) {
    var entry by remember { mutableStateOf(false) }
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onValue)
    val latestReset by rememberUpdatedState(onReset)
    val accent = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.outline
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(label, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                color = accent,
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(4.dp))
                    .clickable(onClickLabel = "Set $name value") { entry = true }
                    .semantics { contentDescription = "Set $name value" }
                    .padding(horizontal = 8.dp, vertical = 6.dp))
        }
        Canvas(Modifier.fillMaxWidth().height(48.dp)
            .semantics {
                contentDescription = "$name, $label"
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), range.first.toFloat()..range.last.toFloat())
                setProgress { latestChange(it.roundToInt().coerceIn(range)); true }
                customActions = listOf(CustomAccessibilityAction("Reset $name") {
                    latestReset?.invoke() ?: latestChange(defaultValue.coerceIn(range)); true
                })
            }
            .pointerInput(range, defaultValue) { detectTapGestures(onDoubleTap = {
                latestReset?.invoke() ?: latestChange(defaultValue.coerceIn(range))
            }) }
            // Horizontal drags only, so vertical swipes scroll the panel. The thumb
            // follows the finger 1:1, relative to where the drag started (no jump).
            .pointerInput(range) {
                var start = 0
                var pixels = 0f
                detectHorizontalDragGestures(onDragStart = { start = latestValue; pixels = 0f }) { change, dx ->
                    change.consume(); pixels += dx
                    val usable = (size.width - 16.dp.toPx()).coerceAtLeast(1f)
                    val next = (start + pixels / usable * (range.last - range.first)).roundToInt().coerceIn(range)
                    if (next != latestValue) latestChange(next)
                }
            }) {
            val left = 8.dp.toPx(); val right = size.width - left; val y = size.height / 2
            val fraction = (value - range.first).toFloat() / (range.last - range.first)
            val x = left + (right - left) * fraction.coerceIn(0f, 1f)
            drawLine(trackColor, Offset(left, y), Offset(right, y), 3.dp.toPx(), StrokeCap.Round)
            drawLine(accent, Offset(left, y), Offset(x, y), 3.dp.toPx(), StrokeCap.Round)
            drawCircle(accent, 6.dp.toPx(), Offset(x, y))
        }
    }
    if (entry) {
        val scale = if (name == "Exposure" || name == "Gamma") 100.0 else 1.0
        fun display(v: Int) = if (scale == 100.0) "%.2f".format(java.util.Locale.US, v / scale) else v.toString()
        val initial = display(value)
        var input by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
        var error by remember { mutableStateOf<String?>(null) }
        fun accept() {
            val parsed = input.text.replace(',', '.').toDoubleOrNull()
            if (parsed == null || !parsed.isFinite() || parsed < range.first / scale || parsed > range.last / scale) {
                error = "Enter a value from ${display(range.first)} to ${display(range.last)}"
            } else { onValue((parsed * scale).roundToInt().coerceIn(range)); entry = false }
        }
        AlertDialog(onDismissRequest = { entry = false }, title = { Text(name) }, text = {
            Column {
                OutlinedTextField(input, { input = it; error = null }, singleLine = true,
                    label = { Text(if (name == "Exposure") "Exposure (EV)" else if (name == "Temperature") "Temperature (K)" else "Value") },
                    isError = error != null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { accept() }))
                Text(error ?: "${display(range.first)} to ${display(range.last)}", fontSize = 12.sp,
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }, confirmButton = { TextButton(onClick = { accept() }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { entry = false }) { Text("Cancel") } })
    }
}

@Composable
private fun Toggle(name: String, value: Boolean, onValue: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onValue)
    }
}

@Composable
private fun Choice(options: List<Pair<Int, String>>, value: Int, onValue: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { (v, label) ->
            FilterChip(selected = value == v, onClick = { onValue(v) }, label = { Text(label, fontSize = 12.sp) })
        }
    }
}
