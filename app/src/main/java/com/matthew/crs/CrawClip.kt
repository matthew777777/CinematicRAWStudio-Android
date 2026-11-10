// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import org.json.JSONObject
import java.io.Closeable

/** Metadata of one take, from the native Clip helper. */
data class ClipInfo(
    val width: Int, val height: Int, val frames: Int, val fps: Double, val rgb: Boolean,
    val camera: String, val iso: Int, val exposureSeconds: Double,
    val audioRate: Int, val audioChannels: Int, val audioBits: Int, val wavSize: Long,
    val recovered: Boolean,
    /** Stable frame codec IDs; display names do not change historical metadata. */
    val codec: Int = 0, val bitDepth: Int = 0,
    val make: String = "", val model: String = "",
    /** AsShotNeutral of frame 0 (camera RGB of white), empty if unknown. */
    val neutral: List<Double> = emptyList(),
) {
    val durationSeconds: Double get() = if (fps > 0) frames / fps else 0.0
    val hasAudio: Boolean get() = audioRate > 0 && audioChannels > 0 && audioBits > 0
    val codecName: String get() = when (codec) { 1 -> "Balanced"; 2 -> "Live v1 — Legacy"; 3 -> "Live"; 4 -> "Compact — Offline"; else -> "" }
}

/** Per-frame levels, white balance and lens shading (see nativeDecodeToBlob). */
class FrameParams(
    val width: Int, val height: Int, val cfa: Int,
    val black: FloatArray, val white: Float, val neutral: FloatArray,
    val shadeColumns: Int, val shadeRows: Int, val shadeGains: FloatArray,
) {
    companion object {
        fun from(a: FloatArray) = FrameParams(
            a[0].toInt(), a[1].toInt(), a[2].toInt(), a.copyOfRange(3, 7), a[7], a.copyOfRange(8, 11),
            a[11].toInt(), a[12].toInt(), a.copyOfRange(13, a.size),
        )
    }
}

/**
 * One open .CRAW take. Opened from a content Uri through its file
 * descriptor, which the native reader memory-maps in place (no copy out of
 * shared storage); the descriptor is closed right after.
 */
class CrawClip private constructor(private var handle: Long, private val context: Context) : Closeable {
    val info: ClipInfo = parseInfo(nativeInfo(handle) ?: error("CRAW info unavailable"))

    val previewSize: Pair<Int, Int> = (info.width/4*2).coerceAtLeast(2) to (info.height/4*2).coerceAtLeast(2)
    fun newPreviewBitmap(): Bitmap = Bitmap.createBitmap(previewSize.first, previewSize.second, Bitmap.Config.ARGB_8888)

