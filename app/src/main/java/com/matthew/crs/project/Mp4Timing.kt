// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs.project

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Preserve AAC pre-roll in the media while presenting exactly the timeline's audio range. */
object Mp4Timing {
    private data class Box(val at: Int, val size: Int, val type: String)
    private fun boxes(data: ByteArray, start: Int = 8): List<Box> {
        val out = ArrayList<Box>(); var at = start
        while (at + 8 <= data.size) {
            val size = ByteBuffer.wrap(data, at, 4).int
            require(size >= 8 && at.toLong() + size <= data.size) { "Invalid MP4 metadata atom" }
            out += Box(at,size,String(data,at+4,4,Charsets.US_ASCII)); at += size
        }
        require(at == data.size); return out
    }
    private fun atom(type: String, payload: ByteArray): ByteArray = ByteBuffer.allocate(payload.size + 8)
        .putInt(payload.size + 8).put(type.toByteArray(Charsets.US_ASCII)).put(payload).array()
    private fun slice(data: ByteArray, box: Box) = data.copyOfRange(box.at, box.at + box.size)
    private fun uint(data: ByteArray, at: Int): Long = ByteBuffer.wrap(data,at,4).int.toLong() and 0xffffffffL
    private fun duration(data: ByteArray, type: String): Long {
        val v1 = data[8].toInt() == 1
        val offset = if (type == "tkhd") { if (v1) 36 else 28 } else { if (v1) 32 else 24 }
        return if (v1) ByteBuffer.wrap(data,offset,8).long else uint(data,offset)
    }
    private fun setDuration(data: ByteArray, type: String, value: Long) {
        val v1 = data[8].toInt() == 1
        val offset = if (type == "tkhd") { if (v1) 36 else 28 } else { if (v1) 32 else 24 }
        if (v1) ByteBuffer.wrap(data,offset,8).putLong(value)
        else { require(value <= 0xffffffffL); ByteBuffer.wrap(data,offset,4).putInt(value.toInt()) }
    }
    fun trimAac(file: File, delaySamples: Int, durationUs: Long) {
        require(delaySamples >= 0 && durationUs > 0)
        RandomAccessFile(file,"rw").use { input ->
            var at=0L; var moovAt=-1L; var original: ByteArray?=null
            while (at + 8 <= input.length()) {
                input.seek(at); val small=input.readInt().toLong() and 0xffffffffL
                val type=ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                val size=if(small==1L) input.readLong() else if(small==0L) input.length()-at else small
                require(size>=8 && at+size<=input.length())
                if(type=="moov") { require(small != 1L && size <= 32*1024*1024); moovAt=at; original=ByteArray(size.toInt());input.seek(at);input.readFully(original);break }
                at+=size
            }
            val movie=requireNotNull(original) { "MP4 movie metadata is missing" }
            val children=boxes(movie)
            val mvhd=slice(movie,children.first { it.type=="mvhd" })
            val movieScale=uint(mvhd,if(mvhd[8].toInt()==1) 28 else 20)
            val desired=durationUs*movieScale/1_000_000L
            var foundAudio=false; var movieDuration=0L
            val rebuilt=children.map { child ->
                if(child.type!="trak") slice(movie,child) else {
                    val track=slice(movie,child); val entries=boxes(track)
                    val mdia=slice(track,entries.first { it.type=="mdia" })
                    val handler=slice(mdia,boxes(mdia).first { it.type=="hdlr" })
                    val audio=String(handler,16,4,Charsets.US_ASCII)=="soun"
                    val tkhd=slice(track,entries.first { it.type=="tkhd" })
                    if(!audio) { movieDuration=maxOf(movieDuration,duration(tkhd,"tkhd"));track }
                    else {
                        foundAudio=true; movieDuration=maxOf(movieDuration,desired)
                        setDuration(tkhd,"tkhd",desired)
                        val edit=atom("edts",atom("elst",ByteBuffer.allocate(20).putInt(0).putInt(1)
                            .putInt(desired.toInt()).putInt(delaySamples).putInt(0x00010000).array()))
                        val contents=entries.filterNot { it.type=="edts" }.flatMap { entry ->
                            if(entry.type=="tkhd") listOf(tkhd,edit) else listOf(slice(track,entry))
                        }
                        atom("trak",contents.fold(ByteArray(0)) { a,b -> a+b })
                    }
                }
            }.toMutableList()
            check(foundAudio) { "MP4 audio track is missing" }
            setDuration(mvhd,"mvhd",movieDuration)
            rebuilt[children.indexOfFirst { it.type=="mvhd" }]=mvhd
            val updated=atom("moov",rebuilt.fold(ByteArray(0)) { a,b -> a+b })
            // Append metadata, preserving every media/chunk offset. Only our staging file is changed.
            input.seek(input.length());input.write(updated);input.fd.sync()
            input.seek(moovAt+4);input.write("free".toByteArray(Charsets.US_ASCII));input.fd.sync()
        }
    }
}
