// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs

import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import java.io.Closeable
import kotlin.math.ln
import kotlin.math.pow

/** Curve control points (x, y), as MLV-App's curve widgets hold them. */
data class Curve(val xs: List<Float>, val ys: List<Float>) {
    companion object {
        /** Curves.cpp initLine: (0.00001, 0.00001)-(1, 1). */
        val GRADATION_IDENTITY = Curve(listOf(0.00001f, 1f), listOf(0.00001f, 1f))
        /** HueVsDiagram.cpp initLine: (0, 0)-(1, 0); y in -1..1. */
        val HUE_IDENTITY = Curve(listOf(0f, 1f), listOf(0f, 0f))
    }
}

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
    // RAW correction (llrawproc; ReceiptSettings.cpp defaults).
    val rawFixesEnabled: Boolean = true,
    val verticalStripes: Int = 0,      // 0 off, 1 normal, 2 force (MLV-App: on by default only for the 5D3)
    val badPixels: Int = 0,            // 0 off, 1 auto, 2 force
    val bpsMethod: Int = 0,            // 0 normal, 1 aggressive
    val bpiMethod: Int = 0,
    val chromaSmooth: Int = 0,         // 0 off, 1 2x2, 2 3x3, 3 5x5
    val patternNoise: Int = 0,         // 0 off, 1 on
    /** Gradation curves: 0 luma, 1 red, 2 green, 3 blue (processingSetGCurve). */
    val gradation: List<Curve> = List(4) { Curve.GRADATION_IDENTITY },
    /** 0 hue vs hue, 1 hue vs saturation, 2 hue vs luma, 3 luma vs saturation (processingSetHueVsCurves). */
    val hueVs: List<Curve> = List(4) { Curve.HUE_IDENTITY },
) {
    fun rawToolValues(): IntArray = intArrayOf(if (rawFixesEnabled) 1 else 0, verticalStripes, badPixels, bpsMethod,
        bpiMethod, chromaSmooth, patternNoise)

    fun toJson(): String = org.json.JSONObject().apply {
        for ((k, get, _) in INTS) put(k, get(this@Receipt))
        for ((k, get, _) in BOOLS) put(k, get(this@Receipt))
        fun arr(cs: List<Curve>) = org.json.JSONArray(cs.map {
            org.json.JSONObject().put("x", org.json.JSONArray(it.xs.map(Float::toDouble))).put("y", org.json.JSONArray(it.ys.map(Float::toDouble)))
        })
        put("gradation", arr(gradation))
        put("hueVs", arr(hueVs))
    }.toString()

    companion object {
        // Explicit keys (stable across builds; R8 renames fields).
        private val INTS: List<Triple<String, (Receipt) -> Int, (Receipt, Int) -> Receipt>> = listOf(
            Triple("exposure", { it.exposure }, { r, v -> r.copy(exposure = v) }),
            Triple("contrast", { it.contrast }, { r, v -> r.copy(contrast = v) }),
            Triple("pivot", { it.pivot }, { r, v -> r.copy(pivot = v) }),
            Triple("temperature", { it.temperature }, { r, v -> r.copy(temperature = v) }),
            Triple("tint", { it.tint }, { r, v -> r.copy(tint = v) }),
            Triple("clarity", { it.clarity }, { r, v -> r.copy(clarity = v) }),
            Triple("vibrance", { it.vibrance }, { r, v -> r.copy(vibrance = v) }),
            Triple("saturation", { it.saturation }, { r, v -> r.copy(saturation = v) }),
            Triple("ds", { it.ds }, { r, v -> r.copy(ds = v) }),
            Triple("dr", { it.dr }, { r, v -> r.copy(dr = v) }),
            Triple("ls", { it.ls }, { r, v -> r.copy(ls = v) }),
            Triple("lr", { it.lr }, { r, v -> r.copy(lr = v) }),
            Triple("lightening", { it.lightening }, { r, v -> r.copy(lightening = v) }),
            Triple("shadows", { it.shadows }, { r, v -> r.copy(shadows = v) }),
            Triple("highlights", { it.highlights }, { r, v -> r.copy(highlights = v) }),
            Triple("sharpen", { it.sharpen }, { r, v -> r.copy(sharpen = v) }),
            Triple("shMasking", { it.shMasking }, { r, v -> r.copy(shMasking = v) }),
            Triple("chromaBlur", { it.chromaBlur }, { r, v -> r.copy(chromaBlur = v) }),
            Triple("profile", { it.profile }, { r, v -> r.copy(profile = v) }),
            Triple("gamma", { it.gamma }, { r, v -> r.copy(gamma = v) }),
            Triple("camMatrixUsed", { it.camMatrixUsed }, { r, v -> r.copy(camMatrixUsed = v) }),
            Triple("denoiserStrength", { it.denoiserStrength }, { r, v -> r.copy(denoiserStrength = v) }),
            Triple("denoiserWindow", { it.denoiserWindow }, { r, v -> r.copy(denoiserWindow = v) }),
            Triple("rbfDenoiserLuma", { it.rbfDenoiserLuma }, { r, v -> r.copy(rbfDenoiserLuma = v) }),
            Triple("rbfDenoiserChroma", { it.rbfDenoiserChroma }, { r, v -> r.copy(rbfDenoiserChroma = v) }),
            Triple("rbfDenoiserRange", { it.rbfDenoiserRange }, { r, v -> r.copy(rbfDenoiserRange = v) }),
            Triple("caDesaturate", { it.caDesaturate }, { r, v -> r.copy(caDesaturate = v) }),
            Triple("vignetteStrength", { it.vignetteStrength }, { r, v -> r.copy(vignetteStrength = v) }),
            Triple("vignetteRadius", { it.vignetteRadius }, { r, v -> r.copy(vignetteRadius = v) }),
            Triple("vignetteShape", { it.vignetteShape }, { r, v -> r.copy(vignetteShape = v) }),
            Triple("tone", { it.tone }, { r, v -> r.copy(tone = v) }),
            Triple("toningStrength", { it.toningStrength }, { r, v -> r.copy(toningStrength = v) }),
            Triple("verticalStripes", { it.verticalStripes }, { r, v -> r.copy(verticalStripes = v) }),
            Triple("badPixels", { it.badPixels }, { r, v -> r.copy(badPixels = v) }),
            Triple("bpsMethod", { it.bpsMethod }, { r, v -> r.copy(bpsMethod = v) }),
            Triple("bpiMethod", { it.bpiMethod }, { r, v -> r.copy(bpiMethod = v) }),
            Triple("chromaSmooth", { it.chromaSmooth }, { r, v -> r.copy(chromaSmooth = v) }),
            Triple("patternNoise", { it.patternNoise }, { r, v -> r.copy(patternNoise = v) }),
        )
        private val BOOLS: List<Triple<String, (Receipt) -> Boolean, (Receipt, Boolean) -> Receipt>> = listOf(
            Triple("highlightReconstruction", { it.highlightReconstruction }, { r, v -> r.copy(highlightReconstruction = v) }),
            Triple("chromaSeparation", { it.chromaSeparation }, { r, v -> r.copy(chromaSeparation = v) }),
            Triple("creativeAdjustments", { it.creativeAdjustments }, { r, v -> r.copy(creativeAdjustments = v) }),
            Triple("exrMode", { it.exrMode }, { r, v -> r.copy(exrMode = v) }),
            Triple("agx", { it.agx }, { r, v -> r.copy(agx = v) }),
            Triple("rawFixesEnabled", { it.rawFixesEnabled }, { r, v -> r.copy(rawFixesEnabled = v) }),
        )

        /** Receipt from [toJson]; missing keys keep MLV-App's defaults. */
        fun fromJson(json: String): Receipt {
            val o = org.json.JSONObject(json)
            var r = Receipt()
            for ((k, _, set) in INTS) if (o.has(k)) r = set(r, o.getInt(k))
            for ((k, _, set) in BOOLS) if (o.has(k)) r = set(r, o.getBoolean(k))
            fun curves(name: String, n: Int): List<Curve>? = o.optJSONArray(name)?.let { a ->
                List(minOf(n, a.length())) { i ->
                    val c = a.getJSONObject(i)
                    val x = c.getJSONArray("x"); val y = c.getJSONArray("y")
                    Curve(List(x.length()) { x.getDouble(it).toFloat() }, List(y.length()) { y.getDouble(it).toFloat() })
                }.takeIf { it.size == n }
            }
            curves("gradation", 4)?.let { r = r.copy(gradation = it) }
            curves("hueVs", 4)?.let { r = r.copy(hueVs = it) }
            return r
        }
    }

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
    private var appliedCurves: Receipt? = null

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
        for (c in 0..3) {
            if (appliedCurves?.gradation?.get(c) != r.gradation[c])
                nativeSetCurve(handle, 0, c, r.gradation[c].xs.toFloatArray(), r.gradation[c].ys.toFloatArray())
            if (appliedCurves?.hueVs?.get(c) != r.hueVs[c])
                nativeSetCurve(handle, 1, c, r.hueVs[c].xs.toFloatArray(), r.hueVs[c].ys.toFloatArray())
        }
        appliedCurves = r
        val toning = r.toneRgb().toList() + r.toningStrength
        if (toning != appliedToning) {
            nativeSetToning(handle, toning[0], toning[1], toning[2], toning[3])
            appliedToning = toning
        }
    }

    /** MLV-App processing of RCD camera RGB (RGBA16F) into [out] (same size). */
    fun render(rgbF16: HardwareBuffer, out: Bitmap): Boolean = handle != 0L && nativeRender(handle, rgbF16, out) == 0

    fun writeProcessedP010(image: android.media.Image, width: Int, height: Int): Boolean = handle != 0L && nativeWriteP010(handle, image, width, height) == 0

    /**
     * MLV-App's grade on Vulkan, straight from the RCD output into a P010 codec
     * Image (fitted to width x height). Null when these settings need the CPU
     * path (blur-based or spatial stages); otherwise whether it succeeded.
     */
    fun gpuWriteP010(rgbF16: HardwareBuffer, image: android.media.Image, width: Int, height: Int): Boolean? {
        if (handle == 0L) return false
        return when (nativeGpuWriteP010(handle, rgbF16, image, width, height)) { 0 -> true; 1 -> null; else -> false }
    }

    /** [render] on Vulkan; null when the CPU path is needed. */
    fun gpuRender(rgbF16: HardwareBuffer, out: Bitmap): Boolean? {
        if (handle == 0L) return false
        return when (nativeGpuRender(handle, rgbF16, out)) { 0 -> true; 1 -> null; else -> false }
    }

    /** {max diff, differing pixels, pixels, sum diff} of Vulkan vs MLV-App's CPU code, or null. */
    fun gpuParity(rgbF16: HardwareBuffer): LongArray? = if (handle == 0L) null else nativeGpuParity(handle, rgbF16)

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
        /** MLV-App's drawn curve at n samples (kind 0 gradation spline, 1 hue-vs cosine). */
        fun interpolate(kind: Int, c: Curve, n: Int): FloatArray? =
            nativeInterpolate(kind, c.xs.toFloatArray(), c.ys.toFloatArray(), n)

        @JvmStatic private external fun nativeInterpolate(kind: Int, xs: FloatArray, ys: FloatArray, n: Int): FloatArray?
        @JvmStatic private external fun nativeWriteP010(handle: Long, image: android.media.Image, width: Int, height: Int): Int
        @JvmStatic private external fun nativeRender(handle: Long, rgbF16: HardwareBuffer, out: Bitmap): Int
        @JvmStatic private external fun nativeGpuWriteP010(handle: Long, rgbF16: HardwareBuffer, image: android.media.Image, width: Int, height: Int): Int
        @JvmStatic private external fun nativeGpuRender(handle: Long, rgbF16: HardwareBuffer, out: Bitmap): Int
        @JvmStatic private external fun nativeGpuParity(handle: Long, rgbF16: HardwareBuffer): LongArray?
    }
}
