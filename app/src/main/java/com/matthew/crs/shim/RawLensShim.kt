// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.rawlens

// Pieces of larger RawLens files that the vendored GPU classes need, copied
// verbatim from RawLens 1557478 (RawSensorUnpacker.kt: CfaColor and the
// BayerPattern members used by VfRcd; DirectLogRecorder.kt companion:
// packShading and floatToHalfBits). packShading's LensShadingModel
// validation is replaced by the equivalent finite/>= 1 content check.

enum class CfaColor { RED, GREEN, BLUE }

/** Standard Camera2 2x2 Bayer arrangements, expressed at the full-sensor origin. */
enum class BayerPattern(private val cells: List<CfaColor>) {
    RGGB(listOf(CfaColor.RED, CfaColor.GREEN, CfaColor.GREEN, CfaColor.BLUE)),
    GRBG(listOf(CfaColor.GREEN, CfaColor.RED, CfaColor.BLUE, CfaColor.GREEN)),
    GBRG(listOf(CfaColor.GREEN, CfaColor.BLUE, CfaColor.RED, CfaColor.GREEN)),
    BGGR(listOf(CfaColor.BLUE, CfaColor.GREEN, CfaColor.GREEN, CfaColor.RED));

    fun colorAt(sensorX: Int, sensorY: Int): CfaColor =
        cells[((sensorY and 1) shl 1) or (sensorX and 1)]

    internal val cellOrdinals = IntArray(4) { cells[it].ordinal }

    fun colorOrdinalAt(sensorX: Int, sensorY: Int): Int =
        cellOrdinals[((sensorY and 1) shl 1) or (sensorX and 1)]
}

object RawLensShading {
    /**
     * Validate + pack a lens-shading map for the native record stages:
     * dims [rows, cols, l, t, r, b] + the Camera2-order gains as fp16 bits.
     */
    fun packShading(
        rows: Int, cols: Int,
        left: Int, top: Int, right: Int, bottom: Int,
        gains: FloatArray
    ): Pair<IntArray, ShortArray>? {
        if (rows <= 0 || cols <= 0 || rows > 64 || cols > 64) return null
        if (gains.size != rows * cols * 4) return null
        if (gains.any { !it.isFinite() || it < 1f }) return null
        return intArrayOf(rows, cols, left, top, right, bottom) to
            ShortArray(gains.size) { i -> floatToHalfBits(gains[i]) }
    }

    /** IEEE-754 fp32 -> fp16 bits (round-to-nearest-even). */
    fun floatToHalfBits(f: Float): Short {
        val bits = f.toRawBits()
        val sign = (bits ushr 16) and 0x8000
        var exp = ((bits ushr 23) and 0xff) - 112
        var mant = bits and 0x7fffff
        if (exp <= 0) {
            if (exp < -10) return sign.toShort()
            mant = mant or 0x800000
            val shift = 14 - exp
            var half = mant shr shift
            val mask = (1 shl shift) - 1
            val rest = mant and mask
            val halfway = 1 shl (shift - 1)
            if (rest > halfway || (rest == halfway && (half and 1) != 0)) half++
            return (sign or half).toShort()
        }
        if (exp >= 31) {
            return (sign or 0x7c00 or (if (mant == 0) 0 else (mant shr 13) or 1)).toShort()
        }
        var half = mant shr 13
        val rest = mant and 0x1fff
        if (rest > 0x1000 || (rest == 0x1000 && (half and 1) != 0)) {
            half++
            if (half == 0x400) {
                half = 0
                exp++
                if (exp >= 31) return (sign or 0x7c00).toShort()
            }
        }
        return (sign or (exp shl 10) or half).toShort()
    }
}
