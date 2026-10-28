// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs.project

import android.content.Context
import android.media.*
import android.net.Uri
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Calibrate the actual encoder once; do not assume all AAC implementations use the same delay. */
object AacDelay {
    suspend fun samples(context: Context, encoder: String): Int {
        val prefs=context.getSharedPreferences("aac-priming",0)
        val key="${android.os.Build.FINGERPRINT}/$encoder/48000/2/192000/v1"
        val cached=prefs.getInt(key,-1);if(cached>=0)return cached
        val base=File(context.cacheDir,"aac-delay-${newId()}")
        val encoded=File(base.path+".m4a");val wav=File(base.path+".wav")
        val reference=ShortArray(4096)
        var seed=0x12345678
        val noise=FloatArray(258) {
            seed=seed*1664525+1013904223
            ((seed ushr 8)/16777216f-.5f)*18000
        }
        for(i in 1024 until 2048) {
            val x=(i-1024)/4f;val n=x.toInt();val f=x-n
            reference[i]=(noise[n]+(noise[n+1]-noise[n])*f).toInt().toShort()
        }
        val codec=MediaCodec.createByCodecName(encoder)
        var sink:CodecSink?=null
        try {
            val format=MediaFormat.createAudioFormat("audio/mp4a-latm",48000,2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE,192000);setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,16384)
            }
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);codec.start()
            val mux=CodecSink(codec,encoded);sink=mux
            var offset=0;var eos=false;val start=android.os.SystemClock.elapsedRealtime()
            while(!eos) {
                currentCoroutineContext().ensureActive()
                check(android.os.SystemClock.elapsedRealtime()-start<30000) { "AAC timing calibration timed out" }
                val slot=codec.dequeueInputBuffer(10000)
                if(slot>=0) {
                    if(offset>=reference.size) {codec.queueInputBuffer(slot,0,0,offset*1_000_000L/48000,MediaCodec.BUFFER_FLAG_END_OF_STREAM);eos=true}
                    else {
                        val b=codec.getInputBuffer(slot)!!.order(ByteOrder.LITTLE_ENDIAN);b.clear()
                        val count=minOf(1024,reference.size-offset,b.remaining()/4)
                        repeat(count) { n->b.putShort(reference[offset+n]);b.putShort(reference[offset+n]) }
                        codec.queueInputBuffer(slot,0,count*4,offset*1_000_000L/48000,0);offset+=count
                    }
                }
                mux.drain(false)
            }
            mux.drain(true)
        } finally { runCatching{codec.stop()};codec.release();sink?.close() }
        try {
            AudioImport.decode(context,Uri.fromFile(encoded),wav)
            val decoded=WavPcm(wav).use { it.read(0,minOf(10000L,it.frameCount).toInt()) }
            var best=Double.NEGATIVE_INFINITY;var delay=-1
            val maxDelay=minOf(4096,decoded.size/2-2048)
            for(candidate in 0..maxDelay) {
                var correlation=0.0
                for(i in 1024 until 2048 step 4) correlation+=reference[i]*decoded[(i+candidate)*2]
                if(correlation>best){best=correlation;delay=candidate}
            }
            check(delay>=0) { "Cannot determine AAC priming delay" }
            var a=0.0;var b=0.0
            for(i in 1024 until 2048 step 4) {a+=reference[i].toDouble()*reference[i];b+=decoded[(i+delay)*2].toDouble()*decoded[(i+delay)*2]}
            check(best/sqrt(a*b)>0.7) { "AAC timing calibration is inconclusive" }
            prefs.edit().putInt(key,delay).apply()
            android.util.Log.i("CRSExport","Calibrated AAC priming: $delay samples ($encoder)")
            return delay
        } finally {encoded.delete();wav.delete()}
    }
}
