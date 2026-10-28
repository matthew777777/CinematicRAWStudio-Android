// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs.project

import android.content.Context
import android.graphics.Bitmap
import android.media.*
import android.net.Uri
import android.opengl.*
import android.view.Surface
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

enum class ExportVideoCodec(val label: String, val mime: String, val tenBit: Boolean = false) {
    AVC("H.264 / AVC", "video/avc"), HEVC("HEVC · 8-bit", "video/hevc"),
    HEVC10("HEVC Main10 · 10-bit", "video/hevc", true)
}
enum class ExportOutput { MP4, ORIGINAL_CINEMA_DNG }

data class ExportSettings(val codec: ExportVideoCodec = ExportVideoCodec.AVC,
    val height: Int = 0, val bitrateMbps: Int = 24, val audio: Boolean = true, val output: ExportOutput = ExportOutput.MP4) {
    fun output(project: SequenceProject): SequenceProject {
        val h = if (height == 0) project.height else height
        val w = ((project.width.toDouble() / project.height * h / 2).toInt() * 2).coerceAtLeast(16)
        return project.copy(width = w, height = h).validated()
    }
    fun format(project: SequenceProject): MediaFormat {
        val out = output(project)
        return MediaFormat.createVideoFormat(codec.mime, out.width, out.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, if (codec.tenBit) MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010 else MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            if (codec.tenBit) setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateMbps * 1_000_000)
            setFloat(MediaFormat.KEY_FRAME_RATE, out.rate.fps.toFloat())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
    }
    fun encoder(project: SequenceProject): String? = runCatching {
        if (!codec.tenBit) MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format(project))
        else {
            val out = output(project)
            // Some MTK encoders under-report Main10. Configuration plus SPS verification is the final gate.
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder && it.isHardwareAccelerated && it.supportedTypes.any { t -> t.equals(codec.mime, true) } }
                .firstOrNull { runCatching { it.getCapabilitiesForType(codec.mime).videoCapabilities?.areSizeAndRateSupported(out.width, out.height, out.rate.fps) == true }.getOrDefault(false) }?.name
        }
    }.getOrNull()
}

