// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs.project

import android.content.Context
import android.util.AtomicFile
import com.matthew.crs.Receipt
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ProjectStore {
    private fun directory(context: Context) = File(context.filesDir, "projects").apply { mkdirs() }
    private fun file(context: Context, id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        return File(directory(context), "$id.json")
    }
    @Synchronized fun save(context: Context, project: SequenceProject) {
        val bytes = encode(project.validated()).toByteArray(Charsets.UTF_8)
        val atomic = AtomicFile(file(context, project.id))
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }
    /** Removes the project file only; its source takes are never touched. */
    @Synchronized fun delete(context: Context, id: String) { AtomicFile(file(context, id)).delete() }
    @Synchronized fun load(context: Context, id: String): SequenceProject =
        decode(AtomicFile(file(context, id)).readFully().toString(Charsets.UTF_8))
    @Synchronized fun list(context: Context): List<SequenceProject> = directory(context).listFiles().orEmpty()
        .filter { it.extension == "json" }.sortedByDescending { it.lastModified() }
        .mapNotNull { runCatching { load(context, it.nameWithoutExtension) }.getOrNull() }
    fun encode(p: SequenceProject): String = JSONObject().apply {
        put("version", p.version); put("id", p.id); put("name", p.name)
        put("fpsNum", p.rate.numerator); put("fpsDen", p.rate.denominator)
        put("width", p.width); put("height", p.height)
        put("assets", JSONArray(p.assets.map { a -> JSONObject().apply {
            put("id", a.id); put("uri", a.uri); put("name", a.name); put("kind", a.kind.name)
            put("durationUs", a.durationUs); put("width", a.width); put("height", a.height)
            put("frames", a.frames); put("fps", a.fps); put("hasAudio", a.hasAudio)
            put("audioFile", a.audioFile ?: JSONObject.NULL); put("rgb", a.rgb)
        } }))
        put("tracks", JSONArray(p.tracks.map { t -> JSONObject().apply {
            put("id", t.id); put("name", t.name); put("kind", t.kind.name)
            put("muted", t.muted); put("solo", t.solo); put("gain", t.gain.toDouble())
        } }))
        put("clips", JSONArray(p.clips.map { c -> JSONObject().apply {
            put("id", c.id); put("assetId", c.assetId); put("trackId", c.trackId)
            put("startUs", c.startUs); put("inUs", c.inUs); put("durationUs", c.durationUs)
            put("linkId", c.linkId ?: JSONObject.NULL); put("gain", c.gain.toDouble())
            put("receipt", JSONObject(c.receipt.toJson()))
        } }))
    }.toString(2)
    fun decode(json: String): SequenceProject {
        val o = JSONObject(json)
        fun <T> rows(key: String, read: (JSONObject) -> T): List<T> = o.getJSONArray(key).let { a ->
            (0 until a.length()).map { read(a.getJSONObject(it)) }
        }
        return SequenceProject(id = o.getString("id"), name = o.getString("name"), version = o.getInt("version"),
            rate = FrameRate(o.getInt("fpsNum"), o.getInt("fpsDen")), width = o.getInt("width"), height = o.getInt("height"),
            assets = rows("assets") { a -> SourceAsset(id = a.getString("id"), uri = a.getString("uri"),
                name = a.getString("name"), kind = AssetKind.valueOf(a.getString("kind")),
                durationUs = a.getLong("durationUs"), width = a.getInt("width"), height = a.getInt("height"),
                frames = a.getInt("frames"), fps = a.getDouble("fps"), hasAudio = a.getBoolean("hasAudio"),
                audioFile = if (a.isNull("audioFile")) null else a.getString("audioFile"), rgb = a.optBoolean("rgb")) },
            tracks = rows("tracks") { t -> SequenceTrack(id = t.getString("id"), name = t.getString("name"),
                kind = TrackKind.valueOf(t.getString("kind")), muted = t.getBoolean("muted"),
                solo = t.getBoolean("solo"), gain = t.getDouble("gain").toFloat()) },
            clips = rows("clips") { c -> SequenceClip(id = c.getString("id"), assetId = c.getString("assetId"),
                trackId = c.getString("trackId"), startUs = c.getLong("startUs"), inUs = c.getLong("inUs"),
                durationUs = c.getLong("durationUs"), linkId = if (c.isNull("linkId")) null else c.getString("linkId"),
                gain = c.getDouble("gain").toFloat(), receipt = Receipt.fromJson(c.getJSONObject("receipt").toString())) }
        ).validated()
    }
}
