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
import androidx.compose.ui.draw.clip
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
    Effects("Effects", Icons.Default.AutoAwesome),
    Optics("Optics", Icons.Default.Camera),
    Profile("Profile", Icons.Default.Style),
}

/**
 * A panel section: its controls' receipt fields and how to put them back to
 * MLV-App's defaults (section reset, and "preview without" in [withSectionsOff]).
 */
enum class EditSection(val title: String, val reset: (Receipt) -> Receipt) {
    Tone("Light & tone", { r -> val d = Receipt(); r.copy(exposure = d.exposure, contrast = d.contrast, pivot = d.pivot,
        clarity = d.clarity, shadows = d.shadows, highlights = d.highlights) }),
    WhiteBalance("White balance", { r -> r.copy(temperature = -1, tint = Receipt().tint) }),
    Saturation("Vibrance & saturation", { r -> r.copy(vibrance = Receipt().vibrance, saturation = Receipt().saturation) }),
    Curves("Curves", { r -> r.copy(gradation = Receipt().gradation, hueVs = Receipt().hueVs) }),
    ContrastCurve("Contrast curve", { r -> val d = Receipt(); r.copy(ds = d.ds, dr = d.dr, ls = d.ls, lr = d.lr, lightening = d.lightening) }),
    Sharpen("Sharpening", { r -> r.copy(sharpen = Receipt().sharpen, shMasking = Receipt().shMasking) }),
    Noise("Noise reduction", { r -> val d = Receipt(); r.copy(chromaSeparation = d.chromaSeparation, chromaBlur = d.chromaBlur,
        denoiserStrength = d.denoiserStrength, denoiserWindow = d.denoiserWindow, rbfDenoiserLuma = d.rbfDenoiserLuma,
        rbfDenoiserChroma = d.rbfDenoiserChroma, rbfDenoiserRange = d.rbfDenoiserRange) }),
    Vignette("Vignette", { r -> val d = Receipt(); r.copy(vignetteStrength = d.vignetteStrength, vignetteRadius = d.vignetteRadius, vignetteShape = d.vignetteShape) }),
    Toning("Toning", { r -> r.copy(tone = Receipt().tone, toningStrength = Receipt().toningStrength) }),
    Lens("Chromatic aberration", { r -> r.copy(caDesaturate = Receipt().caDesaturate) }),
    Raw("RAW correction", { r -> val d = Receipt(); r.copy(rawFixesEnabled = d.rawFixesEnabled, verticalStripes = d.verticalStripes,
        badPixels = d.badPixels, bpsMethod = d.bpsMethod, bpiMethod = d.bpiMethod, chromaSmooth = d.chromaSmooth, patternNoise = d.patternNoise) }),
    Profile("Profile", { r -> val d = Receipt(); r.copy(profile = d.profile, gamma = d.gamma, camMatrixUsed = d.camMatrixUsed, agx = d.agx,
        creativeAdjustments = d.creativeAdjustments, highlightReconstruction = d.highlightReconstruction, exrMode = d.exrMode) }),
}

