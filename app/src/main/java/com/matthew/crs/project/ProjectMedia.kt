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

/** Render the same scheduled, graded source frame for the monitor and the encoder. */
class ProjectFrameRenderer(private val context: Context) : AutoCloseable {
    private var assetId: String? = null
    private var clip: CrawClip? = null
    private var gpu: GpuFrameRenderer? = null
    private var grade: MlvGrade? = null
    private var cpu: Bitmap? = null
    private var outputs = emptyArray<Bitmap>()
    private var next = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    fun render(project: SequenceProject, frame: Long): Bitmap {
        if (outputs.firstOrNull()?.width != project.width || outputs.firstOrNull()?.height != project.height) {
            // Published bitmaps are left to GC; Compose may still be drawing the last one.
            outputs = Array(2) { Bitmap.createBitmap(project.width, project.height, Bitmap.Config.ARGB_8888) }
        }
        val out = outputs[next]; next = 1 - next
        val canvas = Canvas(out); canvas.drawColor(Color.BLACK)
        val sample = SequenceSchedule(project).videoAt(project.rate.time(frame)) ?: return out
        val source = open(sample)
        val g = gpu; val developer = grade
        val bitmap = if (g != null && developer != null) {
            g.renderGraded(sample.sourceFrame, developer) { sample.clip.receipt }
        } else {
            require(sample.clip.receipt == Receipt()) { "RAW grading requires the GPU pipeline for ${sample.asset.name}" }
            cpu!!.takeIf { source.render(sample.sourceFrame, it) }
        } ?: error("Cannot render frame ${sample.sourceFrame + 1} of ${sample.asset.name}")
        val scale = minOf(project.width.toFloat() / bitmap.width, project.height.toFloat() / bitmap.height)
        val width = bitmap.width * scale; val height = bitmap.height * scale
        canvas.drawBitmap(bitmap, null, RectF((project.width - width) / 2, (project.height - height) / 2,
            (project.width + width) / 2, (project.height + height) / 2), paint)
        return out
    }
    private fun open(sample: VideoSample): CrawClip {
        if (assetId != sample.asset.id) {
            closeSource()
            val source = CrawClip.open(context, Uri.parse(sample.asset.uri))
                ?: error("Missing source: ${sample.asset.name}. Relink it in the clip inspector.")
            clip = source
            assetId = sample.asset.id
            if (!source.info.rgb && GpuPipeline.init(context)) {
                gpu = GpuFrameRenderer(source, source.info.width, source.info.height)
                grade = MlvGrade(source)
            } else cpu = source.newPreviewBitmap()
        }
        return clip!!.also { it.setRawTools(sample.clip.receipt.rawToolValues()) }
    }
    /** Processed RGB16 straight to the encoder's P010 planes, never through the ARGB preview. */
    fun renderP010(project: SequenceProject, frame: Long, image: android.media.Image) {
        val sample = SequenceSchedule(project).videoAt(project.rate.time(frame))
        if (sample == null) {
            check(P010Output.black(image, project.width, project.height)) { "Encoder did not expose writable P010 planes" }
            return
        }
        val source = open(sample)
        val g = gpu; val developer = grade
        val ok = if (g != null && developer != null) {
            g.writeGradedP010(sample.sourceFrame, developer, { sample.clip.receipt }, image, project.width, project.height)
        } else {
            render(project, frame)
            source.writeDevelopedP010(image, project.width, project.height)
        }
        check(ok) { "Cannot write frame ${sample.sourceFrame + 1} of ${sample.asset.name} to the encoder’s P010 planes" }
    }
    /** Vulkan grade vs MLV-App's CPU code on one frame (parity tests). */
    fun gpuParity(project: SequenceProject, frame: Long): LongArray? {
        val sample = SequenceSchedule(project).videoAt(project.rate.time(frame)) ?: return null
        open(sample)
        return gpu?.gpuParity(sample.sourceFrame, grade ?: return null) { sample.clip.receipt }
    }
    val asShotKelvin: Double? get() = grade?.asShot?.first
    private fun closeSource() {
        gpu?.close(); gpu = null
        grade?.close(); grade = null
        cpu?.recycle(); cpu = null
        clip?.close(); clip = null
        assetId = null
    }
    override fun close() { closeSource(); outputs = emptyArray() }
}

object P010Output {
    init { System.loadLibrary("crs") }
    fun black(image: android.media.Image, width: Int, height: Int): Boolean = nativeBlack(image, width, height) == 0
    @JvmStatic private external fun nativeBlack(image: android.media.Image, width: Int, height: Int): Int
}
