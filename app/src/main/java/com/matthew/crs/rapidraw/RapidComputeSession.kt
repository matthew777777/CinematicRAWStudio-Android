// SPDX-License-Identifier: AGPL-3.0-or-later
package com.matthew.crs.rapidraw
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONArray
import org.json.JSONObject

class RapidComputeSession(val width: Int,val height: Int): Closeable {
    private var handle: Long
    private var sourceRaw: Boolean?=null
    private var outputWidth=width
    private var outputHeight=height
    init { require(width>0 && height>0); handle=nativeCreate(width,height); check(handle!=0L) }
    @Synchronized fun uploadLinearFrame(frame: ByteBuffer,isRaw: Boolean) {
        check(handle!=0L); require(frame.isDirect && frame.capacity().toLong()==width.toLong()*height*8)
        nativeUpload(handle,frame,isRaw); sourceRaw=isRaw
    }
    @Synchronized fun uploadCameraFrame(frame: HardwareBuffer,a: FloatArray,d65: FloatArray,neutral: FloatArray): RapidSourceMetadata {
        check(handle!=0L); require(frame.width==width && frame.height==height && a.size==9 && d65.size==9 && neutral.size==3)
        val wb=nativeUploadCamera(handle,frame,a,d65,neutral); sourceRaw=true
        return RapidSourceMetadata(true,wb[0],wb[1])
    }
    @Synchronized fun uploadBayerFrame(frame: HardwareBuffer,channels: IntArray,levels: FloatArray,white: Float,
        a: FloatArray,d65: FloatArray,neutral: FloatArray): RapidSourceMetadata {
        check(handle!=0L)
        val wb=nativeUploadBayer(handle,frame,width,height,channels,levels,white,a,d65,neutral)
        sourceRaw=true; return RapidSourceMetadata(true,wb[0],wb[1])
    }
    private fun parameters(grade: RapidRawGrade,metadata: RapidSourceMetadata): ByteBuffer {
        check(handle!=0L && sourceRaw==metadata.isRaw)
        val values=JSONObject(grade.encode()).getJSONObject("parameters").toString().toByteArray(Charsets.UTF_8)
        val dims=nativeConfigure(handle,values,metadata.coordinateScale,metadata.isRaw)
        outputWidth=dims[0]; outputHeight=dims[1]
        return RapidHost.prepare(grade,metadata)
    }
    @Synchronized fun render(grade: RapidRawGrade,metadata: RapidSourceMetadata): Bitmap {
        val packed=parameters(grade,metadata)
        val bitmap=Bitmap.createBitmap(outputWidth,outputHeight,Bitmap.Config.ARGB_8888)
        try { nativeRender(handle,packed,bitmap) } catch(failure: Throwable) { bitmap.recycle(); throw failure }; return bitmap
    }
    @Synchronized fun writeP010(grade: RapidRawGrade,metadata: RapidSourceMetadata,image: android.media.Image,outputWidth: Int,outputHeight: Int) {
        val packed=parameters(grade,metadata)
        require(outputWidth%2==0 && outputHeight%2==0 && image.width>=outputWidth && image.height>=outputHeight)
        require(image.planes.size==3) { "Writable P010 Y/U/V plane views required" }
        nativeP010(handle,packed,image,outputWidth,outputHeight)
    }
    @Synchronized override fun close() { if(handle!=0L) { nativeClose(handle); handle=0 } }
    companion object {
        init { System.loadLibrary("rapidrawhost") }
        fun chart(width: Int,height: Int): ByteBuffer = ByteBuffer.allocateDirect(width*height*8).order(ByteOrder.LITTLE_ENDIAN).apply {
            for(y in 0 until height) for(x in 0 until width) {
                putShort(android.util.Half.toHalf(x.toFloat()/(width-1).coerceAtLeast(1)*2f))
                putShort(android.util.Half.toHalf(y.toFloat()/(height-1).coerceAtLeast(1)))
                putShort(android.util.Half.toHalf((x+y).toFloat()/(width+height-2).coerceAtLeast(1)))
                putShort(android.util.Half.toHalf(1f))
            }; flip()
        }
        @JvmStatic private external fun nativeUploadBayer(handle: Long,source: HardwareBuffer,width: Int,height: Int,channels: IntArray,levels: FloatArray,white: Float,a: FloatArray,d65: FloatArray,neutral: FloatArray): DoubleArray
        @JvmStatic private external fun nativeConfigure(handle: Long,json: ByteArray,coordinateScale: Float,raw: Boolean): IntArray
        @JvmStatic private external fun nativeCreate(width: Int,height: Int): Long
        @JvmStatic private external fun nativeClose(handle: Long)
        @JvmStatic private external fun nativeUpload(handle: Long,source: ByteBuffer,raw: Boolean)
        @JvmStatic private external fun nativeUploadCamera(handle: Long,source: HardwareBuffer,a: FloatArray,d65: FloatArray,neutral: FloatArray): DoubleArray
        @JvmStatic private external fun nativeRender(handle: Long,parameters: ByteBuffer,bitmap: Bitmap)
        @JvmStatic private external fun nativeP010(handle: Long,parameters: ByteBuffer,image: android.media.Image,outputWidth: Int,outputHeight: Int)
    }
}
