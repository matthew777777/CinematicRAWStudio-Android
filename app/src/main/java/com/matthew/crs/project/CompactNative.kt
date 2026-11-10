// SPDX-License-Identifier: Apache-2.0
package com.matthew.crs.project
import java.io.Closeable
import org.json.JSONObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Whole-file lossless verification is required, including all opaque container chunks. */
data class CompactResult(val uri: String,val name: String,val frames: Long,val compactFrames: Long,
    val retainedFrames: Long,val chunks: Long,val sourceBytes: Long,val outputBytes: Long,val sourceCodec: Int) {
    init {require(uri.isNotBlank() && name.isNotBlank() && frames>0 && compactFrames in 0..frames && retainedFrames in 0..frames && chunks>=frames && sourceBytes>0 && outputBytes>0 && sourceCodec in 1..4)}
    fun encode(): JSONObject=JSONObject().put("version",1).put("uri",uri).put("name",name).put("frames",frames)
        .put("compactFrames",compactFrames).put("retainedFrames",retainedFrames).put("chunks",chunks)
        .put("sourceBytes",sourceBytes).put("outputBytes",outputBytes).put("sourceCodec",sourceCodec)
    companion object {
        fun decode(o: JSONObject): CompactResult {require(o.getInt("version")==1);return CompactResult(o.getString("uri"),o.getString("name"),o.getLong("frames"),o.getLong("compactFrames"),o.getLong("retainedFrames"),o.getLong("chunks"),o.getLong("sourceBytes"),o.getLong("outputBytes"),o.getInt("sourceCodec"))}
        fun verified(o: JSONObject,uri: String,name: String): CompactResult {require(o.getBoolean("done") && o.getBoolean("verifying"));return CompactResult(uri,name,o.getLong("frames"),o.getLong("compactFrames"),o.getLong("retainedFrames"),o.getLong("chunks"),o.getLong("sourceBytes"),o.getLong("outputBytes"),o.getInt("sourceCodec"))}
    }
}
internal object CompactNative {
    init {System.loadLibrary("crs")}
    suspend fun run(source: Int,output: Int,verifyOnly: Boolean,progress: (Float,String)->Unit): JSONObject {
        val handle=nativeStart(source,output,verifyOnly);check(handle!=0L)
        try {
            while(true) {
                currentCoroutineContext().ensureActive()
                val step=JSONObject(nativeStep(handle))
                progress(step.getDouble("progress").toFloat(),if(step.getBoolean("verifying")) "Verifying every RAW frame and container chunk" else "Compacting original RAW samples")
                if(step.getBoolean("done")) {currentCoroutineContext().ensureActive();return step}
            }
        } finally {nativeClose(handle)}
    }
    @JvmStatic private external fun nativeStart(source: Int,output: Int,verifyOnly: Boolean): Long
    @JvmStatic private external fun nativeStep(handle: Long): String
    @JvmStatic private external fun nativeClose(handle: Long)
}
