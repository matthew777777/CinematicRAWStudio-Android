// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs

import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import java.io.Closeable
import kotlin.math.ln
import kotlin.math.pow

/**
 * An MLV-App "receipt": every control in MLV-App's own slider units and
 * defaults (platform/qt/ReceiptSettings.cpp, MainWindow.ui ranges). The
 * conversion to the processing API is MLV-App's slider handlers
 * (MainWindow.cpp on_*_valueChanged), applied in MLV-App's setSliders order.
 */
data class Receipt(
    val exposure: Int = 0,             // -400..400   -> stops = v/100 + 1.2
    val contrast: Int = 0,             // -100..100   -> v/100
    val pivot: Int = 75,               // 0..100      -> v/100
    val temperature: Int = -1,         // 2000..10000 K; -1 = from the clip
    val tint: Int = 0,                 // -100..100   -> v/10
    val clarity: Int = 0,              // -100..100   -> v/100
    val vibrance: Int = 0,             // -100..100   -> ((v+100)/200*2)^(ln 3.6/ln 2)
    val saturation: Int = 0,           // -100..100   -> same curve
    val ds: Int = 20,                  // 0..100      -> v*22.5/100 (dark contrast strength)
    val dr: Int = 70,                  // 0..100      -> v/100
    val ls: Int = 0,                   // 0..100      -> v*11.2/100
    val lr: Int = 50,                  // 0..100      -> v/100
    val lightening: Int = 0,           // 0..100      -> v*0.6/100
    val shadows: Int = 0,              // -100..100   -> v*1.5/100
    val highlights: Int = 0,           // -100..100   -> v*1.5/100
    val sharpen: Int = 0,              // 0..100      -> v/100
    val shMasking: Int = 0,            // 0..100
    val chromaBlur: Int = 0,           // 0..10
    val highlightReconstruction: Boolean = false,
    val chromaSeparation: Boolean = false,
    val profile: Int = 2,              // combo index: 1 Standard, 2 Tonemapped, 3 Film, 4 Log-C, 5 Cineon, 6 S-Log3, ...
    val gamma: Int = 315,              // 1..400      -> v/100
    val creativeAdjustments: Boolean = true,
    val exrMode: Boolean = false,
    val agx: Boolean = true,
    val camMatrixUsed: Int = 1,        // 0 off, 1 on, 2 "Danne"
    val denoiserStrength: Int = 0,     // 0..100
    val denoiserWindow: Int = 3,
    val rbfDenoiserLuma: Int = 0,      // 0..100
    val rbfDenoiserChroma: Int = 0,    // 0..100
    val rbfDenoiserRange: Int = 40,    // 0..100
    val caDesaturate: Int = 0,         // 0..100
    val vignetteStrength: Int = 0,     // -100..100   -> v*1.27
    val vignetteRadius: Int = 20,      // 0..100      -> v/100
    val vignetteShape: Int = 0,        // 0..100      -> v/100
    val tone: Int = 0,                 // 0..255 hue  -> Qt HSL(v/255, 1, 0.5)
    val toningStrength: Int = 0,       // 0..100
) {
    /** MLV-App setSliders order: (setter name, API value). */
    fun apiValues(asShotKelvin: Double, asShotTint: Double): List<Pair<String, Double>> {
        val satCurve = { v: Int -> ((v + 100) / 200.0 * 2.0).pow(ln(3.6) / ln(2.0)) }
        return listOf(
            "exposure" to exposure / 100.0 + 1.2,
            "contrast" to contrast / 100.0,
            "pivot" to pivot / 100.0,
            "camMatrix" to camMatrixUsed.toDouble(),
            "temperature" to if (temperature < 0) asShotKelvin else temperature.toDouble(),
            "tint" to if (temperature < 0 && tint == 0) asShotTint else tint / 10.0,
            "clarity" to clarity / 100.0,
            "vibrance" to satCurve(vibrance),
            "saturation" to satCurve(saturation),
            "dcFactor" to ds * 22.5 / 100.0,
            "dcRange" to dr / 100.0,
            "lcFactor" to ls * 11.2 / 100.0,
            "lcRange" to lr / 100.0,
            "lighten" to lightening * 0.6 / 100.0,
            "shadows" to shadows * 1.5 / 100.0,
            "highlights" to highlights * 1.5 / 100.0,
            "sharpen" to sharpen / 100.0,
            "sharpenMasking" to shMasking.toDouble(),
            "chromaBlur" to chromaBlur.toDouble(),
            "highlightReconstruction" to if (highlightReconstruction) 1.0 else 0.0,
            "chromaSeparation" to if (chromaSeparation) 1.0 else 0.0,
            "profile" to (profile - 1).toDouble(),
            "gamma" to gamma / 100.0,
            "creative" to if (creativeAdjustments) 1.0 else 0.0,
            "exr" to if (exrMode) 1.0 else 0.0,
            "agx" to if (agx) 1.0 else 0.0,
            "denoiseWindow" to denoiserWindow.toDouble(),
            "denoiseStrength" to denoiserStrength.toDouble(),
            "rbfLuma" to rbfDenoiserLuma.toDouble(),
            "rbfChroma" to rbfDenoiserChroma.toDouble(),
            "rbfRange" to rbfDenoiserRange.toDouble(),
            "caDesaturate" to caDesaturate.toDouble(),
            "vignetteStrength" to vignetteStrength * 1.27,
            "vignetteRadius" to vignetteRadius / 100.0,
            "vignetteShape" to vignetteShape / 100.0,
        )
    }

    /** Qt QColor::setHslF(h, 1.0, 0.5) -> 8-bit RGB, as MLV-App's tone slider. */
    fun toneRgb(): IntArray {
        val h = tone / 255.0 * 6.0
        val c = 1.0  // chroma at s = 1, l = 0.5
        val x = c * (1 - kotlin.math.abs(h % 2 - 1))
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0.0); 1 -> Triple(x, c, 0.0); 2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c); 4 -> Triple(x, 0.0, c); else -> Triple(c, 0.0, x)
        }
        // QColor stores 16-bit channels; red()/green()/blue() return the high byte.
        fun q8(v: Double) = (Math.round(v * 65535.0).toInt()) shr 8
        return intArrayOf(q8(r), q8(g), q8(b))
    }
}