/** [this] as previewed with [off] sections switched off (their fields at MLV-App's defaults). */
fun Receipt.withSectionsOff(off: Set<EditSection>): Receipt = off.fold(this) { r, s -> s.reset(r) }

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
    modifier: Modifier = Modifier, tool: EditTool = EditTool.Light,
    sectionsOff: Set<EditSection> = emptySet(), onSectionOff: (EditSection, Boolean) -> Unit = { _, _ -> }) {
    val scroll = key(tool) { rememberScrollState() }
    val collapsed = remember { mutableStateMapOf<EditSection, Boolean>() }
    // A section's header, then its controls unless collapsed.
    @Composable fun Section(section: EditSection, canHide: Boolean = true, content: @Composable () -> Unit) {
        val changed = section.reset(r) != r
        SectionHeader(section.title, changed = changed, expanded = collapsed[section] != true,
            off = section in sectionsOff, canHide = canHide && changed,
            onExpand = { collapsed[section] = it.not() },
            onReset = { onChange(section.reset(r)) },
            onOff = { onSectionOff(section, it) })
        if (collapsed[section] != true) content()
    }
    Column(modifier.background(MaterialTheme.colorScheme.surface).verticalScroll(scroll)
        .padding(horizontal = 16.dp, vertical = 4.dp)) {
        when (tool) {
            EditTool.Light -> Section(EditSection.Tone) {
                Knob("Exposure", r.exposure, -400..400, defaultValue = Receipt().exposure, label = "%.2f".format(r.exposure / 100.0)) { onChange(r.copy(exposure = it)) }
                Knob("Contrast", r.contrast, -100..100, defaultValue = Receipt().contrast) { onChange(r.copy(contrast = it)) }
                Knob("Pivot", r.pivot, 0..100, defaultValue = Receipt().pivot) { onChange(r.copy(pivot = it)) }
                Knob("Clarity", r.clarity, -100..100, defaultValue = Receipt().clarity) { onChange(r.copy(clarity = it)) }
                Knob("Shadows", r.shadows, -100..100, defaultValue = Receipt().shadows) { onChange(r.copy(shadows = it)) }
                Knob("Highlights", r.highlights, -100..100, defaultValue = Receipt().highlights) { onChange(r.copy(highlights = it)) }
            }
            EditTool.Color -> {
                Section(EditSection.WhiteBalance) {
                    val kelvin = if (r.temperature < 0) (asShotKelvin ?: 6000.0).toInt() else r.temperature
                    Knob("Temperature", kelvin, 2000..10000, defaultValue = (asShotKelvin ?: 6000.0).toInt(), onReset = { onChange(r.copy(temperature = -1)) }, track = TEMPERATURE_TRACK, label = if (r.temperature < 0) "$kelvin K (as shot)" else "$kelvin K") {
                        onChange(r.copy(temperature = it))
                    }
                    Knob("Tint", r.tint, -100..100, defaultValue = Receipt().tint, track = TINT_TRACK) { onChange(r.copy(tint = it, temperature = if (r.temperature < 0) kelvin else r.temperature)) }
                }
                Section(EditSection.Saturation) {
                    Knob("Vibrance", r.vibrance, -100..100, defaultValue = Receipt().vibrance, track = SATURATION_TRACK) { onChange(r.copy(vibrance = it)) }
                    Knob("Saturation", r.saturation, -100..100, defaultValue = Receipt().saturation, track = SATURATION_TRACK) { onChange(r.copy(saturation = it)) }
                }
            }
            EditTool.Curves -> {
                Section(EditSection.Curves) { CurveEditor(r, onChange) }
                Section(EditSection.ContrastCurve) {
                    Knob("Dark strength", r.ds, 0..100, defaultValue = Receipt().ds) { onChange(r.copy(ds = it)) }
                    Knob("Dark range", r.dr, 0..100, defaultValue = Receipt().dr) { onChange(r.copy(dr = it)) }
                    Knob("Light strength", r.ls, 0..100, defaultValue = Receipt().ls) { onChange(r.copy(ls = it)) }
                    Knob("Light range", r.lr, 0..100, defaultValue = Receipt().lr) { onChange(r.copy(lr = it)) }
                    Knob("Lighten", r.lightening, 0..100, defaultValue = Receipt().lightening) { onChange(r.copy(lightening = it)) }
                }
            }
            EditTool.Detail -> {
                Section(EditSection.Sharpen) {
                    Knob("Sharpen", r.sharpen, 0..100, defaultValue = Receipt().sharpen) { onChange(r.copy(sharpen = it)) }
                    Knob("Sharpen masking", r.shMasking, 0..100, defaultValue = Receipt().shMasking) { onChange(r.copy(shMasking = it)) }
                }
                Section(EditSection.Noise) {
                    Toggle("Chroma separation", r.chromaSeparation) { onChange(r.copy(chromaSeparation = it)) }
                    Knob("Chroma blur", r.chromaBlur, 0..10, defaultValue = Receipt().chromaBlur) { onChange(r.copy(chromaBlur = it)) }
                    Knob("Denoise (median)", r.denoiserStrength, 0..100, defaultValue = Receipt().denoiserStrength) { onChange(r.copy(denoiserStrength = it)) }
                    Knob("RBF denoise luma", r.rbfDenoiserLuma, 0..100, defaultValue = Receipt().rbfDenoiserLuma) { onChange(r.copy(rbfDenoiserLuma = it)) }
                    Knob("RBF denoise chroma", r.rbfDenoiserChroma, 0..100, defaultValue = Receipt().rbfDenoiserChroma) { onChange(r.copy(rbfDenoiserChroma = it)) }
                    Knob("RBF denoise range", r.rbfDenoiserRange, 0..100, defaultValue = Receipt().rbfDenoiserRange) { onChange(r.copy(rbfDenoiserRange = it)) }
                }
            }
            EditTool.Effects -> {
                Section(EditSection.Vignette) {
                    Knob("Vignette strength", r.vignetteStrength, -100..100, defaultValue = Receipt().vignetteStrength) { onChange(r.copy(vignetteStrength = it)) }
                    Knob("Vignette radius", r.vignetteRadius, 0..100, defaultValue = Receipt().vignetteRadius) { onChange(r.copy(vignetteRadius = it)) }
                    Knob("Vignette shape", r.vignetteShape, 0..100, defaultValue = Receipt().vignetteShape) { onChange(r.copy(vignetteShape = it)) }
                }
                Section(EditSection.Toning) {
                    Knob("Tone", r.tone, 0..255, defaultValue = Receipt().tone, track = HUE_TRACK) { onChange(r.copy(tone = it)) }
                    Knob("Toning strength", r.toningStrength, 0..100, defaultValue = Receipt().toningStrength) { onChange(r.copy(toningStrength = it)) }
                }
            }
            EditTool.Optics -> {
                Section(EditSection.Lens) {
                    Knob("CA desaturate", r.caDesaturate, 0..100, defaultValue = Receipt().caDesaturate) { onChange(r.copy(caDesaturate = it)) }
                }
                Section(EditSection.Raw) {
                    Toggle("Raw fixes", r.rawFixesEnabled) { onChange(r.copy(rawFixesEnabled = it)) }
                    Caption("Vertical stripes")
                    Choice(listOf(0 to "Off", 1 to "Normal", 2 to "Force"), r.verticalStripes) { onChange(r.copy(verticalStripes = it)) }
                    Caption("Bad pixels")
                    Choice(listOf(0 to "Off", 1 to "Auto", 2 to "Force"), r.badPixels) { onChange(r.copy(badPixels = it)) }
                    if (r.badPixels != 0) Choice(listOf(0 to "Normal search", 1 to "Aggressive"), r.bpsMethod) { onChange(r.copy(bpsMethod = it)) }
                    Caption("Chroma smooth")
                    Choice(listOf(0 to "Off", 1 to "2x2", 2 to "3x3", 3 to "5x5"), r.chromaSmooth) { onChange(r.copy(chromaSmooth = it)) }
                    Caption("Pattern noise")
                    Choice(listOf(0 to "Off", 1 to "On"), r.patternNoise) { onChange(r.copy(patternNoise = it)) }
                }
            }
            // Profile and its switches define the picture; it has no "preview without".
            EditTool.Profile -> Section(EditSection.Profile, canHide = false) {
                Choice(listOf(1 to "Standard", 2 to "Tonemapped", 3 to "Film", 4 to "Alexa Log-C", 5 to "Cineon Log", 6 to "Sony S-Log3"), r.profile) {
                    onChange(r.copy(profile = it))
                }
                Knob("Gamma", r.gamma, 1..400, defaultValue = Receipt().gamma, label = "%.2f".format(r.gamma / 100.0)) { onChange(r.copy(gamma = it)) }
                Caption("Camera matrix")
                Choice(listOf(0 to "Off", 1 to "On", 2 to "Danne"), r.camMatrixUsed) { onChange(r.copy(camMatrixUsed = it)) }
                Toggle("AgX", r.agx) { onChange(r.copy(agx = it)) }
                Toggle("Creative adjustments", r.creativeAdjustments) { onChange(r.copy(creativeAdjustments = it)) }
                Toggle("Highlight reconstruction", r.highlightReconstruction) { onChange(r.copy(highlightReconstruction = it)) }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}

/** Section header (after RapidRAW's panels): collapse, preview without, reset. */
@Composable
private fun SectionHeader(title: String, changed: Boolean, expanded: Boolean, off: Boolean, canHide: Boolean,
    onExpand: (Boolean) -> Unit, onReset: () -> Unit, onOff: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp).clickable(onClickLabel = if (expanded) "Collapse $title" else "Expand $title") { onExpand(!expanded) },
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (expanded) Icons.Default.ExpandMore else Icons.Default.ChevronRight, null, Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(title.uppercase(), style = MaterialTheme.typography.labelSmall,
            color = if (off) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        if (canHide || off) IconButton(onClick = { onOff(!off) }, modifier = Modifier.size(36.dp)) {
            Icon(if (off) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                if (off) "Show $title in the preview" else "Preview without $title", Modifier.size(18.dp),
                tint = if (off) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (changed) IconButton(onClick = onReset, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.RestartAlt, "Reset $title", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// Track colours: what moving the control does to the picture.
private val TEMPERATURE_TRACK = listOf(Color(0xFF3D7BD9), Color(0xFFDADADA), Color(0xFFE0A030))
private val TINT_TRACK = listOf(Color(0xFF3FA34D), Color(0xFFDADADA), Color(0xFFC045B8))
private val SATURATION_TRACK = listOf(Color(0xFF8A8A8A), Color(0xFFB0B0B0), Color(0xFFE04040), Color(0xFFE0C030), Color(0xFF3FA3E0))
// Tone is Qt HSL(v / 255, 1, 0.5) in MLV-App: the full hue circle.
private val HUE_TRACK = (0..6).map { Color.hsl(it * 60f % 360f, 1f, 0.5f) }

/**
 * A grading slider. Relative 1:1 drags avoid jumps; numeric entry and reset
 * share receipt units. Layout after RapidRAW's slider (design only): name and
 * value on one line, fill drawn from the default value, tap the name to reset,
 * tap the value to type it; [track] colours the track (temperature, tint, hue).
 * The 44 dp track height is the touch target.
 */
@Composable
private fun Knob(name: String, value: Int, range: IntRange, defaultValue: Int = 0,
    label: String = value.toString(), onReset: (() -> Unit)? = null, track: List<Color>? = null,
    onValue: (Int) -> Unit) {
    var entry by remember { mutableStateOf(false) }
    val latestValue by rememberUpdatedState(value)
    val latestChange by rememberUpdatedState(onValue)
    val latestReset by rememberUpdatedState(onReset)
    val accent = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    // Temperature resets to "as shot" (onReset); the others to their default.
    val changed = if (onReset != null) !label.endsWith("(as shot)") else value != defaultValue
    fun reset() { latestReset?.invoke() ?: latestChange(defaultValue.coerceIn(range)) }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, fontSize = 13.sp,
                color = if (changed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).clickable(onClickLabel = "Reset $name") { reset() }.padding(vertical = 4.dp))
            Text(label, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                color = if (changed) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .clickable(onClickLabel = "Set $name value") { entry = true }
                    .semantics { contentDescription = "Set $name value" }
                    .padding(horizontal = 6.dp, vertical = 4.dp))
        }
        Canvas(Modifier.fillMaxWidth().height(44.dp)
            .semantics {
                contentDescription = "$name, $label"
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), range.first.toFloat()..range.last.toFloat())
                setProgress { latestChange(it.roundToInt().coerceIn(range)); true }
                customActions = listOf(CustomAccessibilityAction("Reset $name") {
                    latestReset?.invoke() ?: latestChange(defaultValue.coerceIn(range)); true
                })
            }
            .pointerInput(range, defaultValue) { detectTapGestures(onDoubleTap = { reset() }) }
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
            fun at(v: Int) = left + (right - left) * ((v - range.first).toFloat() / (range.last - range.first)).coerceIn(0f, 1f)
            val x = at(value)
            val origin = at(defaultValue)
            val stroke = 4.dp.toPx()
            if (track != null) {
                drawLine(androidx.compose.ui.graphics.Brush.horizontalGradient(track, left, right),
                    Offset(left, y), Offset(right, y), stroke, StrokeCap.Round, alpha = 0.85f)
            } else {
                drawLine(trackColor, Offset(left, y), Offset(right, y), stroke, StrokeCap.Round)
                // Fill from the default value, so a bipolar control grows from its centre.
                if (x != origin) drawLine(accent, Offset(origin, y), Offset(x, y), stroke, StrokeCap.Round)
            }
            // Default-value tick.
            drawLine(trackColor, Offset(origin, y - 7.dp.toPx()), Offset(origin, y + 7.dp.toPx()), 1.5.dp.toPx())
            drawCircle(Color.White, 8.dp.toPx(), Offset(x, y))
            drawCircle(if (track != null) Color(0xFF202124) else accent, 5.dp.toPx(), Offset(x, y))
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
        // − / + nudge the value live (0.1 EV, 50 K, otherwise one slider unit).
        val step = when (name) { "Exposure" -> 10; "Temperature" -> 50; else -> 1 }
        fun nudge(by: Int) {
            val current = (input.text.replace(',', '.').toDoubleOrNull()?.times(scale)?.roundToInt() ?: latestValue).coerceIn(range)
            val next = (current + by).coerceIn(range)
            val text = display(next)
            input = TextFieldValue(text, TextRange(text.length)); error = null
            onValue(next)
        }
        AlertDialog(onDismissRequest = { entry = false }, title = { Text(name) }, text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = { nudge(-step) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Remove, "Decrease $name") }
                    OutlinedTextField(input, { input = it; error = null }, singleLine = true,
                        label = { Text(if (name == "Exposure") "EV" else if (name == "Temperature") "Kelvin" else "Value") },
                        textStyle = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center),
                        isError = error != null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { accept() }), modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                    FilledTonalIconButton(onClick = { nudge(step) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Add, "Increase $name") }
                }
                Text(error ?: "Range ${display(range.first)} to ${display(range.last)} · default ${display(defaultValue)}", fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { latestReset?.invoke() ?: onValue(defaultValue.coerceIn(range)); entry = false }) { Text("Reset to default") }
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
