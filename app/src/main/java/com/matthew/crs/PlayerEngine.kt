// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Playback of one take. Audio (when present) is the master clock; a render
 * thread develops the frame due at the clock and skips frames when decoding
 * falls behind, so picture never drifts from sound. Two bitmaps alternate:
 * one on screen, one being rendered.
 */
class PlayerEngine(private val clip: CrawClip, private val gpu: GpuFrameRenderer? = null) {
    /** MLV-App processing for paused frames (vendored MLV-App code). */
    private val grade: MlvGrade? = gpu?.let { runCatching { MlvGrade(clip) }.getOrNull() }

    /** Grade in MLV-App units; null = as shot (MLV-App kelvin/tint found from AsShotNeutral). */
    var gradeSettings by mutableStateOf<GradeSettings?>(null)

    private fun effectiveSettings(asShot: Pair<Double, Double>): GradeSettings =
        (gradeSettings ?: GradeSettings(temperature = asShot.first, tint = asShot.second).also { gradeSettings = it })
            .copy(exposure = exposureEv.toDouble())
    val clipInfo: ClipInfo = clip.info
    private val info = clipInfo
    private val audio = if (info.hasAudio) TakeAudio(clip) else null
    private val bitmaps by lazy { arrayOf(clip.newPreviewBitmap(), clip.newPreviewBitmap()) }

    /** "GPU RCD" (RawLens Vulkan pipeline) or "CPU" (half-resolution developer). */
    val renderPath: String = if (gpu != null) "GPU RCD" else "CPU"
    private var back = 0

    var frame by mutableStateOf<Bitmap?>(null)
        private set
    var index by mutableIntStateOf(0)
        private set
    var playing by mutableStateOf(false)
        private set
    /** Frames shown per second while playing (preview decode speed). */
    var shownFps by mutableStateOf(0.0)
        private set
    var exposureEv by mutableStateOf(0f)
    /** Play at full resolution instead of the half-size proxy (paused frames are always full). */
    var fullResPlayback by mutableStateOf(false)

    @Volatile private var thread: Thread? = null
    @Volatile private var running = false
    private var noAudioStartMs = 0L
    private var startSeconds = 0.0

    private var lastAudio = -1.0
    private var lastAudioChangeMs = 0L

    /**
     * Playback clock: the audio position while audio advances. Audio can end
     * before the last frame (and has not started for the first few ms); when
     * it stalls for > 150 ms the clock continues on wall time from there.
     */
    private fun clockSeconds(): Double {
        val now = SystemClock.elapsedRealtime()
        val a = audio?.positionSeconds
            ?: return startSeconds + (now - noAudioStartMs) / 1000.0
        if (a != lastAudio) {
            lastAudio = a
            lastAudioChangeMs = now
            return a
        }
        val stalled = now - lastAudioChangeMs
        return if (stalled > 150) a + (stalled - 150) / 1000.0 else a
    }

    @Synchronized
    fun show(i: Int) {
        val target = i.coerceIn(0, maxOf(info.frames - 1, 0))
        val t0 = SystemClock.elapsedRealtime()
        if (gpu != null && grade != null && !playing) {
            val bmp = gpu.renderGraded(target, grade, ::effectiveSettings)
            if (bmp != null) { frame = bmp; index = target }
            return
        }
        if (gpu != null) {
            val hw = gpu.render(target, exposureEv, playing)

            if (!playing) android.util.Log.i("PlayerEngine", "gpu render $target ok=${hw != null} ${SystemClock.elapsedRealtime() - t0} ms")
            if (hw != null) { frame = hw; index = target }
            return
        }
        val bmp = bitmaps[back]
        val ok = clip.render(target, bmp, exposureEv)
        if (!playing) android.util.Log.i("PlayerEngine", "render $target ok=$ok ${SystemClock.elapsedRealtime() - t0} ms")
        if (ok) {
            back = 1 - back
            frame = bmp
            index = target
        }
    }

    fun play() {
        if (playing || info.frames == 0) return
        if (index >= info.frames - 1) show(0)
        playing = true
        startSeconds = index / info.fps
        noAudioStartMs = SystemClock.elapsedRealtime()
        lastAudio = -1.0
        lastAudioChangeMs = noAudioStartMs
        audio?.play(startSeconds)
        running = true
        gpu?.startPlayback(index, info.frames, fullResPlayback) { (clockSeconds() * info.fps).toInt() }
        thread = Thread({
            var shown = 0
            var gpuMs = 0L
            var starvedMs = 0L
            var windowStart = SystemClock.elapsedRealtime()
            while (running) {
                val due = (clockSeconds() * info.fps).toInt()
                if (due >= info.frames) break
                if (gpu != null) {
                    // Decoder workers run ahead; show the newest finished frame.
                    val g0 = SystemClock.elapsedRealtime()
                    val next = if (due != index) gpu.nextPlayback(due, exposureEv) else null
                    if (next != null) gpuMs += SystemClock.elapsedRealtime() - g0
                    else if (due != index) starvedMs += 2
                    val bmp = next?.second
                    if (next != null && bmp != null) {
                        frame = bmp
                        index = next.first
                        shown++
                    } else {
                        Thread.sleep(2)
                    }
                } else if (due != index) {
                    show(due)
                    shown++
                } else {
                    Thread.sleep(2)
                }
                val now = SystemClock.elapsedRealtime()
                if (now - windowStart >= 1000) {
                    shownFps = shown * 1000.0 / (now - windowStart)
                    android.util.Log.i("PlayerEngine", "preview %.1f fps, frame %d, clock %.2f s, gpu %d ms, starved %d ms".format(
                        shownFps, index, clockSeconds(), gpuMs, starvedMs))
                    gpuMs = 0; starvedMs = 0
                    shown = 0
                    windowStart = now
                }
            }
            running = false
            gpu?.stopPlayback()
            audio?.stop()
            playing = false
            if (gpu != null) show(index)  // full-resolution frame once stopped
        }, "PlayerRender").apply { start() }
    }

    fun pause() {
        running = false
        thread?.join(1000)
        thread = null
        gpu?.stopPlayback()
        audio?.stop()
        val wasPlaying = playing
        playing = false
        // Replace the playback proxy with the full-resolution frame.
        if (wasPlaying && gpu != null) Thread({ show(index) }, "PlayerStill").start()
    }

    fun seek(i: Int) {
        val wasPlaying = playing
        if (wasPlaying) pause()
        show(i)
        if (wasPlaying) play()
    }

    fun release() {
        pause()
        gpu?.close()
        grade?.close()
    }
}
