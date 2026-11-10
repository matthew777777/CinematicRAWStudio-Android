// SPDX-License-Identifier: AGPL-3.0-or-later
package com.matthew.crs.rapidraw
import android.content.Context
import android.hardware.HardwareBuffer
import android.net.Uri
import com.matthew.crs.CrawClip
import java.io.Closeable

/** CRAW decoding and exact RawLens coherent MHC, followed by RapidRAW's camera calibration. */
class RapidMhcSource private constructor(private val clip: CrawClip,private val proxy: Boolean,
    private val blob: HardwareBuffer?,private val camera: HardwareBuffer?, private val frameWidth: Int, private val frameHeight: Int,private val ownsClip: Boolean): Closeable {
    val info get()=clip.info
    val width get()=frameWidth
    val height get()=frameHeight
    private val matrices=requireNotNull(clip.camMatrices()) { "Source has no camera calibration matrices" }
    private var lastFrame=-1
    private var lastCompute: RapidComputeSession?=null
    private var lastMetadata: RapidSourceMetadata?=null
    @Synchronized fun upload(index: Int,compute: RapidComputeSession): RapidSourceMetadata {
        require(index in 0 until info.frames)
        if(lastFrame==index && lastCompute===compute) return lastMetadata!!
        val a=matrices.second.map { it.toFloat() }.toFloatArray()
        val d65=matrices.first.map { it.toFloat() }.toFloatArray()
        val metadata=if(info.rgb) {
            val neutral=clip.decodeRgb(index,camera!!) ?: error("Cannot decode recorded RGB frame ${index+1}")
            compute.uploadCameraFrame(camera,a,d65,neutral)
        } else {
            val frame=clip.decodeToBlob(index,blob!!,proxy) ?: error("Cannot decode frame ${index+1}")
            require(frame.width==width && frame.height==height) { "Decoded Bayer dimensions differ from MHC input" }
            val channels=when(frame.cfa) {
                0 -> intArrayOf(0,1,2,3)
                1 -> intArrayOf(1,0,3,2)
                2 -> intArrayOf(2,3,0,1)
                3 -> intArrayOf(3,2,1,0)
                else -> error("Unknown Bayer phase")
            }
            compute.uploadBayerFrame(blob,channels,frame.black,frame.white,a,d65,frame.neutral)
        }
        lastFrame=index; lastCompute=compute; lastMetadata=metadata.copy(coordinateScale=width.toFloat()/info.width); return lastMetadata!!
    }
    override fun close() { camera?.close(); blob?.close(); if(ownsClip) clip.close() }
    companion object {
        fun open(context: Context,uri: Uri,proxy: Boolean=true): RapidMhcSource {
            val clip=CrawClip.open(context,uri) ?: error("Cannot open source: $uri")
            return fromClip(clip,proxy,true)
        }
        internal fun fromClip(clip: CrawClip,proxy: Boolean,ownsClip: Boolean): RapidMhcSource {
            var blob: HardwareBuffer?=null; var camera: HardwareBuffer?=null
            try {
                val width=if(proxy) clip.info.width/4*2 else clip.info.width and -2
                val height=if(proxy) clip.info.height/4*2 else clip.info.height and -2
                require(width>0 && height>0)
                if(!clip.info.rgb) {
                    blob=HardwareBuffer.create(Math.multiplyExact(Math.multiplyExact(clip.info.width,clip.info.height),2),1,0x21,1,
                        HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_CPU_WRITE_OFTEN)
                } else camera=HardwareBuffer.create(width,height,HardwareBuffer.RGBA_FP16,1,
                    HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_CPU_WRITE_OFTEN)
                return RapidMhcSource(clip,proxy,blob,camera,width,height,ownsClip)
            } catch(failure: Throwable) { camera?.close(); blob?.close(); if(ownsClip) clip.close(); throw failure }
        }
    }
}
