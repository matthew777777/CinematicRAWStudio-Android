// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs.project

/** Read the actual SPS bit depth; Main10 profile alone also permits 8-bit pictures. */
object HevcBitstream {
    fun bitDepth(csd: ByteArray): Pair<Int,Int> {
        val starts=ArrayList<Pair<Int,Int>>()
        var i=0
        while(i+3<csd.size) {
            val n=if(csd[i]==0.toByte()&&csd[i+1]==0.toByte()&&csd[i+2]==1.toByte())3
                else if(i+4<csd.size&&csd[i]==0.toByte()&&csd[i+1]==0.toByte()&&csd[i+2]==0.toByte()&&csd[i+3]==1.toByte())4 else 0
            if(n>0){starts+=i to n;i+=n}else i++
        }
        for(index in starts.indices) {
            val begin=starts[index].first+starts[index].second
            val end=starts.getOrNull(index+1)?.first?:csd.size
            if(begin+2>=end || (csd[begin].toInt() ushr 1 and 63)!=33)continue
            val raw=ArrayList<Byte>();var zeros=0
            for(p in begin+2 until end){val b=csd[p].toInt()and 255;if(zeros>=2&&b==3){zeros=0;continue};raw+=csd[p];zeros=if(b==0)zeros+1 else 0}
            val bits=Bits(raw.toByteArray())
            bits.skip(4);val layers=bits.read(3);bits.skip(1);bits.skip(96)
            val profile=BooleanArray(layers);val level=BooleanArray(layers)
            repeat(layers){profile[it]=bits.read(1)!=0;level[it]=bits.read(1)!=0}
            if(layers>0)bits.skip((8-layers)*2)
            repeat(layers){if(profile[it])bits.skip(88);if(level[it])bits.skip(8)}
            bits.ue();val chroma=bits.ue();if(chroma==3)bits.skip(1)
            bits.ue();bits.ue();if(bits.read(1)!=0)repeat(4){bits.ue()}
            return (8+bits.ue()) to (8+bits.ue())
        }
        error("HEVC encoder did not provide an Annex-B SPS for bit-depth verification")
    }
    private class Bits(val bytes:ByteArray){var at=0
        fun read(n:Int):Int{require(n in 0..31&&at+n<=bytes.size*8){"Truncated HEVC SPS"};var value=0;repeat(n){value=(value shl 1)or(bytes[at/8].toInt()ushr(7-at%8)and 1);at++};return value}
        fun skip(n:Int){require(at+n<=bytes.size*8);at+=n}
        fun ue():Int{var zeros=0;while(read(1)==0){zeros++;require(zeros<30)};return(1 shl zeros)-1+read(zeros)}
    }
}
