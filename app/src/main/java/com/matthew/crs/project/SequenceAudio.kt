// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs.project

import android.content.Context
import android.media.*
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/** Disk-backed PCM. Only the window needed for the current audio block is read. */
class WavPcm(file: File) : AutoCloseable {
    private val input = RandomAccessFile(file, "r")
    val rate: Int
    val channels: Int
    private val bits: Int
    private val floating: Boolean
    private val dataOffset: Long
    val frameCount: Long
    init {
        try {
            val header = ByteArray(12); input.readFully(header)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "Not a PCM WAV file" }
            var format: ByteArray? = null; var start = -1L; var size = 0L
            while (input.filePointer + 8 <= input.length()) {
                val tag = ByteArray(4); input.readFully(tag)
                val length = Integer.reverseBytes(input.readInt()).toLong() and 0xffffffffL
                val at = input.filePointer
                when (String(tag, Charsets.US_ASCII)) {
                    "fmt " -> { require(length in 16..4096); format = ByteArray(length.toInt()).also(input::readFully) }
                    "data" -> { start = at; size = minOf(length, input.length() - at) }
                }
                if (format != null && start >= 0) break
                require(at + length + (length and 1) <= input.length()) { "Truncated WAV" }
                input.seek(at + length + (length and 1))
            }
            val fmt = ByteBuffer.wrap(requireNotNull(format) { "Missing WAV format" }).order(ByteOrder.LITTLE_ENDIAN)
            var kind = fmt.getShort(0).toInt() and 0xffff
            if (kind == 0xfffe && fmt.capacity() >= 40) kind = fmt.getShort(24).toInt() and 0xffff
            channels = fmt.getShort(2).toInt(); rate = fmt.getInt(4); bits = fmt.getShort(14).toInt()
            floating = kind == 3
            require(channels in 1..2) { "Use mono or stereo audio" }
            require(rate in 8000..192000 && ((kind == 1 && bits in listOf(8, 16, 24, 32)) || (floating && bits == 32))) { "Unsupported WAV encoding" }
            require(start >= 0 && size > 0) { "WAV has no audio samples" }
            dataOffset = start; frameCount = size / (channels * bits / 8)
        } catch (error: Throwable) { input.close(); throw error }
    }
    fun read(start: Long, frames: Int): FloatArray {
        val out = FloatArray(frames * 2)
        val first = start.coerceAtLeast(0)
        val offset = (first - start).toInt()
        val count = minOf((frameCount - first).coerceAtLeast(0), (frames - offset).coerceAtLeast(0).toLong()).toInt()
        if (count == 0) return out
        val bytes = ByteArray(count * channels * bits / 8)
        input.seek(dataOffset + first * channels * bits / 8); input.readFully(bytes)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun sample(): Float = when {
            floating -> b.float.let { if (it.isFinite()) it else 0f }
            bits == 8 -> ((b.get().toInt() and 255) - 128) / 128f
            bits == 16 -> b.short / 32768f
            bits == 24 -> {
                val value = (b.get().toInt() and 255) or ((b.get().toInt() and 255) shl 8) or (b.get().toInt() shl 16)
                value / 8388608f
            }
            else -> b.int / 2147483648f
        }
        repeat(count) { n ->
            val left = sample(); val right = if (channels == 1) left else sample()
            out[(n + offset) * 2] = left; out[(n + offset) * 2 + 1] = right
        }
        return out
    }
    override fun close() { input.close() }
}

