// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs.project

import com.matthew.crs.Receipt
import java.util.UUID
import kotlin.math.roundToLong

fun newId(): String = UUID.randomUUID().toString()
enum class TrackKind { VIDEO, AUDIO }
enum class AssetKind { CRAW, AUDIO }

data class FrameRate(val numerator: Int = 30, val denominator: Int = 1) {
    init { require(numerator in 1..240000 && denominator in 1..10000) }
    companion object {
        fun fromFps(fps: Double): FrameRate {
            require(fps.isFinite() && fps > 0)
            listOf(24000, 30000, 60000, 120000, 240000).firstOrNull { kotlin.math.abs(it / 1001.0 - fps) < 0.0001 }?.let { return FrameRate(it, 1001) }
            return if (kotlin.math.abs(fps - fps.roundToLong()) < 0.0001) FrameRate(fps.roundToLong().toInt())
                else FrameRate((fps * 1000).roundToLong().toInt(), 1000)
        }
    }
    val fps: Double get() = numerator.toDouble() / denominator
    fun time(frame: Long): Long {
        require(frame in 0..(86_400L * numerator / denominator + 1)) { "Time is outside the 24-hour project limit" }
        return frame * 1_000_000L * denominator / numerator
    }
    fun frame(timeUs: Long): Long = (timeUs.coerceAtLeast(0) + 1) * numerator / (1_000_000L * denominator)
    fun snap(timeUs: Long): Long = time((timeUs.coerceAtLeast(0) * fps / 1_000_000).roundToLong())
    fun framesFor(durationUs: Long): Long =
        (durationUs * numerator + 1_000_000L * denominator - 1) / (1_000_000L * denominator)
}

data class SourceAsset(
    val id: String = newId(), val uri: String, val name: String, val kind: AssetKind,
    val durationUs: Long, val width: Int = 0, val height: Int = 0,
    val frames: Int = 0, val fps: Double = 0.0, val hasAudio: Boolean = false,
    val audioFile: String? = null, val rgb: Boolean = false,
)
data class SequenceTrack(
    val id: String = newId(), val name: String, val kind: TrackKind,
    val muted: Boolean = false, val solo: Boolean = false, val gain: Float = 1f,
)
data class SequenceClip(
    val id: String = newId(), val assetId: String, val trackId: String,
    val startUs: Long, val inUs: Long = 0, val durationUs: Long,
    val linkId: String? = null, val gain: Float = 1f, val receipt: Receipt = Receipt(),
) { val endUs: Long get() = startUs + durationUs }