/** One MLV-App processing object (vendored MLV-App code) for a clip. */
class MlvGrade(clip: CrawClip) : Closeable {
    private var handle = nativeCreate()
    private var applied: Map<String, Double> = emptyMap()
    private var appliedToning: List<Int>? = null

    /** As-shot white balance in MLV-App kelvin/tint (found with MLV-App itself). */
    var asShot: Pair<Double, Double>? = null
        private set

    init {
        clip.camMatrices()?.let { (d65, a) -> nativeSetCamMatrix(handle, d65, a) }
    }

    fun findAsShot(neutral: FloatArray): Pair<Double, Double> {
        val r = nativeFindWhiteBalance(handle, neutral[0].toDouble(), neutral[1].toDouble(), neutral[2].toDouble())
        return (r[0] to r[1]).also { asShot = it }
    }

    /** Pushes changed receipt values into MLV-App's processing object, in MLV-App's order. */
    @Synchronized
    fun apply(r: Receipt) {
        val (k, t) = asShot ?: (6000.0 to 0.0)
        val values = r.apiValues(k, t)
        // MLV-App's profile setter resets dependent state: when it changes, re-send everything after it.
        val profileChanged = applied["profile"] != values.first { it.first == "profile" }.second
        var afterProfile = false
        for ((name, v) in values) {
            if (name == "profile") afterProfile = true
            if (applied[name] != v || (afterProfile && profileChanged)) nativeSet(handle, name, v)
        }
        applied = values.toMap()
        val toning = r.toneRgb().toList() + r.toningStrength
        if (toning != appliedToning) {
            nativeSetToning(handle, toning[0], toning[1], toning[2], toning[3])
            appliedToning = toning
        }
    }

    /** MLV-App processing of RCD camera RGB (RGBA16F) into [out] (same size). */
    fun render(rgbF16: HardwareBuffer, out: Bitmap): Boolean = handle != 0L && nativeRender(handle, rgbF16, out) == 0

    @Synchronized
    override fun close() {
        if (handle != 0L) { nativeFree(handle); handle = 0 }
    }

    companion object {
        @JvmStatic private external fun nativeCreate(): Long
        @JvmStatic private external fun nativeFree(handle: Long)
        @JvmStatic private external fun nativeSetCamMatrix(handle: Long, d65: DoubleArray, a: DoubleArray)
        @JvmStatic private external fun nativeSet(handle: Long, name: String, value: Double): Boolean
        @JvmStatic private external fun nativeSetToning(handle: Long, r: Int, g: Int, b: Int, strength: Int)
        @JvmStatic private external fun nativeSetCurve(handle: Long, kind: Int, channel: Int, xs: FloatArray, ys: FloatArray)
        @JvmStatic private external fun nativeFindWhiteBalance(handle: Long, r: Double, g: Double, b: Double): DoubleArray
        @JvmStatic private external fun nativeRender(handle: Long, rgbF16: HardwareBuffer, out: Bitmap): Int
    }
}