object SequenceExporter {
    suspend fun export(context: Context, snapshot: SequenceProject, settings: ExportSettings, destination: Uri,
        progress: (Float, String) -> Unit) = withContext(Dispatchers.IO) {
        snapshot.validated(); require(snapshot.clips.isNotEmpty()) { "Add clips before exporting" }
        if (settings.output == ExportOutput.ORIGINAL_CINEMA_DNG) {
            OriginalDngExporter.export(context, snapshot, destination, progress)
            return@withContext
        }
        val project = settings.output(snapshot)
        val encoder = settings.encoder(snapshot) ?: error("This device cannot encode these settings. Choose another codec, resolution, or bitrate.")
        val folder = File(context.cacheDir, "sequence-export-${UUID.randomUUID()}").apply { mkdirs() }
        val video = File(folder, "video.mp4"); val audio = File(folder, "audio.m4a")
        try {
            if (settings.codec.tenBit) encodeVideo10(context, project, settings, encoder, video) { progress(it * 0.85f, "Rendering 10-bit edited frames") }
            else encodeVideo(context, project, settings, encoder, video) { progress(it * 0.85f, "Rendering edited frames") }
            currentCoroutineContext().ensureActive()
            val withAudio = settings.audio && SequenceSchedule(project).audioBetween(0, project.durationUs).isNotEmpty()
            val delay = if (withAudio) encodeAudio(context, project, audio) { progress(0.85f + it * 0.12f, "Mixing audio tracks") } else 0
            currentCoroutineContext().ensureActive()
            progress(0.98f, "Writing MP4")
            val completed = File(folder, "completed.mp4")
            mux(video, audio.takeIf { withAudio }, completed)
            if (withAudio) Mp4Timing.trimAac(completed, delay, project.durationUs)
            context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                completed.inputStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer); if (n < 0) break
                        output.write(buffer, 0, n)
                    }
                }
            } ?: error("Cannot write the export destination")
            currentCoroutineContext().ensureActive()
            progress(1f, "Export complete")
        } catch (failure: Throwable) {
            // The destination is a newly created document, never an existing source.
            runCatching { context.contentResolver.openFileDescriptor(destination, "rwt")?.close() }
            throw failure
        } finally { folder.listFiles()?.forEach { it.delete() }; folder.delete() }
    }
    private suspend fun encodeVideo10(context: Context, project: SequenceProject, settings: ExportSettings,
        name: String, file: File, progress: (Float) -> Unit) {
        val codec = MediaCodec.createByCodecName(name)
        var sink: CodecSink? = null
        try {
            codec.configure(settings.format(project), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val mux = CodecSink(codec, file, requireTenBit = true); sink = mux
            coroutineScope {
                val drained = async(Dispatchers.IO) { mux.drain(true) }
                try {
                    ProjectFrameRenderer(context).use { renderer ->
                        var lastProgress = android.os.SystemClock.elapsedRealtime()
                        for (frame in 0 until project.frameCount) {
                            currentCoroutineContext().ensureActive()
                            var slot = -1
                            while (slot < 0) {
                                currentCoroutineContext().ensureActive()
                                slot = codec.dequeueInputBuffer(10000)
                                check(android.os.SystemClock.elapsedRealtime() - lastProgress < 30000) { "Main10 input stopped responding" }
                            }
                            // Query capacity before getInputImage, which owns this input slot on C2.
                            val capacity = codec.getInputBuffer(slot)?.capacity() ?: error("Main10 encoder has no input buffer")
                            val image = codec.getInputImage(slot) ?: error("Main10 encoder did not expose writable P010 images")
                            try {
                                renderer.renderP010(project, frame, image)
                                codec.queueInputBuffer(slot, 0, capacity, project.rate.time(frame), 0)
                            } finally { runCatching { image.close() } }
                            lastProgress = android.os.SystemClock.elapsedRealtime()
                            progress((frame + 1).toFloat() / project.frameCount)
                        }
                        var slot = -1
                        while (slot < 0) {
                            currentCoroutineContext().ensureActive(); slot = codec.dequeueInputBuffer(10000)
                            check(android.os.SystemClock.elapsedRealtime() - lastProgress < 30000) { "Main10 encoder did not accept end of stream" }
                        }
                        codec.queueInputBuffer(slot, 0, 0, project.rate.time(project.frameCount), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    }
                    drained.await()
                } finally { withContext(NonCancellable) { drained.cancelAndJoin() } }
            }
        } finally { runCatching { codec.stop() }; codec.release(); sink?.close() }
    }

    private suspend fun encodeVideo(context: Context, project: SequenceProject, settings: ExportSettings,
        name: String, file: File, progress: (Float) -> Unit) {
        val codec = MediaCodec.createByCodecName(name)
        var encoderSurface: EncoderSurface? = null
        var sink: CodecSink? = null
        try {
            codec.configure(settings.format(project), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            encoderSurface = EncoderSurface(surface, project.width, project.height)
            codec.start()
            val mux = CodecSink(codec, file); sink = mux
            coroutineScope {
                val finished = java.util.concurrent.atomic.AtomicBoolean(false)
                // Stop the codec from a separate thread on cancellation to unblock a full input surface.
                val cancellationGuard = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() }
                    finally { if (!finished.get()) runCatching { codec.stop() } }
                }
                val drained = async(Dispatchers.IO) { mux.drain(true) }
                try {
                    ProjectFrameRenderer(context).use { renderer ->
                        for (frame in 0 until project.frameCount) {
                            currentCoroutineContext().ensureActive()
                            val bitmap = renderer.render(project, frame)
                            encoderSurface.draw(bitmap, project.rate.time(frame) * 1000)
                            progress((frame + 1).toFloat() / project.frameCount)
                        }
                    }
                    codec.signalEndOfInputStream()
                    encoderSurface.detach()
                    drained.await()
                    finished.set(true)
                } finally {
                    encoderSurface.detach()
                    withContext(NonCancellable) { cancellationGuard.cancelAndJoin(); drained.cancelAndJoin() }
                }
            }
        } finally {
            runCatching { codec.stop() }; codec.release()
            encoderSurface?.close(); sink?.close()
        }
    }
    private suspend fun encodeAudio(context: Context, project: SequenceProject, file: File, progress: (Float) -> Unit): Int {
        val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
        var sink: CodecSink? = null
        try {
            val delay = AacDelay.samples(context, codec.name)
            val format = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
            val mux = CodecSink(codec, file); sink = mux
            SequenceAudioMixer(context, project).use { mixer ->
                val total = (project.durationUs * 48000 + 999999) / 1_000_000
                var sample = 0L; var lastProgress = android.os.SystemClock.elapsedRealtime()
                while (sample < total) {
                    currentCoroutineContext().ensureActive()
                    val slot = codec.dequeueInputBuffer(10000)
                    if (slot >= 0) {
                        val buffer = codec.getInputBuffer(slot)!!.order(ByteOrder.LITTLE_ENDIAN); buffer.clear()
                        val frames = minOf(2048L, total - sample, buffer.remaining().toLong() / 4).toInt()
                        check(frames > 0) { "AAC input buffer is empty" }
                        mixer.mix(sample, frames).forEach { buffer.putShort(it) }
                        codec.queueInputBuffer(slot, 0, frames * 4, sample * 1_000_000L / 48000, 0)
                        sample += frames; lastProgress = android.os.SystemClock.elapsedRealtime()
                        progress(sample.toFloat() / total)
                    }
                    mux.drain(false)
                    check(android.os.SystemClock.elapsedRealtime() - lastProgress < 30000) { "Audio encoder stopped responding" }
                }
                var queuedEos = false
                while (!queuedEos) {
                    currentCoroutineContext().ensureActive()
                    val slot = codec.dequeueInputBuffer(10000)
                    if (slot >= 0) { codec.queueInputBuffer(slot, 0, 0, sample * 1_000_000L / 48000, MediaCodec.BUFFER_FLAG_END_OF_STREAM); queuedEos = true }
                    mux.drain(false)
                    check(android.os.SystemClock.elapsedRealtime() - lastProgress < 30000) { "Audio encoder did not accept end of stream" }
                }
                mux.drain(true)
            }
            return delay
        } finally { runCatching { codec.stop() }; codec.release(); sink?.close() }
    }
    private suspend fun mux(video: File, audio: File?, destination: File) {
        val inputs = listOfNotNull(video, audio).map { file -> MediaExtractor().also { it.setDataSource(file.path); it.selectTrack(0) } }
        val muxer = MediaMuxer(destination.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            val tracks = inputs.map { muxer.addTrack(it.getTrackFormat(0)) }
            muxer.start(); started = true
            val buffer = ByteBuffer.allocateDirect(16 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                currentCoroutineContext().ensureActive()
                val next = inputs.indices.filter { inputs[it].sampleTime >= 0 }.minByOrNull { inputs[it].sampleTime } ?: break
                val extractor = inputs[next]; buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(tracks[next], buffer, info); extractor.advance()
            }
            muxer.stop(); started = false
        } finally {
            if (started) runCatching { muxer.stop() }
            muxer.release(); inputs.forEach { it.release() }
        }
    }
}

