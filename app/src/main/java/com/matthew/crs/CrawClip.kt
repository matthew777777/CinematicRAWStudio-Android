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
) {
    val durationSeconds: Double get() = if (fps > 0) frames / fps else 0.0
    val hasAudio: Boolean get() = audioRate > 0 && audioChannels > 0 && audioBits > 0
}

/**
 * One open .CRAW take. Opened from a content Uri through its file
 * descriptor, which the native reader memory-maps in place (no copy out of
 * shared storage); the descriptor is closed right after.
 */
class CrawClip private constructor(private var handle: Long) : Closeable {
    val info: ClipInfo = parseInfo(nativeInfo(handle) ?: error("CRAW info unavailable"))

    /** Size of [render]'s output: half the coded frame size. */
    val previewSize: Pair<Int, Int> = nativePreviewSize(handle)!!.let { it[0] to it[1] }

    fun newPreviewBitmap(): Bitmap =
        Bitmap.createBitmap(previewSize.first, previewSize.second, Bitmap.Config.ARGB_8888)

    /** Develops frame [index] (Rec.709, half resolution) into [bitmap]; false on failure. */
    fun render(index: Int, bitmap: Bitmap, exposureEv: Float = 0f): Boolean {
        if (handle == 0L) return false
        val code = nativeRender(handle, index, bitmap, exposureEv)
        if (code != 0) android.util.Log.w("CrawClip", "render $index failed: code $code")
        return code == 0
    }

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
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (e: Exception) {
                android.util.Log.w("CrawClip", "cannot open $uri: $e")
                null
            } ?: return null
            val h = pfd.use { nativeOpenFd(it.fd, uri.lastPathSegment ?: "take") }
            if (h == 0L) return null
            return try { CrawClip(h) } catch (e: Exception) { nativeClose(h); null }
        }

        private fun parseInfo(json: String): ClipInfo = JSONObject(json).run {
            ClipInfo(
                getInt("width"), getInt("height"), getInt("frames"), getDouble("fps"), getBoolean("rgb"),
                getString("camera"), getDouble("iso").toInt(), getDouble("exposure"),
                getInt("audioRate"), getInt("audioChannels"), getInt("audioBits"), getLong("wavSize"),
                getBoolean("recovered"),
            )
        }

        @JvmStatic private external fun nativeOpen(path: String): Long
        @JvmStatic private external fun nativeOpenFd(fd: Int, name: String): Long
        @JvmStatic private external fun nativeClose(handle: Long)
        @JvmStatic private external fun nativeInfo(handle: Long): String?
        @JvmStatic private external fun nativePreviewSize(handle: Long): IntArray?
        @JvmStatic private external fun nativeRender(handle: Long, index: Int, bitmap: Bitmap, exposureEv: Float): Int
        @JvmStatic private external fun nativeReadWav(handle: Long, offset: Long, dst: ByteArray, length: Int): Int
    }
}
