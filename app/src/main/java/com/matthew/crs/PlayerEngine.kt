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
class PlayerEngine(private val clip: CrawClip) {
    val clipInfo: ClipInfo = clip.info
    private val info = clipInfo
    private val audio = if (info.hasAudio) TakeAudio(clip) else null
    private val bitmaps = arrayOf(clip.newPreviewBitmap(), clip.newPreviewBitmap())
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
        val bmp = bitmaps[back]
        val t0 = SystemClock.elapsedRealtime()
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
        thread = Thread({
            var shown = 0
            var windowStart = SystemClock.elapsedRealtime()
            while (running) {
                val due = (clockSeconds() * info.fps).toInt()
                if (due >= info.frames) break
                if (due != index) {
                    show(due)
                    shown++
                } else {
                    Thread.sleep(2)
                }
                val now = SystemClock.elapsedRealtime()
                if (now - windowStart >= 1000) {
                    shownFps = shown * 1000.0 / (now - windowStart)
                    android.util.Log.i("PlayerEngine", "preview %.1f fps, frame %d, clock %.2f s".format(shownFps, index, clockSeconds()))
                    shown = 0
                    windowStart = now
                }
            }
            running = false
            audio?.stop()
            playing = false
        }, "PlayerRender").apply { start() }
    }

    fun pause() {
        running = false
        thread?.join(1000)
        thread = null
        audio?.stop()
        playing = false
    }

    fun seek(i: Int) {
        val wasPlaying = playing
        if (wasPlaying) pause()
        show(i)
        if (wasPlaying) play()
    }

    fun release() {
        pause()
    }
}
