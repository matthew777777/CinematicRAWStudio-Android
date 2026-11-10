// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs.project

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.provider.OpenableColumns
import com.matthew.crs.*
import java.io.File

object ProjectMedia {
    fun name(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Media"
    fun audioFile(context: Context, asset: SourceAsset) = File(context.filesDir, "project-audio/${asset.id}.wav")
    fun importRaw(context: Context, uri: Uri): SourceAsset {
        val name = name(context, uri)
        require(name.endsWith(".craw", true)) { "Choose a .craw take" }
        return CrawClip.open(context, uri)?.use { clip ->
            val i = clip.info
            require(i.frames > 0 && i.fps > 0) { "This take has no readable frames" }
            val asset = SourceAsset(uri = uri.toString(), name = name, kind = AssetKind.CRAW,
                durationUs = (i.frames * 1_000_000.0 / i.fps).toLong(), width = i.width, height = i.height,
                frames = i.frames, fps = i.fps, hasAudio = i.hasAudio, rgb = i.rgb)
            if (i.hasAudio) {
                val file = audioFile(context, asset); file.parentFile!!.mkdirs()
                val temp = File(file.path + ".part")
                try {
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(65536); var offset = 0L
                        while (offset < i.wavSize) {
                            val n = clip.readWav(offset, buffer, minOf(buffer.size.toLong(), i.wavSize - offset).toInt())
                            check(n > 0) { "Cannot read camera audio" }
                            output.write(buffer, 0, n); offset += n
                        }
                    }
                    WavPcm(temp).use { }
                    check(temp.renameTo(file)) { "Cannot store camera audio" }
                } finally { temp.delete() }
                asset.copy(audioFile = file.name)
            } else asset
        } ?: error("Cannot open $name. Choose the source again to grant access.")
    }
    fun importAudio(context: Context, uri: Uri): SourceAsset {
        val asset = SourceAsset(uri = uri.toString(), name = name(context, uri), kind = AssetKind.AUDIO,
            durationUs = 1, hasAudio = true)
        val file = audioFile(context, asset)
        AudioImport.decode(context, uri, file)
        return WavPcm(file).use { asset.copy(durationUs = it.frameCount * 1_000_000L / it.rate, audioFile = file.name) }
    }
    fun ensureAudio(context: Context, asset: SourceAsset): File {
        val file = audioFile(context, asset)
        if (file.exists()) return file
        if (asset.kind == AssetKind.AUDIO) AudioImport.decode(context, Uri.parse(asset.uri), file)
        else CrawClip.open(context, Uri.parse(asset.uri))?.use { clip ->
            file.parentFile!!.mkdirs()
            val temp = File(file.path + ".part")
            try {
                temp.outputStream().use { output ->
                    val buf = ByteArray(65536); var at = 0L
                    while (at < clip.info.wavSize) {
                        val n = clip.readWav(at, buf, minOf(buf.size.toLong(), clip.info.wavSize - at).toInt())
                        check(n > 0) { "Cannot restore audio from ${asset.name}" }; output.write(buf, 0, n); at += n
                    }
                }
                WavPcm(temp).use { }
                check(temp.renameTo(file))
            } finally { temp.delete() }
        } ?: error("Missing source: ${asset.name}. Relink it in the clip inspector.")
        return file
    }
}

