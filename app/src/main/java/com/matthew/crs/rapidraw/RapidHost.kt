// SPDX-License-Identifier: AGPL-3.0-or-later
package com.matthew.crs.rapidraw
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class RapidSourceMetadata(val isRaw: Boolean,val asShotTemperature: Double,val asShotTint: Double,
    val sceneReferred: Boolean=false,val dngBaseline: Float=0f,val highlightPreservation: Float=0f,
    val baselineEnabled: Boolean=false,val baselineAmount: Float=0f,val compensateHighlights: Boolean=false,val upstreamTonemapperOverride: Int=-1,val coordinateScale: Float=1f)
object RapidHost {
    init { System.loadLibrary("rapidrawhost") }
    fun prepare(grade: RapidRawGrade,metadata: RapidSourceMetadata): ByteBuffer {
        require(metadata.asShotTemperature.isFinite() && metadata.asShotTint.isFinite())
        val bytes=nativePrepare(JSONObject(grade.encode()).getJSONObject("parameters").toString().toByteArray(Charsets.UTF_8),
            metadata.asShotTemperature,metadata.asShotTint,metadata.isRaw,metadata.sceneReferred,metadata.dngBaseline,
            metadata.highlightPreservation,metadata.baselineEnabled,metadata.baselineAmount,metadata.compensateHighlights,
            metadata.upstreamTonemapperOverride,grade.output.ordinal)
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN).apply { put(bytes); flip() }
    }
    @JvmStatic private external fun nativePrepare(json: ByteArray,temperature: Double,tint: Double,raw: Boolean,scene: Boolean,
        dngBaseline: Float,preservation: Float,baselineEnabled: Boolean,baseline: Float,compensate: Boolean,tonemapper: Int,outputMode: Int): ByteArray
}