internal class CodecSink(private val codec: MediaCodec, file: File, private val requireTenBit: Boolean = false) : AutoCloseable {
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var started = false
    private var track = -1
    private var ended = false
    suspend fun drain(eos: Boolean) {
        val info = MediaCodec.BufferInfo()
        var lastProgress = android.os.SystemClock.elapsedRealtime()
        while (!ended) {
            currentCoroutineContext().ensureActive()
            val slot = codec.dequeueOutputBuffer(info, if (eos) 10000 else 0)
            when {
                slot == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!eos) return
                    check(android.os.SystemClock.elapsedRealtime() - lastProgress < 30000) { "Encoder did not finish" }
                }
                slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    check(!started)
                    val format = codec.outputFormat
                    if (requireTenBit) {
                        val csd = format.getByteBuffer("csd-0")?.duplicate() ?: error("Main10 output configuration is missing")
                        val bytes = ByteArray(csd.remaining()); csd.get(bytes)
                        val depths = HevcBitstream.bitDepth(bytes)
                        check(depths.first == 10 && depths.second == 10) { "Encoder output is ${depths.first}/${depths.second}-bit; refusing an 8-bit fallback" }
                        android.util.Log.i("CRSExport", "Verified HEVC SPS: 10-bit luma and chroma")
                    }
                    track = muxer.addTrack(format); muxer.start(); started = true
                }
                slot >= 0 -> {
                    try {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0) {
                            check(started) { "Encoder output format is missing" }
                            val buffer = codec.getOutputBuffer(slot)!!
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            muxer.writeSampleData(track, buffer, info)
                        }
                        ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        lastProgress = android.os.SystemClock.elapsedRealtime()
                    } finally { codec.releaseOutputBuffer(slot, false) }
                }
            }
        }
    }
    override fun close() { if (started) runCatching { muxer.stop() }; muxer.release() }
}