/** One RapidRAW Vulkan engine for scheduled playback and full-resolution exports. */
class ProjectFrameRenderer(private val context: Context, private val proxy: Boolean = false) : AutoCloseable {
    private var assetId: String? = null
    private var source: com.matthew.crs.rapidraw.RapidMhcSource? = null
    private var compute: com.matthew.crs.rapidraw.RapidComputeSession? = null
    private var metadata: com.matthew.crs.rapidraw.RapidSourceMetadata? = null
    private var outputs = emptyArray<Bitmap>()
    private var next = 0
    private data class CachedFrame(val bitmap: Bitmap, val metadata: com.matthew.crs.rapidraw.RapidSourceMetadata)
    private val cache = object : LinkedHashMap<String, CachedFrame>(16, .75f, true) {}
    private var cachedGrade: String? = null
    private var cachedGradeHash = ""
    private fun gradeHash(encoded: String): String {
        if(cachedGrade != encoded) {
            cachedGrade = encoded
            cachedGradeHash = java.security.MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
        return cachedGradeHash
    }
    private var cacheBytes = 0L
    private val cacheLimit = 64L * 1024 * 1024
    private fun cacheFrame(key: String, bitmap: Bitmap, sourceMetadata: com.matthew.crs.rapidraw.RapidSourceMetadata) {
        if (!proxy || bitmap.allocationByteCount > cacheLimit) return
        cache[key] = CachedFrame(bitmap, sourceMetadata); cacheBytes += bitmap.allocationByteCount
        while (cacheBytes > cacheLimit && cache.isNotEmpty()) {
            val entry = cache.entries.iterator().next()
            cacheBytes -= entry.value.bitmap.allocationByteCount
            entry.value.bitmap.recycle(); cache.remove(entry.key)
        }
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private fun prepare(sample: VideoSample) {
        check(sample.clip.legacyGrade == null) { "${sample.asset.name} has a legacy grade. Reset its grade to render with RapidRAW." }
        if (assetId != sample.asset.id) {
            closeSource()
            val opened = com.matthew.crs.rapidraw.RapidMhcSource.open(context, Uri.parse(sample.asset.uri), proxy)
            source = opened
            compute = com.matthew.crs.rapidraw.RapidComputeSession(opened.width, opened.height)
            assetId = sample.asset.id
        }
        metadata = source!!.upload(sample.sourceFrame, compute!!)
    }
    fun render(project: SequenceProject, frame: Long): Bitmap {
        val monitorScale = if (proxy) minOf(1.0, 1280.0 / maxOf(project.width, project.height)) else 1.0
        val monitorWidth = (project.width * monitorScale).toInt().coerceAtLeast(2)
        val monitorHeight = (project.height * monitorScale).toInt().coerceAtLeast(2)
        if (outputs.firstOrNull()?.width != monitorWidth || outputs.firstOrNull()?.height != monitorHeight) {
            // A published monitor bitmap may still be referenced by Compose.
            outputs = Array(2) { Bitmap.createBitmap(monitorWidth, monitorHeight, Bitmap.Config.ARGB_8888) }
        }
        val out = outputs[next]; next = 1 - next
        val canvas = Canvas(out); canvas.drawColor(Color.BLACK)
        val sample = SequenceSchedule(project).videoAt(project.rate.time(frame)) ?: return out
        check(sample.clip.legacyGrade == null) { "Reset this clip's legacy grade before rendering" }
        val key = "${sample.asset.uri}:${sample.sourceFrame}:${gradeHash(sample.clip.grade.encoded)}"
        val cached = if (proxy) cache[key] else null
        val bitmap = if (cached != null) { metadata = cached.metadata; cached.bitmap } else {
            prepare(sample)
            compute!!.render(sample.clip.grade.grade(), metadata!!).also { cacheFrame(key, it, metadata!!) }
        }
        try {
            val scale = minOf(out.width.toFloat() / bitmap.width, out.height.toFloat() / bitmap.height)
            val width = bitmap.width * scale; val height = bitmap.height * scale
            canvas.drawBitmap(bitmap, null, RectF((out.width-width)/2, (out.height-height)/2,
                (out.width+width)/2, (out.height+height)/2), paint)
        } finally { if (cached == null && (!proxy || cache[key]?.bitmap !== bitmap)) bitmap.recycle() }
        return out
    }
    fun renderP010(project: SequenceProject, frame: Long, image: android.media.Image) {
        val sample = SequenceSchedule(project).videoAt(project.rate.time(frame))
        if (sample == null) {
            check(P010Output.black(image, project.width, project.height)) { "Encoder did not expose writable P010 planes" }
            return
        }
        prepare(sample)
        compute!!.writeP010(sample.clip.grade.grade(), metadata!!, image, project.width, project.height)
    }
    val asShotKelvin: Double? get() = metadata?.asShotTemperature
    private fun closeSource() {
        compute?.close(); compute = null
        source?.close(); source = null
        metadata = null; assetId = null
    }
    override fun close() { closeSource(); cache.values.forEach { it.bitmap.recycle() }; cache.clear(); cacheBytes = 0; outputs = emptyArray() }
}

object P010Output {
    init { System.loadLibrary("crs") }
    fun black(image: android.media.Image, width: Int, height: Int): Boolean = nativeBlack(image, width, height) == 0
    @JvmStatic private external fun nativeBlack(image: android.media.Image, width: Int, height: Int): Int
}