    /** Thumbnails use the same new engine as playback and export. */
    fun render(index: Int, bitmap: Bitmap, exposureEv: Float = 0f): Boolean = runCatching {
        com.matthew.crs.rapidraw.RapidMhcSource.fromClip(this, proxy = true, ownsClip = false).use { source ->
            com.matthew.crs.rapidraw.RapidComputeSession(source.width, source.height).use { compute ->
                val metadata = source.upload(index, compute)
                val grade = com.matthew.crs.rapidraw.RapidDefaults.schema.defaults().withValue("exposure", exposureEv.toDouble())
                val rendered = compute.render(grade, metadata)
                try { android.graphics.Canvas(bitmap).drawBitmap(rendered, null, android.graphics.Rect(0,0,bitmap.width,bitmap.height), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)) }
                finally { rendered.recycle() }
            }
        }
        true
    }.getOrElse { android.util.Log.w("CrawClip", "RapidRAW thumbnail failed", it); false }

    /**
     * Decodes Bayer frame [index] into [blob] (RawLens GPU input) and returns
     * [FrameParams]; null for RGB (binned) takes or on error.
     */
    fun decodeToBlob(index: Int, blob: android.hardware.HardwareBuffer, half: Boolean = false, threads: Int = 0): FrameParams? =
        if (handle == 0L) null else nativeDecodeToBlob(handle, index, blob, half, threads)?.let { values ->
            if (PipelineTiming.enabled && values.size >= 16) {
                PipelineTiming.record("decode.native", (values[13] * 1e6).toLong())
                PipelineTiming.record("raw_tools.native", (values[14] * 1e6).toLong())
                PipelineTiming.record("decode.shade_copy", (values[15] * 1e6).toLong())
            }
            // Blob decode already applied shading; diagnostic tail is not gain data.
            FrameParams.from(values.copyOf(13))
        }

    /** XYZ-to-camera matrices (daylight, tungsten). */
    fun camMatrices(): Pair<DoubleArray, DoubleArray>? =
        if (handle == 0L) null else nativeCamMatrices(handle)?.let { it.copyOfRange(0, 9) to it.copyOfRange(9, 18) }

    fun decodeRgb(index: Int, camera: android.hardware.HardwareBuffer): FloatArray? =
        if (handle == 0L) null else nativeDecodeRgb(handle,index,camera)

    /** Original source frame as CinemaDNG, with capture metadata and no development edits. */
    fun writeOriginalDng(index: Int, outputFd: Int): Boolean = handle != 0L && nativeWriteDng(handle, index, outputFd) == 0

    /** Reads take WAV bytes at [offset] into [dst]; returns the count (<= 0 at the end). */
    fun readWav(offset: Long, dst: ByteArray, length: Int = dst.size): Int =
        if (handle == 0L) -1 else nativeReadWav(handle, offset, dst, length)

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            nativeClose(handle)
            handle = 0
        }
    }

    companion object {
        init { System.loadLibrary("crs") }

        fun open(context: Context, uri: Uri): CrawClip? {
            // Unreadable or vanished files show "cannot open" instead of crashing.
            val pfd = try {
                context.contentResolver.openFileDescriptor(SourceAliases.resolve(context, uri), "r")
            } catch (e: Exception) {
                android.util.Log.w("CrawClip", "cannot open $uri: $e")
                null
            } ?: return null
            val h = pfd.use { nativeOpenFd(it.fd, uri.lastPathSegment ?: "take") }
            if (h == 0L) return null
            return try { CrawClip(h, context.applicationContext) } catch (e: Exception) { nativeClose(h); null }
        }

        private fun parseInfo(json: String): ClipInfo = JSONObject(json).run {
            ClipInfo(
                getInt("width"), getInt("height"), getInt("frames"), getDouble("fps"), getBoolean("rgb"),
                getString("camera"), getDouble("iso").toInt(), getDouble("exposure"),
                getInt("audioRate"), getInt("audioChannels"), getInt("audioBits"), getLong("wavSize"),
                getBoolean("recovered"),
                optInt("codec"), optInt("bitDepth"), optString("make"), optString("model"),
                optJSONArray("neutral")?.let { a -> List(a.length()) { a.getDouble(it) } } ?: emptyList(),
            )
        }

        @JvmStatic private external fun nativeOpen(path: String): Long
        @JvmStatic private external fun nativeOpenFd(fd: Int, name: String): Long
        @JvmStatic private external fun nativeClose(handle: Long)
        @JvmStatic private external fun nativeInfo(handle: Long): String?
        @JvmStatic private external fun nativeDecodeToBlob(handle: Long, index: Int, blob: android.hardware.HardwareBuffer, half: Boolean, threads: Int): FloatArray?
        @JvmStatic private external fun nativeDecodeRgb(handle: Long, index: Int, camera: android.hardware.HardwareBuffer): FloatArray?
        @JvmStatic private external fun nativeCamMatrices(handle: Long): DoubleArray?
        @JvmStatic private external fun nativeWriteDng(handle: Long, index: Int, fd: Int): Int
        @JvmStatic private external fun nativeReadWav(handle: Long, offset: Long, dst: ByteArray, length: Int): Int
    }
}
