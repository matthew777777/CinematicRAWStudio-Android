// SPDX-License-Identifier: AGPL-3.0-or-later
package com.matthew.crs.rapidraw

import org.json.JSONArray
import org.json.JSONObject

enum class RapidSection(val title: String, val key: String) { Basic("Basic","basic"), Curves("Curves","curves"), Color("Color","color"), Details("Details","details"), Effects("Effects","effects"), Masks("Masks","masks") }
enum class RapidOutput(val title: String) { Upstream("RapidRAW"), FilamentAgX("AgX · Filament"), Aces2("ACES 2.0") }
enum class CurveChannel(val key: String) { Luma("luma"), Red("red"), Green("green"), Blue("blue") }
enum class WheelRange(val key: String) { Shadows("shadows"), Midtones("midtones"), Highlights("highlights"), Global("global") }
data class RapidPoint(val x: Double, val y: Double)
data class RapidWheel(val hue: Double = 0.0, val saturation: Double = 0.0, val luminance: Double = 0.0)
data class ParameterRange(val min: Double, val max: Double, val step: Double)
internal fun copyJson(value: Any): Any = when(value) { is JSONObject -> JSONObject(value.toString()); is JSONArray -> JSONArray(value.toString()); else -> value }

class RapidSchema(defaultsJson: String, schemaJson: String) {
    private val defaults = JSONObject(defaultsJson)
    private val schema = JSONObject(schemaJson)
    init { require(schema.getString("sourceCommit") == SOURCE_COMMIT && schema.getString("engine") == "rapidraw" && schema.getInt("version") == 1) }
    val keys: List<String> get() = defaults.keys().asSequence().toList()
    fun defaultValue(key: String): Any = copyJson(defaults.get(key))
    fun range(key: String): ParameterRange? = schema.getJSONObject("ranges").optJSONObject(key)?.let { ParameterRange(it.getDouble("min"),it.getDouble("max"),it.getDouble("step")) }
    fun sectionKeys(section: RapidSection): List<String> = when(section) {
        RapidSection.Masks -> listOf("masks")
        else -> schema.getJSONObject("sections").getJSONArray(section.key).let { a -> (0 until a.length()).map(a::getString) }
    }
    fun defaults(output: RapidOutput = RapidOutput.FilamentAgX) = create(JSONObject(defaults.toString()),output)
    internal fun create(values: JSONObject, output: RapidOutput): RapidRawGrade {
        require(values.keys().asSequence().toSet() == keys.toSet()) { "Parameter set differs from pinned RapidRAW schema" }
        fun finite(value: Any) { when(value) {
            is Number -> require(value.toDouble().isFinite()) { "Non-finite parameter" }
            is JSONObject -> value.keys().forEach { finite(value.get(it)) }
            is JSONArray -> repeat(value.length()) { finite(value.get(it)) }
        } }
        finite(values)
        keys.forEach { key ->
            val default = defaults.get(key); val actual = values.get(key)
            require(default === JSONObject.NULL || when(default) {
                is Number -> actual is Number
                is Boolean -> actual is Boolean
                is String -> actual is String
                is JSONObject -> actual is JSONObject
                is JSONArray -> actual is JSONArray
                else -> true
            }) { "Invalid type for $key" }
            range(key)?.let { require((actual as Number).toDouble() in it.min..it.max) { "$key out of range" } }
        }
        require(values.getString("toneMapper") in listOf("basic","agx"))
        require(values.getString("curveMode") in listOf("point","parametric"))
        require(values.getString("aiDenoiseMethod") in listOf("ai","bm3d"))
        for (field in listOf("curves","pointCurves")) for(channel in CurveChannel.entries) {
            val points = values.getJSONObject(field).getJSONArray(channel.key)
            require(points.length() in 2..16)
            var last = -1.0
            repeat(points.length()) { index ->
                val p = points.getJSONObject(index); val x = p.getDouble("x"); val y = p.getDouble("y")
                require(x in 0.0..255.0 && x > last && y in 0.0..255.0) { "Invalid curve points" }; last = x
            }
        }
        for(channel in CurveChannel.entries) {
            val p=values.getJSONObject("parametricCurve").getJSONObject(channel.key)
            val a=p.getDouble("split1");val b=p.getDouble("split2");val c=p.getDouble("split3")
            require(a>=10 && b-a>=10 && c-b>=10 && c<=90) {"Invalid curve region splits"}
        }
        for (range in WheelRange.entries) {
            val wheel = values.getJSONObject("colorGrading").getJSONObject(range.key)
            require(wheel.getDouble("hue") in 0.0..360.0 && wheel.getDouble("saturation") in 0.0..100.0 && wheel.getDouble("luminance") in -100.0..100.0)
        }
        return RapidRawGrade(this, values.toString(),output)
    }
    fun decode(serialized: String): RapidRawGrade {
        val o = JSONObject(serialized)
        require(o.getString("engine") == "rapidraw" && o.getInt("version") == 1 && o.getString("sourceCommit") == SOURCE_COMMIT) { "Unsupported grade engine or source version" }
        return create(o.getJSONObject("parameters"),RapidOutput.valueOf(o.getString("output")))
    }
    companion object { const val SOURCE_COMMIT = "f1d71a69eef813432db214f77969f567c41ff1d7" }
}
class RapidRawGrade internal constructor(private val schema: RapidSchema, private val json: String, val output: RapidOutput) {
    fun value(key: String): Any = copyJson(JSONObject(json).get(key))
    fun number(key: String) = (value(key) as Number).toDouble()
    fun at(path: String): Any { var node: Any = JSONObject(json); for(key in path.split('.')) node = (node as JSONObject).get(key); return copyJson(node) }
    fun withValue(key: String,value: Any) = schema.create(JSONObject(json).put(key,copyJson(value)),output)
    fun withPath(path: String,value: Any): RapidRawGrade {
        val root = JSONObject(json); val keys = path.split('.'); var node = root
        for(key in keys.dropLast(1)) node = node.getJSONObject(key)
        node.put(keys.last(),copyJson(value)); return schema.create(root,output)
    }
    fun active(tool: String) = (value("activeTools") as JSONObject).optBoolean(tool,true)
    fun withActive(tool: String,enabled: Boolean) = withPath("activeTools.$tool",enabled)
    fun withOutput(output: RapidOutput) = schema.create(JSONObject(json),output)
    fun withWheel(range: WheelRange,wheel: RapidWheel) = withPath("colorGrading.${range.key}",JSONObject().put("hue",wheel.hue).put("saturation",wheel.saturation).put("luminance",wheel.luminance))
    fun withCurve(channel: CurveChannel, points: List<RapidPoint>): RapidRawGrade {
        val data = JSONArray(points.map { JSONObject().put("x",it.x).put("y",it.y) })
        return withPath("pointCurves.${channel.key}",data).withCurveMode("point")
    }
    fun withCurveMode(mode: String) = withValue("curveMode",mode).resolveCurves()
    fun withParametric(channel: CurveChannel,key: String,value: Double) = withPath("parametricCurve.${channel.key}.$key",value).resolveCurves()
    private fun resolveCurves(): RapidRawGrade {
        val root = JSONObject(json)
        val curves = if(root.getString("curveMode") == "point") root.getJSONObject("pointCurves") else JSONObject().apply {
            CurveChannel.entries.forEach { channel -> put(channel.key,JSONArray(buildParametricPoints(root.getJSONObject("parametricCurve").getJSONObject(channel.key)).map { JSONObject().put("x",it.x).put("y",it.y) })) }
        }
        root.put("curves",curves); return schema.create(root,output)
    }
    fun reset(section: RapidSection): RapidRawGrade { var next = this; schema.sectionKeys(section).forEach { next = next.withValue(it,schema.defaultValue(it)) }; return next.resolveCurves() }
    fun encode(): String = JSONObject().put("engine","rapidraw").put("version",1).put("sourceCommit",RapidSchema.SOURCE_COMMIT).put("output",output.name).put("parameters",JSONObject(json)).toString()
}
data class RapidGradeSnapshot(val encoded: String = RapidDefaults.schema.defaults().encode()) {
    init { RapidDefaults.schema.decode(encoded) }
    fun grade() = RapidDefaults.schema.decode(encoded)
}
