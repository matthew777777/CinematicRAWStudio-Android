// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs.project

import android.content.Context
import android.graphics.Bitmap
import android.media.*
import androidx.compose.runtime.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** One serial owner for RAW/GPU resources; video follows the mixed audio output clock. */
class SequencePlayer(private val context: Context, initial: SequenceProject) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "SequenceVideo") }
    private val generation = AtomicLong()
    private val renderer = ProjectFrameRenderer(context)
    @Volatile private var current = initial
    @Volatile private var sound: SequenceSound? = null
    @Volatile private var closed = false
    var frame by mutableStateOf<Bitmap?>(null); private set
    var index by mutableLongStateOf(0); private set
    var playing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var asShotKelvin by mutableStateOf<Double?>(null); private set

    fun update(project: SequenceProject) {
        pause(); current = project; seek(index)
    }
    fun seek(requested: Long) {
        if (closed) return
        pause()
        val project = current
        val target = requested.coerceIn(0, (project.frameCount - 1).coerceAtLeast(0))
        index = target; busy = true; error = null
        val token = generation.incrementAndGet()
        executor.execute {
            if (generation.get() != token || closed) return@execute
            try {
                val rendered = renderer.render(project, target)
                if (generation.get() == token) { frame = rendered; asShotKelvin = renderer.asShotKelvin }
            } catch (failure: Exception) { if (generation.get() == token) error = failure.message ?: "Preview failed" }
            finally { if (generation.get() == token) busy = false }
        }
    }
    fun play() {
        if (ExportQueue.running) { error = "Playback is paused while an export uses the RAW pipeline"; return }
        if (closed || playing || current.frameCount == 0L) return
        val project = current
        val start = if (index >= project.frameCount - 1) 0L else index
        val token = generation.incrementAndGet()
        playing = true; error = null
        executor.execute {
            if (generation.get() != token || closed) return@execute
            val audio = SequenceSound(context, project, project.rate.time(start)); sound = audio
            try {
                audio.start()
                var last = -1L
                while (!closed && generation.get() == token) {
                    audio.failure?.let { throw IllegalStateException(it) }
                    val time = audio.positionUs
                    if (time >= project.durationUs) break
                    val due = project.rate.frame(time).coerceAtMost(project.frameCount - 1)
                    if (due != last) {
                        val rendered = renderer.render(project, due)
                        if (generation.get() != token) break
                        frame = rendered; index = due; last = due; busy = false
                        asShotKelvin = renderer.asShotKelvin
                    } else Thread.sleep(2)
                }
            } catch (failure: Exception) { if (generation.get() == token) error = failure.message ?: "Sequence playback failed" }
            finally {
                audio.stop()
                if (sound === audio) sound = null
                if (generation.get() == token) { playing = false; busy = false }
            }
        }
    }
    fun pause() {
        generation.incrementAndGet()
        val audio = sound
        if (playing && audio != null) index = current.rate.frame(audio.positionUs).coerceIn(0, (current.frameCount - 1).coerceAtLeast(0))
        playing = false; audio?.stop(); busy = false
    }
    override fun close() {
        if (closed) return
        closed = true; pause()
        executor.execute { renderer.close() }; executor.shutdown()
    }
}

private class SequenceSound(private val context: Context, private val project: SequenceProject, private val startUs: Long) {
    @Volatile private var track: AudioTrack? = null
    @Volatile private var running = false
    @Volatile var failure: String? = null; private set
    private var thread: Thread? = null
    private val stamp = AudioTimestamp()
    @Volatile private var queued = 0L
    val positionUs: Long get() {
        val output = track ?: return startUs
        return runCatching {
            val frames = if (output.getTimestamp(stamp) && stamp.framePosition > 0) {
                minOf(queued, stamp.framePosition + ((System.nanoTime() - stamp.nanoTime) * 48000 / 1_000_000_000L).coerceAtLeast(0))
            } else output.playbackHeadPosition.toLong() and 0xffffffffL
            startUs + frames * 1_000_000L / 48000
        }.getOrDefault(startUs)
    }
    fun start() {
        running = true
        thread = Thread({
            var output: AudioTrack? = null
            try {
                SequenceAudioMixer(context, project).use { mixer ->
                    val min = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                    output = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(maxOf(min * 2, 16384))
                        .setTransferMode(AudioTrack.MODE_STREAM).build()
                    val t = output!!
                    if (!running) return@use
                    track = t; t.play()
                    var sample = startUs * 48000 / 1_000_000
                    val end = (project.durationUs * 48000 + 999999) / 1_000_000
                    while (running && sample < end) {
                        val frames = minOf(2048L, end - sample).toInt()
                        val pcm = mixer.mix(sample, frames)
                        var written = 0
                        while (running && written < pcm.size) {
                            val n = t.write(pcm, written, pcm.size - written, AudioTrack.WRITE_BLOCKING)
                            check(n >= 0 || !running) { "Audio output failed ($n)" }
                            if (n > 0) { written += n; queued += n / 2 }
                        }
                        sample += frames
                    }
                    // Let the final queued samples reach the output before releasing the track.
                    while (running && positionUs < project.durationUs) Thread.sleep(5)
                }
            } catch (failure: Exception) { if (running) this.failure = failure.message ?: "Audio playback failed" }
            finally { track = null; output?.let { runCatching { it.stop() }; it.release() } }
        }, "SequenceAudio").apply { start() }
    }
    fun stop() {
        running = false
        track?.let { runCatching { it.pause(); it.flush(); it.stop() } }
    }
}