/** Surface input avoids ambiguous vendor-specific YUV byte layouts. */
private class EncoderSurface(private val surface: Surface, private val width: Int, private val height: Int) : AutoCloseable {
    private val display: android.opengl.EGLDisplay
    private val context: android.opengl.EGLContext
    private val window: android.opengl.EGLSurface
    private val program: Int
    private val texture: Int
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,0f,1f, 1f,-1f,1f,1f, -1f,1f,0f,0f, 1f,1f,1f,0f)); position(0)
    }
    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
            EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,0x3142,1,EGL14.EGL_NONE),0,configs,0,1,count,0) && count[0] > 0)
        context = EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        window = EGL14.eglCreateWindowSurface(display,configs[0],surface,intArrayOf(EGL14.EGL_NONE),0)
        check(context != EGL14.EGL_NO_CONTEXT && window != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display,window,window,context))
        fun shader(type: Int, source: String): Int {
            val s=GLES20.glCreateShader(type); GLES20.glShaderSource(s,source); GLES20.glCompileShader(s)
            val status=IntArray(1); GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,status,0)
            check(status[0] != 0) { GLES20.glGetShaderInfoLog(s) }; return s
        }
        val v=shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 p; attribute vec2 t; varying vec2 uv; void main(){gl_Position=vec4(p,0.,1.);uv=t;}")
        val f=shader(GLES20.GL_FRAGMENT_SHADER,"precision mediump float; varying vec2 uv; uniform sampler2D image; void main(){gl_FragColor=texture2D(image,uv);}")
        program=GLES20.glCreateProgram(); GLES20.glAttachShader(program,v); GLES20.glAttachShader(program,f); GLES20.glLinkProgram(program)
        val status=IntArray(1); GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,status,0); check(status[0] != 0) { GLES20.glGetProgramInfoLog(program) }
        GLES20.glDeleteShader(v); GLES20.glDeleteShader(f)
        val textures=IntArray(1); GLES20.glGenTextures(1,textures,0); texture=textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
    }
    fun draw(bitmap: Bitmap, timeNs: Long) {
        GLES20.glViewport(0,0,width,height); GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"image"),0)
        val p=GLES20.glGetAttribLocation(program,"p");val t=GLES20.glGetAttribLocation(program,"t")
        vertices.position(0);GLES20.glVertexAttribPointer(p,2,GLES20.GL_FLOAT,false,16,vertices);GLES20.glEnableVertexAttribArray(p)
        vertices.position(2);GLES20.glVertexAttribPointer(t,2,GLES20.GL_FLOAT,false,16,vertices);GLES20.glEnableVertexAttribArray(t)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Encoder surface draw failed" }
        check(EGLExt.eglPresentationTimeANDROID(display,window,timeNs))
        check(EGL14.eglSwapBuffers(display,window)) { "Encoder surface submission failed" }
    }
    fun detach() { EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT) }
    override fun close() {
        EGL14.eglMakeCurrent(display,window,window,context)
        GLES20.glDeleteTextures(1,intArrayOf(texture),0);GLES20.glDeleteProgram(program)
        EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display,window);EGL14.eglDestroyContext(display,context)
        // The EGL display can be shared with the preview; do not globally terminate it.
        EGL14.eglReleaseThread();surface.release()
    }
}
