// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.util.Log
import com.matthew.rawlens.BayerPattern
import com.matthew.rawlens.DirectLogProfile
import com.matthew.rawlens.VfEglImport
import com.matthew.rawlens.VfLogGrade
import com.matthew.rawlens.VfPackedRcd
import com.matthew.rawlens.VfRcd
import com.matthew.rawlens.VfRgb8Convert
import com.matthew.rawlens.VfVulkan

/**
 * RawLens's Vulkan Direct-Log pipeline, unchanged (third_party/rawlens-gpu):
 * Packed RCD demosaic fused with the grade (white balance, CCM, exposure,
 * BT.709 encode) into P010, then P010 -> RGBA8 for display. The same P010
 * feeds MediaCodec at export, as in Direct Log.
 */
object GpuPipeline {
    @Volatile private var state: Int = -1  // -1 untried, 0 ok, >0 VfVulkan error

    val ready: Boolean get() = state == 0
    private var appContext: Context? = null
    private var fusedMode = true

    /**
     * Packed RCD runs either fused with the grade into P010 (playback) or to
     * camera RGB for MLV-App processing (editing). Switching re-creates the
     * RCD pipelines (~0.2 s), so it happens only on play/pause.
     */
    @Synchronized
    fun ensureRcdMode(fused: Boolean): Boolean {
        if (fusedMode == fused) return true
        val c = appContext ?: return false
        val ok = VfPackedRcd.init(c, fused = fused) == VfVulkan.OK
        if (ok) fusedMode = fused
        Log.i("GpuPipeline", "Packed RCD mode ${if (fused) "fused P010" else "camera RGB"}: $ok")
        return ok
    }

    @Synchronized
    fun init(context: Context): Boolean {
        if (state >= 0) return state == 0
        appContext = context.applicationContext
        state = try {
            fun spv(path: String) = context.assets.open(path).use { it.readBytes() }
            when {
                !VfVulkan.available -> VfVulkan.NOT_INITIALIZED
                // Direct Log's init order (DirectLogRecorder): device, f16, grade
                // (owns the twin command buffers/sets), grade-YUV, RCD, RGBA8.
                VfVulkan.initNative(spv("shaders/vf/vf_superpixel.spv")) != VfVulkan.OK -> VfVulkan.DEVICE_FAILED
                VfVulkan.initF16Native(spv("shaders/vf/vf_superpixel_f16.spv")) != VfVulkan.OK -> VfVulkan.DEVICE_FAILED
                VfLogGrade.initGradeNative(spv("shaders/vf/vf_loggrade.spv")) != VfVulkan.OK -> VfVulkan.PIPELINE_FAILED
                VfLogGrade.initGradeYuvNative(spv("shaders/vf/vf_gradeyuv.spv")) != VfVulkan.OK -> VfVulkan.PIPELINE_FAILED
                VfPackedRcd.init(context, fused = true) != VfVulkan.OK -> VfVulkan.PIPELINE_FAILED
                VfRgb8Convert.initRgb8Native(spv("shaders/vf/vf_p010torgb8.spv")) != VfVulkan.OK -> VfVulkan.PIPELINE_FAILED
                else -> VfVulkan.OK
            }
        } catch (e: Throwable) {
            Log.w("GpuPipeline", "init failed", e)
            VfVulkan.DEVICE_FAILED
        }
        Log.i("GpuPipeline", "Vulkan RCD pipeline: ${VfVulkan.describe(state)}")
        return state == 0
    }
}

/**
 * Renders one Bayer clip's frames on the GPU into hardware bitmaps. Paused
 * frames render at full resolution; playback renders a half-size proxy
 * (2x2 same-colour bin while decoding) and prefetches upcoming frames on
 * two decoder threads while the GPU works.
 */
class GpuFrameRenderer(private val clip: CrawClip, width: Int, height: Int) : AutoCloseable {
    private val w = width and 1.inv()
    private val h = height and 1.inv()

