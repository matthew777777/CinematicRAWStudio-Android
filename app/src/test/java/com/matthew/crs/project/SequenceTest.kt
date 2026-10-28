package com.matthew.crs.project

import com.matthew.crs.Receipt
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class SequenceTest {
    private fun source(name: String = "take", fps: Double = 30.0, frames: Int = 300, audio: Boolean = true) =
        SourceAsset(uri = "content://test/$name", name = "$name.craw", kind = AssetKind.CRAW,
            durationUs = (frames * 1e6 / fps).toLong(), width = 2040, height = 1144, frames = frames, fps = fps, hasAudio = audio)
    @Test fun rationalFrameBoundariesRoundTrip() {
        listOf(FrameRate(24), FrameRate(25), FrameRate(30), FrameRate(60), FrameRate(24000,1001), FrameRate(30000,1001)).forEach { rate ->
            for (frame in 0L..20000L) { assertEquals(frame, rate.frame(rate.time(frame))); assertEquals(frame, rate.framesFor(rate.time(frame))) }
        }
    }
    @Test fun appendAndExclusiveCutsMapTheCorrectSource() {
        val first = source("a"); val second = source("b", fps = 60.0, frames = 600)
        val p = SequenceProject().append(first).append(second)
        val schedule = SequenceSchedule(p)
        assertEquals(first.id, schedule.videoAt(9_999_999)!!.asset.id)
        assertEquals(second.id, schedule.videoAt(10_000_000)!!.asset.id)
        assertEquals(2, schedule.videoAt(p.rate.time(301))!!.sourceFrame)
        assertNull(schedule.videoAt(p.durationUs))
        assertEquals(600L, p.frameCount)
    }
    @Test fun trimSplitAndMoveKeepCameraAudioLinked() {
        var p = SequenceProject().append(source())
        val video = p.clips.first()
        p = p.trim(video.id, 1_000_000, 8_000_000)
        assertTrue(p.linked(video.id).all { it.startUs == 1_000_000L && it.inUs == 1_000_000L && it.durationUs == 7_000_000L })
        p = p.move(video.id, 2_000_000).split(video.id, 4_000_000)
        assertEquals(4, p.clips.size)
        val right = p.clips.first { it.startUs == 4_000_000L }
        assertEquals(3_000_000L, right.inUs)
        assertEquals(2, p.linked(right.id).size)
        assertEquals(2, p.remove(video.id).clips.size)
    }
    @Test fun overlapsAndOutOfSourceTrimsAreRejected() {
        val p = SequenceProject().append(source("a")).append(source("b"))
        assertThrows(IllegalArgumentException::class.java) { p.move(p.clips.last().id, 1_000_000) }
        assertThrows(IllegalArgumentException::class.java) { p.trim(p.clips.first().id, 2_000_000, 1_000_000) }
        // Right trims clamp at the physical source end.
        assertEquals(10_000_000L, p.trim(p.clips.first().id, 0, 20_000_000).clips.first().durationUs)
    }
    @Test fun muteSoloAndGainsProduceTheSameScheduledAudioSpans() {
        var p = SequenceProject().append(source())
        val a2 = p.tracks[2]
        val music = SourceAsset(uri = "content://test/music", name = "music.wav", kind = AssetKind.AUDIO, durationUs = 5_000_000, hasAudio = true)
        p = p.append(music, a2.id).copy(tracks = p.tracks.map { if (it.id == a2.id) it.copy(solo = true, gain = 0.5f) else it })
        val spans = SequenceSchedule(p).audioBetween(0, 2_000_000)
        assertEquals(1, spans.size); assertEquals(music.id, spans.single().asset.id); assertEquals(0.5f, spans.single().gain)
        val muted = p.copy(tracks = p.tracks.map { it.copy(muted = true) })
        assertTrue(SequenceSchedule(muted).audioBetween(0, 2_000_000).isEmpty())
    }
    @Test fun projectRoundTripPreservesCutsLinksGradesAndAudio() {
        var p = SequenceProject(name = "Saved sequence").append(source()).append(source("b"))
        p = p.copy(clips = p.clips.mapIndexed { i, c -> c.copy(gain = 0.75f, receipt = Receipt(exposure = i * 20, contrast = 17, rawFixesEnabled = true)) })
        val restored = ProjectStore.decode(ProjectStore.encode(p))
        assertEquals(p, restored)
        assertEquals(SequenceSchedule(p).videoAt(12_000_000), SequenceSchedule(restored).videoAt(12_000_000))
    }
    @Test fun pcmWindowReadsRespectOffsetsAndMonoDuplication() {
        val file = File.createTempFile("sequence-audio", ".wav")
        try {
            RandomAccessFile(file, "rw").use { f ->
                f.write(ByteArray(44)); repeat(32) { f.writeShort(java.lang.Short.reverseBytes((it * 100).toShort()).toInt()) }
                AudioImport.writeWavHeader(f, 44100, 1, 64)
            }
            WavPcm(file).use { pcm ->
                assertEquals(32L, pcm.frameCount)
                val values = pcm.read(10, 4)
                assertEquals(1000f / 32768f, values[0], 0.000001f); assertEquals(values[0], values[1])
                assertEquals(1300f / 32768f, values[6], 0.000001f)
                assertTrue(pcm.read(35, 4).all { it == 0f })
            }
        } finally { file.delete() }
    }
    @Test fun ntscSingleClipRetainsAllFrames() {
        val asset = source(fps = 30000.0 / 1001, frames = 301)
        val p = SequenceProject(rate = FrameRate.fromFps(asset.fps)).append(asset)
        assertEquals(301L, p.frameCount)
        assertEquals(300, SequenceSchedule(p).videoAt(p.rate.time(300))!!.sourceFrame)
    }
    @Test fun aacEditListTrimsPrimingWithoutMovingMediaBytes() {
        fun atom(type: String, data: ByteArray) = java.nio.ByteBuffer.allocate(data.size + 8).putInt(data.size + 8).put(type.toByteArray()).put(data).array()
        fun full(type: String, size: Int) = atom(type, ByteArray(size - 8))
        fun track(type: String, length: Int): ByteArray {
            val header = full("tkhd", 92); java.nio.ByteBuffer.wrap(header, 28, 4).putInt(length)
            val handler = full("hdlr", 44); System.arraycopy(type.toByteArray(),0,handler,16,4)
            return atom("trak", header + atom("mdia", full("mdhd",32) + handler))
        }
        val movieHeader = full("mvhd",108)
        java.nio.ByteBuffer.wrap(movieHeader,20,4).putInt(1000)
        java.nio.ByteBuffer.wrap(movieHeader,24,4).putInt(363)
        val ftyp = full("ftyp",24)
        val moov = atom("moov",movieHeader + track("vide",300) + track("soun",363))
        val media = atom("mdat",ByteArray(128) { it.toByte() })
        val file = File.createTempFile("mp4-timing", ".mp4")
        try {
            file.writeBytes(ftyp + moov + media)
            Mp4Timing.trimAac(file,2112,300000)
            val bytes = file.readBytes()
            assertArrayEquals(media,bytes.copyOfRange(ftyp.size+moov.size,ftyp.size+moov.size+media.size))
            assertEquals("free",String(bytes,ftyp.size+4,4))
            val text=String(bytes,Charsets.ISO_8859_1)
            val edit=text.lastIndexOf("elst")-4
            assertTrue(edit>0)
            assertEquals(300,java.nio.ByteBuffer.wrap(bytes,edit+16,4).int)
            assertEquals(2112,java.nio.ByteBuffer.wrap(bytes,edit+20,4).int)
        } finally { file.delete() }
    }

    @Test fun importingAGradedTakeCopiesTheReceiptOntoPicture() {
        val receipt = Receipt(exposure = 70, contrast = 21, temperature = 5200)
        val p = SequenceProject().append(source(), receipt = receipt)
        assertEquals(receipt, p.clips.first { p.track(it.trackId).kind == TrackKind.VIDEO }.receipt)
        assertEquals(receipt, ProjectStore.decode(ProjectStore.encode(p)).clips.first { p.track(it.trackId).kind == TrackKind.VIDEO }.receipt)
    }

    @Test fun hevcSpsReportsPictureBitDepthRatherThanAssumingTheProfile() {
        val hex = "00000001420101016000000300b0000003000003005da00a080c1f3e5ad249492ef006a020202010"
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(8 to 8, HevcBitstream.bitDepth(bytes))
        assertThrows(IllegalStateException::class.java) { HevcBitstream.bitDepth(byteArrayOf(0,0,0,1,0x40,1)) }
    }

}
