// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plays a take's audio (the WAV the native Clip builds from its AUDI chunks)
 * and is the playback master clock: picture follows [positionSeconds].
 * PCM16 plays as-is; 24-bit converts to float.
 */
class TakeAudio(private val clip: CrawClip) {
    private val info = clip.info
    private val bytesPerSample = info.audioBits / 8
    private val frameBytes = bytesPerSample * info.audioChannels
    private val dataOffset: Long = findDataOffset()
    private var track: AudioTrack? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var startSeconds = 0.0

    /** Seconds of audio played since [play]'s start position. */
    val positionSeconds: Double
        get() {
            val t = track ?: return startSeconds
            return startSeconds + (t.playbackHeadPosition.toLong() and 0xffffffffL).toDouble() / info.audioRate
        }

    fun play(fromSeconds: Double) {
        stop()
        startSeconds = fromSeconds
        val float = bytesPerSample == 3
        val encoding = if (float) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (info.audioChannels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val min = AudioTrack.getMinBufferSize(info.audioRate, channelMask, encoding)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(info.audioRate).setEncoding(encoding)
                .setChannelMask(channelMask).build())
            .setBufferSizeInBytes(maxOf(min * 2, 32768))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        running = true
        var offset = dataOffset + (fromSeconds * info.audioRate).toLong() * frameBytes
        thread = Thread({
            val buf = ByteArray(frameBytes * 4096)
            t.play()
            while (running) {
                val n = clip.readWav(offset, buf)
                if (n <= 0) break
                val whole = n - n % frameBytes
                offset += whole
                if (float) {
                    val samples = whole / 3
                    val out = FloatArray(samples)
                    for (i in 0 until samples) {
                        val b = i * 3
                        val v = (buf[b].toInt() and 0xff) or ((buf[b + 1].toInt() and 0xff) shl 8) or (buf[b + 2].toInt() shl 16)
                        out[i] = v / 8388608f
                    }
                    t.write(out, 0, samples, AudioTrack.WRITE_BLOCKING)
                } else {
                    t.write(buf, 0, whole, AudioTrack.WRITE_BLOCKING)
                }
            }
        }, "TakeAudio").apply { start() }
    }

    fun stop() {
        running = false
        track?.let { runCatching { it.pause(); it.flush() } }
        thread?.join(500)
        thread = null
        track?.release()
        track = null
    }

    /** Offset of the WAV "data" chunk payload. */
    private fun findDataOffset(): Long {
        val head = ByteArray(4096)
        val n = clip.readWav(0, head)
        val bb = ByteBuffer.wrap(head, 0, maxOf(n, 0)).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        while (pos + 8 <= n) {
            val id = String(head, pos, 4, Charsets.US_ASCII)
            val size = bb.getInt(pos + 4).toLong() and 0xffffffffL
            if (id == "data") return pos + 8L
            pos += 8 + size.toInt() + (size.toInt() and 1)
        }
        return 44
    }
}