object AudioImport {
    fun decode(context: Context, uri: Uri, destination: File) {
        destination.parentFile!!.mkdirs()
        val temp = File(destination.path + ".part")
        try {
            val wav = context.contentResolver.openInputStream(uri)?.use { input ->
                val head = ByteArray(12); val n = input.read(head)
                n == 12 && String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WAVE"
            } ?: error("Cannot read the audio file. Choose it again to grant access.")
            if (wav) {
                context.contentResolver.openInputStream(uri)!!.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                WavPcm(temp).use { }
            } else decodeCompressed(context, uri, temp)
            check(temp.renameTo(destination)) { "Cannot store imported audio" }
        } finally { temp.delete() }
    }
    private fun decodeCompressed(context: Context, uri: Uri, file: File) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("This file has no supported audio track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            require(mime != "audio/raw") { "Import uncompressed audio as a PCM WAV file" }
            val decoder = MediaCodec.createDecoderByType(mime); codec = decoder
            decoder.configure(format, null, null, 0); decoder.start()
            var inputDone = false; var outputDone = false
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val info = MediaCodec.BufferInfo()
            var lastProgress = android.os.SystemClock.elapsedRealtime()
            RandomAccessFile(file, "rw").use { output ->
                output.setLength(0); output.write(ByteArray(44))
                while (!outputDone) {
                    check(android.os.SystemClock.elapsedRealtime() - lastProgress < 30000) { "Audio decoder stopped responding" }
                    if (!inputDone) {
                        val slot = decoder.dequeueInputBuffer(10000)
                        if (slot >= 0) {
                            val buffer = decoder.getInputBuffer(slot)!!; buffer.clear()
                            val count = extractor.readSampleData(buffer, 0)
                            if (count < 0) { decoder.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                            else { decoder.queueInputBuffer(slot, 0, count, extractor.sampleTime.coerceAtLeast(0), 0); extractor.advance() }
                            lastProgress = android.os.SystemClock.elapsedRealtime()
                        }
                    }
                    val slot = decoder.dequeueOutputBuffer(info, 10000)
                    if (slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = decoder.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) f.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        require(channels in 1..2) { "Use mono or stereo audio" }
                    } else if (slot >= 0) {
                        try {
                            val buffer = decoder.getOutputBuffer(slot)!!.order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            when (encoding) {
                                AudioFormat.ENCODING_PCM_16BIT -> {
                                    val bytes = ByteArray(buffer.remaining()); buffer.get(bytes); output.write(bytes)
                                }
                                AudioFormat.ENCODING_PCM_FLOAT -> {
                                    while (buffer.remaining() >= 4) {
                                        val value = buffer.float.let { if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f }
                                        output.writeShort(java.lang.Short.reverseBytes((value * 32767).toInt().toShort()).toInt())
                                    }
                                }
                                else -> error("Unsupported decoded PCM format")
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            lastProgress = android.os.SystemClock.elapsedRealtime()
                        } finally { decoder.releaseOutputBuffer(slot, false) }
                    }
                }
                require(output.length() > 44) { "Audio file is empty" }
                writeWavHeader(output, rate, channels, output.length() - 44)
            }
        } finally { codec?.let { runCatching { it.stop() }; it.release() }; extractor.release() }
    }
    fun writeWavHeader(file: RandomAccessFile, rate: Int, channels: Int, size: Long) {
        require(size <= 0xffffffffL - 36) { "Audio is too long for WAV" }
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt((size + 36).toInt()); b.put("WAVEfmt ".toByteArray())
        b.putInt(16); b.putShort(1); b.putShort(channels.toShort()); b.putInt(rate)
        b.putInt(rate * channels * 2); b.putShort((channels * 2).toShort()); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(size.toInt()); file.seek(0); file.write(b.array())
    }
}

/** Identical deterministic 48 kHz stereo mix for the preview AudioTrack and AAC export. */
class SequenceAudioMixer(private val context: Context, val project: SequenceProject) : AutoCloseable {
    companion object { const val RATE = 48000; const val CHANNELS = 2 }
    private val schedule = SequenceSchedule(project)
    private val sources = HashMap<String, WavPcm>()
    fun mix(startSample: Long, frames: Int): ShortArray {
        val sum = FloatArray(frames * 2)
        val startUs = startSample * 1_000_000L / RATE
        val endUs = (startSample + frames) * 1_000_000L / RATE + 1
        schedule.audioBetween(startUs, endUs).forEach { span ->
            val source = sources.getOrPut(span.asset.id) { WavPcm(ProjectMedia.ensureAudio(context, span.asset)) }
            val first = maxOf(0, ((span.startUs * RATE + 999999) / 1_000_000 - startSample).toInt())
            val end = minOf(frames, ((span.endUs * RATE + 999999) / 1_000_000 - startSample).toInt())
            if (end <= first) return@forEach
            val sourceFirst = (span.clip.inUs + (startSample + first) * 1_000_000.0 / RATE - span.clip.startUs) * source.rate / 1_000_000.0
            val base = floor(sourceFirst).toLong()
            val needed = ((end - first) * source.rate.toDouble() / RATE).toInt() + 3
            val samples = source.read(base, needed)
            for (n in first until end) {
                val at = sourceFirst - base + (n - first) * source.rate.toDouble() / RATE
                val i = floor(at).toInt(); val fraction = (at - i).toFloat()
                for (channel in 0..1) {
                    val a = samples[i * 2 + channel]; val b = samples[(i + 1) * 2 + channel]
                    sum[n * 2 + channel] += (a + (b - a) * fraction) * span.gain
                }
            }
        }
        return ShortArray(frames * 2) { (sum[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }
    }
    override fun close() { sources.values.forEach { it.close() }; sources.clear() }
}
