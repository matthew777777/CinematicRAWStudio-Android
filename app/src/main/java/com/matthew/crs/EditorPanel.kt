// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
@Composable
fun EditorPanel(r: Receipt, asShotKelvin: Double?, onChange: (Receipt) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Edit (MLV-App processing)", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onChange(Receipt()) }) { Text("Reset") }
        }
        Section("Processing")
        Knob("Exposure", r.exposure, -400..400, label = "%.2f".format(r.exposure / 100.0)) { onChange(r.copy(exposure = it)) }
        Knob("Contrast", r.contrast, -100..100) { onChange(r.copy(contrast = it)) }
        Knob("Pivot", r.pivot, 0..100) { onChange(r.copy(pivot = it)) }
        val kelvin = if (r.temperature < 0) (asShotKelvin ?: 6000.0).toInt() else r.temperature
        Knob("Temperature", kelvin, 2000..10000, label = if (r.temperature < 0) "$kelvin K (as shot)" else "$kelvin K") {
            onChange(r.copy(temperature = it))
        }
        Knob("Tint", r.tint, -100..100) { onChange(r.copy(tint = it, temperature = if (r.temperature < 0) kelvin else r.temperature)) }
        Knob("Clarity", r.clarity, -100..100) { onChange(r.copy(clarity = it)) }
        Knob("Vibrance", r.vibrance, -100..100) { onChange(r.copy(vibrance = it)) }
        Knob("Saturation", r.saturation, -100..100) { onChange(r.copy(saturation = it)) }
        Knob("Shadows", r.shadows, -100..100) { onChange(r.copy(shadows = it)) }
        Knob("Highlights", r.highlights, -100..100) { onChange(r.copy(highlights = it)) }

        Section("Contrast curve")
        Knob("Dark strength", r.ds, 0..100) { onChange(r.copy(ds = it)) }
        Knob("Dark range", r.dr, 0..100) { onChange(r.copy(dr = it)) }
        Knob("Light strength", r.ls, 0..100) { onChange(r.copy(ls = it)) }
        Knob("Light range", r.lr, 0..100) { onChange(r.copy(lr = it)) }
        Knob("Lighten", r.lightening, 0..100) { onChange(r.copy(lightening = it)) }

        Section("Profile")
        Choice(listOf(1 to "Standard", 2 to "Tonemapped", 3 to "Film", 4 to "Alexa Log-C", 5 to "Cineon Log", 6 to "Sony S-Log3"), r.profile) {
            onChange(r.copy(profile = it))
        }
        Knob("Gamma", r.gamma, 1..400, label = "%.2f".format(r.gamma / 100.0)) { onChange(r.copy(gamma = it)) }
        Text("Camera matrix", fontSize = 12.sp, color = Color.Gray)
        Choice(listOf(0 to "Off", 1 to "On", 2 to "Danne"), r.camMatrixUsed) { onChange(r.copy(camMatrixUsed = it)) }
        Toggle("AgX", r.agx) { onChange(r.copy(agx = it)) }
        Toggle("Creative adjustments", r.creativeAdjustments) { onChange(r.copy(creativeAdjustments = it)) }
        Toggle("Highlight reconstruction", r.highlightReconstruction) { onChange(r.copy(highlightReconstruction = it)) }

        Section("Details")
        Knob("Sharpen", r.sharpen, 0..100) { onChange(r.copy(sharpen = it)) }
        Knob("Sharpen masking", r.shMasking, 0..100) { onChange(r.copy(shMasking = it)) }
        Toggle("Chroma separation", r.chromaSeparation) { onChange(r.copy(chromaSeparation = it)) }
        Knob("Chroma blur", r.chromaBlur, 0..10) { onChange(r.copy(chromaBlur = it)) }
        Knob("Denoise (median)", r.denoiserStrength, 0..100) { onChange(r.copy(denoiserStrength = it)) }
        Knob("RBF denoise luma", r.rbfDenoiserLuma, 0..100) { onChange(r.copy(rbfDenoiserLuma = it)) }
        Knob("RBF denoise chroma", r.rbfDenoiserChroma, 0..100) { onChange(r.copy(rbfDenoiserChroma = it)) }
        Knob("RBF denoise range", r.rbfDenoiserRange, 0..100) { onChange(r.copy(rbfDenoiserRange = it)) }

        Section("Toning")
        Knob("Tone", r.tone, 0..255) { onChange(r.copy(tone = it)) }
        Knob("Toning strength", r.toningStrength, 0..100) { onChange(r.copy(toningStrength = it)) }

        Section("Lens")
        Knob("CA desaturate", r.caDesaturate, 0..100) { onChange(r.copy(caDesaturate = it)) }
        Knob("Vignette strength", r.vignetteStrength, -100..100) { onChange(r.copy(vignetteStrength = it)) }
        Knob("Vignette radius", r.vignetteRadius, 0..100) { onChange(r.copy(vignetteRadius = it)) }
        Knob("Vignette shape", r.vignetteShape, 0..100) { onChange(r.copy(vignetteShape = it)) }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
}

@Composable
private fun Knob(name: String, value: Int, range: IntRange, label: String = value.toString(), onValue: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, fontSize = 12.sp, modifier = Modifier.width(118.dp))
        Slider(
            value = value.toFloat(), onValueChange = { onValue(Math.round(it)) },
            valueRange = range.first.toFloat()..range.last.toFloat(), modifier = Modifier.weight(1f).height(32.dp),
        )
        Text(label, fontSize = 11.sp, color = Color.Gray, modifier = Modifier.width(78.dp).padding(start = 6.dp))
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