data class SequenceProject(
    val id: String = newId(), val name: String = "Untitled sequence", val version: Int = 1,
    val rate: FrameRate = FrameRate(), val width: Int = 1280, val height: Int = 720,
    val assets: List<SourceAsset> = emptyList(),
    val tracks: List<SequenceTrack> = listOf(SequenceTrack(name = "V1", kind = TrackKind.VIDEO),
        SequenceTrack(name = "A1 · Camera", kind = TrackKind.AUDIO),
        SequenceTrack(name = "A2 · Audio", kind = TrackKind.AUDIO),
        SequenceTrack(name = "A3 · Audio", kind = TrackKind.AUDIO)),
    val clips: List<SequenceClip> = emptyList(),
) {
    val durationUs: Long get() = clips.maxOfOrNull { it.endUs } ?: 0
    val frameCount: Long get() = rate.framesFor(durationUs)
    fun asset(id: String): SourceAsset = assets.first { it.id == id }
    fun track(id: String): SequenceTrack = tracks.first { it.id == id }
    fun clip(id: String): SequenceClip = clips.first { it.id == id }
    fun linked(id: String): List<SequenceClip> = clip(id).let { selected ->
        clips.filter { it.id == id || (selected.linkId != null && it.linkId == selected.linkId) }
    }
    fun validated(): SequenceProject {
        require(version == 1) { "Unsupported project version $version" }
        require(name.isNotBlank() && width in 16..4096 && height in 16..4096 && width % 2 == 0 && height % 2 == 0)
        require(assets.map { it.id }.distinct().size == assets.size)
        require(tracks.map { it.id }.distinct().size == tracks.size)
        require(clips.map { it.id }.distinct().size == clips.size)
        assets.forEach { a ->
            require(a.durationUs in 1..86_400_000_000L && a.uri.isNotBlank())
            if (a.kind == AssetKind.CRAW) require(a.frames > 0 && a.fps.isFinite() && a.fps > 0 && a.width > 0 && a.height > 0)
        }
        tracks.forEach { require(it.gain.isFinite() && it.gain in 0f..4f) }
        clips.forEach { c ->
            val a = asset(c.assetId); val t = track(c.trackId)
            require(c.startUs >= 0 && c.endUs <= 86_400_000_000L && c.inUs >= 0 && c.durationUs > 0)
            require(c.inUs + c.durationUs <= a.durationUs) { "Trim extends beyond ${a.name}" }
            require(c.gain.isFinite() && c.gain in 0f..4f)
            require(t.kind != TrackKind.VIDEO || a.kind == AssetKind.CRAW)
            require(t.kind != TrackKind.AUDIO || a.hasAudio)
        }
        tracks.forEach { t ->
            val ordered = clips.filter { it.trackId == t.id }.sortedBy { it.startUs }
            ordered.zipWithNext().forEach { (a, b) -> require(a.endUs <= b.startUs) { "Clips overlap on ${t.name}" } }
        }
        return this
    }
    fun append(asset: SourceAsset, audioTrack: String? = null, atUs: Long? = null, receipt: Receipt = Receipt()): SequenceProject {
        val allAssets = if (assets.any { it.id == asset.id }) assets else assets + asset
        val target = audioTrack?.let(::track) ?: tracks.first { it.kind == TrackKind.VIDEO }
        val start = atUs ?: clips.filter { it.trackId == target.id }.maxOfOrNull { it.endUs } ?: 0
        val duration = rate.time(rate.frame(asset.durationUs)).coerceAtLeast(1).coerceAtMost(asset.durationUs)
        val group = newId()
        val added = mutableListOf(SequenceClip(assetId = asset.id, trackId = target.id,
            startUs = start, durationUs = duration, receipt = if (target.kind == TrackKind.VIDEO) receipt else Receipt(), linkId = if (target.kind == TrackKind.VIDEO && asset.hasAudio) group else null))
        if (target.kind == TrackKind.VIDEO && asset.hasAudio) {
            val camera = tracks.first { it.kind == TrackKind.AUDIO }
            added += SequenceClip(assetId = asset.id, trackId = camera.id, startUs = start, durationUs = duration, linkId = group)
        }
        return copy(assets = allAssets, clips = clips + added).validated()
    }
    fun move(id: String, start: Long): SequenceProject {
        val members = linked(id).map { it.id }.toSet()
        val delta = rate.snap(start) - clip(id).startUs
        return copy(clips = clips.map { if (it.id in members) it.copy(startUs = it.startUs + delta) else it }).validated()
    }
    /** Moving a trim edge preserves linked audio alignment and leaves the other edge fixed. */
    fun trim(id: String, sourceIn: Long, sourceOut: Long): SequenceProject {
        val selected = clip(id)
        require(sourceIn >= 0 && sourceIn < asset(selected.assetId).durationUs) { "Source in is outside the take" }
        val left = rate.snap(sourceIn)
        val right = rate.snap(sourceOut).coerceAtMost(asset(selected.assetId).durationUs)
        require(right - left >= minOf(rate.time(1), asset(selected.assetId).durationUs) - 1 && right > left) { "A clip must contain at least one frame" }
        val delta = left - selected.inUs
        val members = linked(id).map { it.id }.toSet()
        return copy(clips = clips.map { if (it.id in members) it.copy(startUs = it.startUs + delta,
            inUs = left, durationUs = right - left) else it }).validated()
    }
    fun split(id: String, atUs: Long): SequenceProject {
        val selected = clip(id); val point = rate.snap(atUs)
        require(point > selected.startUs && point < selected.endUs) { "Place the playhead inside the selected clip" }
        val members = linked(id).map { it.id }.toSet(); val newGroup = newId()
        return copy(clips = clips.flatMap { c ->
            if (c.id !in members) listOf(c) else {
                val left = point - c.startUs
                listOf(c.copy(durationUs = left), c.copy(id = newId(), startUs = point,
                    inUs = c.inUs + left, durationUs = c.durationUs - left,
                    linkId = if (c.linkId != null) newGroup else null))
            }
        }).validated()
    }
    fun remove(id: String): SequenceProject {
        val members = linked(id).map { it.id }.toSet()
        return copy(clips = clips.filterNot { it.id in members }).validated()
    }
}

data class VideoSample(val clip: SequenceClip, val asset: SourceAsset, val sourceFrame: Int)
data class AudioSpan(val clip: SequenceClip, val asset: SourceAsset, val startUs: Long, val endUs: Long, val gain: Float)

/** The single source of timing decisions for preview, sound mixing, and export. Endpoints are exclusive. */
class SequenceSchedule(val project: SequenceProject) {
    fun videoAt(timeUs: Long): VideoSample? {
        val tracks = project.tracks.filter { it.kind == TrackKind.VIDEO && !it.muted }
        for (track in tracks.asReversed()) {
            val clip = project.clips.firstOrNull { it.trackId == track.id && timeUs >= it.startUs && timeUs < it.endUs } ?: continue
            val asset = project.asset(clip.assetId)
            val frame = ((clip.inUs + timeUs - clip.startUs + 1) * asset.fps / 1_000_000.0).toInt().coerceIn(0, asset.frames - 1)
            return VideoSample(clip, asset, frame)
        }
        return null
    }
    fun audioBetween(startUs: Long, endUs: Long): List<AudioSpan> {
        val tracks = project.tracks.filter { it.kind == TrackKind.AUDIO }
        val solo = tracks.any { it.solo && !it.muted }
        return tracks.filter { !it.muted && (!solo || it.solo) }.flatMap { track ->
            project.clips.filter { it.trackId == track.id && it.startUs < endUs && it.endUs > startUs }.map {
                AudioSpan(it, project.asset(it.assetId), maxOf(startUs, it.startUs), minOf(endUs, it.endUs), it.gain * track.gain)
            }
        }
    }
}