    private inner class Target(val tw: Int, val th: Int) {
        val p010: HardwareBuffer = VfEglImport.createP010(tw, th) ?: error("P010 buffer")
        val strides: IntArray = VfEglImport.describeP010(p010)?.let { intArrayOf(it[0], it[1], tw, th) } ?: error("P010 layout")
        val rgb8 = Array(2) {
            HardwareBuffer.create(tw, th, HardwareBuffer.RGBA_8888, 1,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_DATA_BUFFER or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        }
        var next = 0
        fun close() { p010.close(); rgb8.forEach { it.close() } }
    }

    /** One decoded input frame (BLOB holds a full-size mosaic or the half proxy). */
    private class Slot(val blob: HardwareBuffer) {
        var index = -1
        var half = false
        var params: FrameParams? = null
    }

    private val full = Target(w, h)
    private val half = Target(w / 4 * 2, h / 4 * 2)
    private val free = java.util.concurrent.LinkedBlockingQueue<Slot>()
    // Playback decodes on DECODERS workers at once (each a share of the cores);
    // slots = in-flight decodes + ready frames + the one on the GPU.
    private val slots = List(DECODERS + 3) { Slot(VfEglImport.createBlobBayerInput(w, h) ?: error("RAW buffer")) }
    private val decoders = java.util.concurrent.Executors.newFixedThreadPool(DECODERS) { r -> Thread(r, "CrawDecode") }
    private var ccmFor: FloatArray? = null
    private var ccm: FloatArray = VfLogGrade.IDENTITY_CCM.copyOf()
    private var timed = 0

    init { free.addAll(slots) }

    // Playback workers share the cores; a direct (paused) decode uses all.
    private val workerThreads = maxOf(1, Runtime.getRuntime().availableProcessors() / DECODERS)

    private fun decode(index: Int, halfRes: Boolean, threads: Int = 0): Slot? {
        val slot = free.take()
        val p = clip.decodeToBlob(index, slot.blob, halfRes, threads)
        if (p == null) { free.add(slot); return null }
        slot.index = index; slot.half = halfRes; slot.params = p
        return slot
    }

    // --- Playback: two workers decode proxy frames at the playhead; stale ones drop. ---
    @Volatile private var playing = false
    private val cursor = java.util.concurrent.atomic.AtomicInteger(0)
    private val ready = java.util.concurrent.ConcurrentSkipListMap<Int, Slot>()

    /** Starts decoding from [start]; [due] returns the frame due at the playback clock.
     *  [fullRes] plays at full resolution instead of the half-size proxy. */
    fun startPlayback(start: Int, frames: Int, fullRes: Boolean = false, due: () -> Int) {
        stopPlayback()
        GpuPipeline.ensureRcdMode(fused = true)
        playing = true
        cursor.set(start)
        repeat(DECODERS) {
            decoders.execute {
                while (playing) {
                    // Stay at most a few frames ahead of the clock (bounded by the slot pool).
                    val now = due()
                    if (cursor.get() > now + DECODERS + 2) { Thread.sleep(2); continue }
                    val i = cursor.getAndUpdate { maxOf(it, now) + 1 }.let { maxOf(it, now) }
                    if (i >= frames) break
                    val slot = free.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                    val p = clip.decodeToBlob(i, slot.blob, !fullRes, workerThreads)
                    if (p == null || !playing) { free.add(slot); continue }
                    slot.index = i; slot.half = !fullRes; slot.params = p
                    ready[i] = slot
                }
            }
        }
    }

    /** Newest decoded frame at or before [due] as (index, bitmap), or null if none is ready. */
    fun nextPlayback(due: Int, exposureEv: Float): Pair<Int, Bitmap?>? {
        val entry = ready.floorEntry(due) ?: return null
        // Frames older than the chosen one will never be shown.
        while (true) {
            val first = ready.firstEntry() ?: break
            if (first.key >= entry.key) break
            ready.remove(first.key)?.let { free.add(it) }
        }
        val slot = ready.remove(entry.key) ?: return null
        try {
            val t0 = System.nanoTime()
            val bmp = gpu(slot, exposureEv)
            if (timed++ % 30 == 0) Log.i("GpuFrameRenderer", "%s gpu %.1f ms, ready %d".format(if (slot.half) "proxy" else "full", (System.nanoTime() - t0) / 1e6, ready.size))
            return entry.key to bmp
        } finally {
            free.add(slot)
        }
    }

    fun stopPlayback() {
        playing = false
        // Workers notice within one decode; their slots return as frames finish.
        val deadline = System.currentTimeMillis() + 1000
        while (free.size + ready.size < slots.size && System.currentTimeMillis() < deadline) Thread.sleep(5)
        while (true) { val e = ready.pollFirstEntry() ?: break; free.add(e.value) }
    }

    private var rgbF16: HardwareBuffer? = null
    private val graded = arrayOfNulls<Bitmap>(2)
    private var gradedNext = 0

    /**
     * Frame [index] at full resolution through MLV-App's processing (vendored
     * MLV-App code): Packed RCD to camera RGB, then [grade]. For paused frames;
     * playback uses the fused GPU grade.
     */
    fun renderGraded(index: Int, grade: MlvGrade, receipt: () -> Receipt): Bitmap? {
        if (!GpuPipeline.ensureRcdMode(fused = false)) return null
        val slot = decode(index, false) ?: return null
        try {
            val f = slot.params ?: return null
            if (grade.asShot == null) grade.findAsShot(f.neutral)
            grade.apply(receipt())
            val buf = rgbF16 ?: HardwareBuffer.create(w, h, HardwareBuffer.RGBA_FP16, 1,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_DATA_BUFFER or
                    HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_CPU_READ_OFTEN).also { rgbF16 = it }
            val pattern = BayerPattern.entries[f.cfa.coerceIn(0, 3)]
            val (ip, fp) = VfRcd.packRcd(w, h, 0, 0, VfRcd.channelsFromPattern(pattern), f.width, 0, f.black, f.white)
            val t0 = System.nanoTime()
            val rc = VfPackedRcd.processNative(slot.blob, buf, ip, fp)
            if (rc != VfVulkan.OK) { Log.w("GpuFrameRenderer", "RCD failed: ${VfVulkan.describe(rc)}"); return null }
            val t1 = System.nanoTime()
            val out = graded[gradedNext] ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { graded[gradedNext] = it }
            if (!grade.render(buf, out)) return null
            Log.i("GpuFrameRenderer", "graded $index: rcd %.1f ms, MLV-App %.1f ms".format((t1 - t0) / 1e6, (System.nanoTime() - t1) / 1e6))
            gradedNext = 1 - gradedNext
            return out
        } finally {
            free.add(slot)
        }
    }

    /** Hardware Bitmap of frame [index]: half-size proxy while playing, else full resolution. */
    fun render(index: Int, exposureEv: Float, playing: Boolean = false): Bitmap? {
        GpuPipeline.ensureRcdMode(fused = true)
        val t0 = System.nanoTime()
        val slot = decode(index, playing) ?: return null
        try {
            val t1 = System.nanoTime()
            val bmp = gpu(slot, exposureEv)
            if (timed++ % 15 == 0) Log.i("GpuFrameRenderer", "%s: wait/decode %.1f ms, gpu %.1f ms".format(
                if (slot.half) "proxy" else "full", (t1 - t0) / 1e6, (System.nanoTime() - t1) / 1e6))
            return bmp
        } finally {
            free.add(slot)
        }
    }

    private fun gpu(slot: Slot, exposureEv: Float): Bitmap? {
        val f = slot.params ?: return null
        val t = if (slot.half) half else full
        if (!f.neutral.contentEquals(ccmFor)) {
            clip.ccm(f.neutral)?.let { ccm = it }
            ccmFor = f.neutral.copyOf()
        }
        val pattern = BayerPattern.entries[f.cfa.coerceIn(0, 3)]
        val (ip, fp) = VfRcd.packRcd(t.tw, t.th, 0, 0, VfRcd.channelsFromPattern(pattern), f.width, 0, f.black, f.white)
        // Camera gains from AsShotNeutral (green = 1), as the DNG/desktop path.
        val gains = floatArrayOf(f.neutral[1] / f.neutral[0], 1f, f.neutral[1] / f.neutral[2], 1f)
        val (_, grade) = VfLogGrade.packGrade(intArrayOf(t.tw, t.th), gains, ccm, exposureEv, false,
            profile = DirectLogProfile.BT709)
        // Lens shading is applied while decoding (pinned RawLens RCD has no shading input).
        val rc = VfPackedRcd.processYuvNative(slot.blob, t.p010, ip, fp, grade, t.strides)
        if (rc != VfVulkan.OK) { Log.w("GpuFrameRenderer", "RCD+grade failed: ${VfVulkan.describe(rc)}"); return null }
        val target = t.rgb8[t.next]
        val fd = VfRgb8Convert.rgb8SubmitNative(t.p010, target, intArrayOf(t.tw, t.th), intArrayOf(t.tw, t.th), t.strides, t.next)
        if (fd < 0) { Log.w("GpuFrameRenderer", "P010->RGBA8 failed: $fd"); return null }
        while (!VfEglImport.pollSyncFd(fd)) Thread.sleep(0, 200_000)
        VfEglImport.closeSyncFd(fd)
        t.next = 1 - t.next
        return Bitmap.wrapHardwareBuffer(target, ColorSpace.get(ColorSpace.Named.SRGB))
    }

    override fun close() {
        stopPlayback()
        decoders.shutdownNow()
        decoders.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
        slots.forEach { it.blob.close() }
        full.close(); half.close()
        rgbF16?.close()
    }
}

/** Concurrent playback decoders (measured on MT6878: 3 x 3 threads beats 2 x 4). */
private const val DECODERS = 3
