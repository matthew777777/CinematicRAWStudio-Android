// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs

import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import java.io.Closeable

/**
 * Grade settings in MLV-App's processing API units (raw_processing.h), so
 * every value means exactly what it means in MLV-App. The editor maps its
 * sliders onto these with MLV-App's own slider scaling (MainWindow.cpp).
 */
data class GradeSettings(
    val exposure: Double = 0.0,          // stops
    val contrast: Double = 0.0,          // processingSetSimpleContrast
    val pivot: Double = 0.0,
    val clarity: Double = 0.0,
    val temperature: Double = 6000.0,    // kelvin, 2500..10000
    val tint: Double = 0.0,              // -10..10
    val shadows: Double = 0.0,           // -1..1
    val highlights: Double = 0.0,        // -1..1
    val saturation: Double = 1.0,        // factor
    val vibrance: Double = 1.0,          // factor
    val sharpen: Double = 0.0,           // 0..1
    val sharpenMasking: Int = 0,         // 0..100
    val rbfLuma: Int = 0,
    val rbfChroma: Int = 0,
    val rbfRange: Int = 40,
    val caDesaturate: Int = 0,
    val vignetteStrength: Int = 0,       // -127..127
    val vignetteRadius: Double = 1.0,
    val vignetteShape: Double = 0.0,
    val profile: Int = 1,                // PROFILE_TONEMAPPED
    val gamut: Int = 0,
    val highlightReconstruction: Boolean = false,
) {
    fun values(): Map<String, Double> = mapOf(
        "exposure" to exposure, "contrast" to contrast, "pivot" to pivot, "clarity" to clarity,
        "temperature" to temperature, "tint" to tint, "shadows" to shadows, "highlights" to highlights,
        "saturation" to saturation, "vibrance" to vibrance, "sharpen" to sharpen,
        "sharpenMasking" to sharpenMasking.toDouble(), "rbfLuma" to rbfLuma.toDouble(),
        "rbfChroma" to rbfChroma.toDouble(), "rbfRange" to rbfRange.toDouble(),
        "caDesaturate" to caDesaturate.toDouble(), "vignetteStrength" to vignetteStrength.toDouble(),
        "vignetteRadius" to vignetteRadius, "vignetteShape" to vignetteShape,
        "profile" to profile.toDouble(), "gamut" to gamut.toDouble(),
        "highlightReconstruction" to if (highlightReconstruction) 1.0 else 0.0,
    )
}

/** One MLV-App processing object (vendored MLV-App code) for a clip. */
class MlvGrade(clip: CrawClip) : Closeable {
    private var handle = nativeCreate()
    private var applied: Map<String, Double> = emptyMap()

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

    /** Pushes changed settings into MLV-App's processing object. */
    @Synchronized
    fun apply(s: GradeSettings) {
        val v = s.values()
        // Profile first: MLV-App's profiles reset dependent state.
        val order = listOf("profile") + v.keys.filter { it != "profile" }
        for (k in order) {
            val x = v.getValue(k)
            if (applied[k] != x) nativeSet(handle, k, x)
        }
        applied = v
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
