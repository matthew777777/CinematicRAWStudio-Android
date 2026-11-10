package com.matthew.crs

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.net.Uri
import com.matthew.crs.project.*
import com.matthew.crs.rapidraw.*
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.*

/** Real-device integration checks: RAW grading -> codec, sequence cuts, mixed audio, save/reopen, DNG. */
class ProjectInstrumentation : Instrumentation() {
    private var rapidNative = false
    private var rapidSource = false
    private var nativePlayback = false
    private var nativeExport = false
    private var playbackOnly = false
    private var benchmark = false
    private var benchmarkFrames = 30
    private var headless = false
    private var main10HighRes = false
    private var gpuParity = false
    private var colorCheck = false
    private var colorSource: String? = null
    override fun onCreate(arguments: Bundle?) {
        rapidNative = arguments?.getString("rapidraw") == "true"
        rapidSource = arguments?.getString("rapidrawsource") == "true"
        nativePlayback = arguments?.getString("nativeplayback") == "true"
        nativeExport = arguments?.getString("nativeexport") == "true"
        playbackOnly = arguments?.getString("playbackonly") == "true"
        benchmark = arguments?.getString("benchmark") == "true"
        benchmarkFrames = (arguments?.getString("frames")?.toIntOrNull() ?: 30).coerceIn(3, 600)
        colorSource = arguments?.getString("source")
        colorCheck = arguments?.getString("colorcheck") == "true"
        headless = arguments?.getString("headless") == "true"
        main10HighRes = arguments?.getString("main10hires") == "true"
        gpuParity = arguments?.getString("gpuparity") == "true"
        super.onCreate(arguments); start()
    }
    private fun report(text: String) { sendStatus(0, Bundle().apply { putString("stream", text + "\n") }) }
    override fun onStart() {
        try {
            runBlocking { if (rapidSource) verifyRapidSource() else if (rapidNative) verifyRapidNative() else if (benchmark) verifyBenchmark() else if (colorCheck || gpuParity) verifyRapidNative() else if (main10HighRes) verifyMain10HighRes() else verify() }
            finish(Activity.RESULT_OK, Bundle().apply { putString("stream", when {
                rapidNative -> "PASS: independent RapidRAW JNI/Vulkan controls\n"
                rapidSource -> "PASS: MHC to independent RapidRAW source preview\n"
                benchmark -> "PASS: pipeline benchmark completed\n"
                else -> "PASS: project/RAW/video/audio/CinemaDNG integration\n"
            }) })
        } catch (failure: Throwable) {
            finish(Activity.RESULT_CANCELED, Bundle().apply { putString("stream", failure.stackTraceToString()) })
        }
    }
    private fun verifyRapidSource() {
        val context=targetContext
        val schema=com.matthew.crs.rapidraw.RapidSchema(
            context.assets.open("rapidraw/defaults.json").bufferedReader().use{it.readText()},
            context.assets.open("rapidraw/schema.json").bufferedReader().use{it.readText()})
        val grade=schema.defaults(com.matthew.crs.rapidraw.RapidOutput.FilamentAgX)
        val dir=context.getExternalFilesDir("project-review")!!.resolve("rapidraw-source").apply{mkdirs()}
        com.matthew.crs.rapidraw.RapidMhcSource.open(context,bayerSource(),proxy=true).use { source ->
            com.matthew.crs.rapidraw.RapidComputeSession(source.width,source.height).use { compute ->
                val start=System.nanoTime();val metadata=source.upload(0,compute)
                report("Source ${source.info.width}x${source.info.height} -> ${source.width}x${source.height}; upload ${(System.nanoTime()-start)/1e6} ms; as-shot ${metadata.asShotTemperature} K / ${metadata.asShotTint}")
                check(metadata.asShotTemperature.isFinite()&&metadata.asShotTint.isFinite())
                val baseline=compute.render(grade,metadata)
                for((name,adjusted) in listOf("exposure" to grade.withValue("exposure",1.0),
                    "wheels" to grade.withWheel(com.matthew.crs.rapidraw.WheelRange.Global,com.matthew.crs.rapidraw.RapidWheel(240.0,60.0,5.0)),
                    "curves" to grade.withCurveMode("parametric").withParametric(com.matthew.crs.rapidraw.CurveChannel.Luma,"lights",60.0),
                    "aces2" to grade.withOutput(com.matthew.crs.rapidraw.RapidOutput.Aces2))) {
                    val image=compute.render(adjusted,metadata)
                    val difference=error(image,baseline)
                    check(difference>0.01){"$name did not affect source preview"}
                    File(dir,"$name.png").outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
                    image.recycle();report("RapidRAW source $name: mean pixel difference $difference")
                }
                val again=source.upload(0,compute)
                check(again==metadata){"Cached source metadata changed"}
                val restored=compute.render(grade,again)
                check(error(restored,baseline)==0.0){"Cached source/grade changed preview"}
                restored.recycle();baseline.recycle()
                if(source.info.frames>1){val next=source.upload(1,compute);compute.render(grade,next).recycle();report("Second source frame developed through new engine")}
                val fixtureKey="fixture://rapidraw-source-store"
                com.matthew.crs.rapidraw.RapidGradeStore.save(context,fixtureKey,grade.withValue("exposure",1.0))
                val saved=com.matthew.crs.rapidraw.RapidGradeStore.load(context,fixtureKey,schema)!!
                check(saved.number("exposure")==1.0&&saved.output==grade.output)
                report("Separate new-engine grade storage round-trip passed; original receipt untouched")
            }
        }
    }
    private fun verifyRapidNative() {
        val context=targetContext
        val schema=com.matthew.crs.rapidraw.RapidSchema(
            context.assets.open("rapidraw/defaults.json").bufferedReader().use { it.readText() },
            context.assets.open("rapidraw/schema.json").bufferedReader().use { it.readText() })
        val base=schema.defaults(com.matthew.crs.rapidraw.RapidOutput.Upstream)
        val metadata=com.matthew.crs.rapidraw.RapidSourceMetadata(true,6504.0,0.0)
        val curveFixtures=org.json.JSONObject(this.context.assets.open("rapidraw-parametric-curves.json").bufferedReader().use{it.readText()}).getJSONArray("cases")
        repeat(curveFixtures.length()) { index ->
            val fixture=curveFixtures.getJSONObject(index)
            val parametric=base.value("parametricCurve") as org.json.JSONObject
            parametric.put("luma",fixture.getJSONObject("settings"))
            val resolved=base.withValue("parametricCurve",parametric).withCurveMode("parametric")
            val points=resolved.at("curves.luma") as org.json.JSONArray
            val expected=fixture.getJSONArray("float32_bits")
            repeat(points.length()) { i ->
                for((component,key) in listOf("x","y").withIndex()) {
                    val bits=points.getJSONObject(i).getDouble(key).toFloat().toRawBits().toLong() and 0xffffffffL
                    check(bits==expected.getJSONArray(i).getLong(component)){"Curve fixture $index point $i $key differs at shader precision"}
                }
            }
        }
        report("${curveFixtures.length()} upstream parametric curve fixtures match exact float32 bits on Android")
        val packed=com.matthew.crs.rapidraw.RapidHost.prepare(base.withValue("exposure",2.0),metadata)
        check(packed.getFloat(0)==2.5f) { "JNI preparation changed upstream exposure scaling" }
        val dir=context.getExternalFilesDir("project-review")!!.resolve("rapidraw-native").apply { mkdirs() }
        com.matthew.crs.rapidraw.RapidComputeSession(64,32).use { renderer ->
            renderer.uploadLinearFrame(com.matthew.crs.rapidraw.RapidComputeSession.chart(64,32),true)
            val original=renderer.render(base,metadata)
            val cases=listOf(
                "exposure" to base.withValue("exposure",1.0),
                "tonal" to base.withValue("shadows",40.0).withValue("highlights",-30.0),
                "detail" to base.withValue("clarity",30.0).withValue("sharpness",35.0),
                "wheels" to base.withWheel(com.matthew.crs.rapidraw.WheelRange.Shadows,com.matthew.crs.rapidraw.RapidWheel(220.0,70.0,5.0)),
                "curves" to base.withCurve(com.matthew.crs.rapidraw.CurveChannel.Red,listOf(com.matthew.crs.rapidraw.RapidPoint(0.0,0.0),com.matthew.crs.rapidraw.RapidPoint(100.0,180.0),com.matthew.crs.rapidraw.RapidPoint(255.0,255.0))),
                "parametric" to base.withCurveMode("parametric").withParametric(com.matthew.crs.rapidraw.CurveChannel.Luma,"lights",70.0),
                "filament" to base.withOutput(com.matthew.crs.rapidraw.RapidOutput.FilamentAgX),
                "aces2" to base.withOutput(com.matthew.crs.rapidraw.RapidOutput.Aces2))
            for((name,grade) in cases) {
                val image=renderer.render(grade,metadata)
                val diff=error(image,original)
                check(diff>0.01) { "$name did not affect native Vulkan pixels" }
                File(dir,"$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                image.recycle()
                report("RapidRAW native $name: mean pixel difference $diff")
            }
            val bypass=renderer.render(base.withValue("exposure",1.0).withActive("basic",false),metadata)
            check(error(original,bypass)==0.0) { "Basic bypass did not restore original Vulkan output" }
            bypass.recycle();original.recycle()
            try {
                renderer.render(base.withValue("aiDenoiseEnabled",true),metadata)
                error("Unconnected host effect was silently ignored")
            } catch(expected:IllegalStateException) { check(expected.message?.contains("basic Vulkan")==true); report("AI excluded from basic Vulkan grading") }
        }
        report("PASS: independent RapidRAW JNI + Vulkan chart controls; no source footage or video export tested")
    }
    /** Same production renderer used by playback and export, with explicit resolution reporting. */
    private suspend fun verifyBenchmark() {
        val context = targetContext
        val raw = ProjectMedia.importRaw(context, bayerSource()).copy(hasAudio = false)
        val count = minOf(benchmarkFrames, raw.frames)
        val dir = context.getExternalFilesDir("project-review")!!.apply { mkdirs() }
        val lines = mutableListOf<String>()
        fun emit(line: String) { lines.add(line); report(line) }
        for (output in listOf(RapidOutput.FilamentAgX, RapidOutput.Aces2)) {
            val grade = RapidGradeSnapshot(RapidDefaults.schema.defaults(output).encode())
            val base = SequenceProject(name = "RapidRAW benchmark", width = raw.width and -2,
                height = raw.height and -2, rate = FrameRate.fromFps(raw.fps)).append(raw, grade = grade)
            val project = base.copy(clips = base.clips.map { it.copy(durationUs = base.rate.time(count.toLong())) }).validated()
            ProjectFrameRenderer(context, proxy = !nativePlayback).use { renderer ->
                renderer.render(project,0)
                val start = System.nanoTime()
                repeat(count) { renderer.render(project,it.toLong()) }
                val seconds = (System.nanoTime()-start)/1e9
                emit("RapidRAW ${output.name} native=$nativePlayback source=${raw.width}x${raw.height} frames=$count seconds=$seconds fps=${count/seconds}; headless throughput, not display presentation")
            }
            if (!playbackOnly) for (codec in listOf(ExportVideoCodec.AVC,ExportVideoCodec.HEVC10)) {
                val settings = ExportSettings(codec=codec,bitrateMbps=12)
                val outputProject = if (nativeExport) project else project.copy(width=1280,height=720)
                if(settings.encoder(outputProject)==null) continue
                val start = System.nanoTime()
                SequenceExporter.export(context,outputProject,settings,Uri.fromFile(File(dir,"rapidraw-${output.name}-${codec.name}.mp4"))) { _,_ -> }
                val seconds = (System.nanoTime()-start)/1e9
                val actual = settings.output(outputProject)
                emit("RapidRAW export ${output.name} ${codec.name} ${actual.width}x${actual.height} frames=$count seconds=$seconds fps=${count/seconds}")
            }
        }
        File(dir,"rapidraw-benchmark.txt").writeText(lines.joinToString("\n"))
    }
    private suspend fun verify() {
        val context = targetContext
        val root = context.getSharedPreferences("gallery", 0).getString("tree", null) ?: error("Choose a source folder in the library first")
        val takes = listTakes(context, Uri.parse(root))
        val bayer = takes.firstOrNull { take -> CrawClip.open(context, take.uri)?.use { !it.info.rgb } == true } ?: error("Test requires a Bayer take")
        val rgb = takes.firstOrNull { take -> CrawClip.open(context, take.uri)?.use { it.info.rgb } == true } ?: error("Test requires a binned RGB take")
        val dir = context.getExternalFilesDir("project-review")!!.apply { mkdirs() }
        val raw = ProjectMedia.importRaw(context, bayer.uri).copy(hasAudio = false)
        val recorded = ProjectMedia.importRaw(context, rgb.uri).copy(hasAudio = false)
        val rate = FrameRate(30)
        var project = SequenceProject(name = "Integration check", width = 320, height = 180, rate = rate).append(raw).append(recorded).append(raw)
        project = project.copy(clips = project.clips.mapIndexed { index, c -> c.copy(startUs = rate.time(index * 3L),
            durationUs = rate.time(3), grade = if (index == 2) testGrade() else RapidGradeSnapshot()) }).validated()
        val music = tone(context, 44100, 440.0, 0.12, project.durationUs)
        val voice = tone(context, 48000, 880.0, 0.1, project.durationUs)
        project = project.append(music, project.tracks[2].id, 0).append(voice, project.tracks[3].id, 0)
        ProjectStore.save(context, project)
        val reopened = ProjectStore.load(context, project.id)
        check(reopened == project) { "Save/reopen changed the timeline" }
        report("Save/reopen preserves ${project.clips.size} clips, grades, and four tracks")
        SequenceAudioMixer(context, project).use { mixer ->
            val block = mixer.mix(2400, 4800)
            check(energy(block, 440.0) > 500 && energy(block, 880.0) > 500) { "Mix lost an audio track" }
        }
        val expected = ProjectFrameRenderer(context).use { renderer ->
            listOf(0L, 3L, 6L).map { frame -> renderer.render(project, frame).copy(Bitmap.Config.ARGB_8888, false)!! }
        }
        check(mean(expected[2]) > mean(expected[0]) + 3) { "Grade did not alter the RAW frame" }
        report("Shared RAW renderer applies per-clip grade; 44.1/48 kHz audio tracks mix correctly")
        for (codec in ExportVideoCodec.entries) {
            val settings = ExportSettings(codec = codec, bitrateMbps = 8)
            if (settings.encoder(project) == null) { report("${codec.label}: unavailable, skipped"); continue }
            val file = File(dir, "sequence-${codec.name}.mp4")
            SequenceExporter.export(context, project, settings, Uri.fromFile(file)) { _, _ -> }
            verifyVideo(file, expected, 9)
            if (codec.tenBit) {
                val reader = MediaExtractor()
                try {
                    reader.setDataSource(file.path)
                    val v = (0 until reader.trackCount).first { reader.getTrackFormat(it).getString("mime") == "video/hevc" }
                    val csd = reader.getTrackFormat(v).getByteBuffer("csd-0")!!.duplicate()
                    val bytes = ByteArray(csd.remaining()); csd.get(bytes)
                    check(HevcBitstream.bitDepth(bytes) == (10 to 10)) { "Exported bitstream is not true 10-bit HEVC" }
                } finally { reader.release() }
                report("HEVC Main10 SPS confirms 10-bit luma/chroma from the graded RGB16 pipeline")
            }
            val decoded = File(context.cacheDir, "integration-audio-${codec.name}.wav")
            AudioImport.decode(context, Uri.fromFile(file), decoded)
            WavPcm(decoded).use { pcm ->
                val beginning = pcm.read(0, 512)
                check(beginning.map { kotlin.math.abs(it) }.average() > 0.01) { "AAC priming delayed the start of the mixed audio" }
                check(kotlin.math.abs(pcm.frameCount - project.durationUs * 48000 / 1_000_000) <= 1024) { "Encoded audio duration does not match the timeline" }
                val samples = pcm.read(2400, minOf(4800, pcm.frameCount.toInt() - 2400))
                val shorts = ShortArray(samples.size) { (samples[it] * 32767).toInt().toShort() }
                check(energy(shorts, 440.0) > 200 && energy(shorts, 880.0) > 200) { "Encoded audio lost a track" }
            }
            decoded.delete()
            report("${codec.label}: 9 edited frames, both cuts, RAW grade, and both audio tones verified")
        }
        val single = SequenceProject(name = "Single edited clip", width = 320, height = 180, rate = rate).append(raw).let { p ->
            p.copy(clips = p.clips.map { it.copy(durationUs = rate.time(3), grade = testGrade()) }).validated()
        }
        val singleExpected = ProjectFrameRenderer(context).use { it.render(single, 0).copy(Bitmap.Config.ARGB_8888, false)!! }
        val singleFile = File(dir, "single-edited.mp4")
        SequenceExporter.export(context, single, ExportSettings(bitrateMbps = 8), Uri.fromFile(singleFile)) { _, _ -> }
        MediaMetadataRetriever().use { reader ->
            reader.setDataSource(singleFile.path)
            val image = reader.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST) ?: error("Single-clip export is empty")
            check(error(image, singleExpected) < 20) { "Single-clip export differs from its edited preview" }
            image.recycle()
        }
        singleExpected.recycle()
        report("Single-clip export preserves the full current edit receipt")
        if (headless) {
            for ((width, height) in listOf(1280 to 720, raw.width to raw.height).distinct()) {
                val full = SequenceProject(name = "Main10 $width x $height", width = width, height = height, rate = rate).append(raw).let { p ->
                    p.copy(clips = p.clips.map { it.copy(durationUs = rate.time(2), grade = testGrade()) }).validated()
                }
                val tenBit = ExportSettings(codec = ExportVideoCodec.HEVC10, bitrateMbps = 24)
                check(tenBit.encoder(full) != null) { "No Main10 encoder candidate for $width x $height" }
                val output = File(dir, "main10-${width}x${height}.mp4")
                SequenceExporter.export(context, full, tenBit, Uri.fromFile(output)) { _, _ -> }
                report("Main10 edited export passed at $width x $height / 30 fps (2 frames, SPS-confirmed 10-bit)")
            }
        }
        val dng = File(dir, "original-frame.dng")
        CrawClip.open(context, bayer.uri)!!.use { clip ->
            ParcelFileDescriptor.open(dng, ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or ParcelFileDescriptor.MODE_WRITE_ONLY).use { fd ->
                check(clip.writeOriginalDng(0, fd.fd)) { "Original CinemaDNG writer failed" }
            }
        }
        dng.inputStream().use { check(it.read() == 73 && it.read() == 73 && it.read() == 42) { "Invalid DNG TIFF header" } }
        check(dng.length() > raw.width.toLong() * raw.height) { "DNG frame is truncated" }
        report("Original CinemaDNG frame written with the existing CRAW writer")
        if (!headless) {
        withContext(Dispatchers.Main) {
            context.startActivity(android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        delay(1000)
        val cancelledFile = File(dir, "queue-cancelled.mp4")
        val queuedFile = File(dir, "queue-edited.mp4")
        withContext(Dispatchers.Main) {
            ExportQueue.enqueue(context, project, ExportSettings(bitrateMbps = 8), Uri.fromFile(cancelledFile).toString())
            ExportQueue.enqueue(context, single, ExportSettings(bitrateMbps = 8), Uri.fromFile(queuedFile).toString())
        }
        var cancellationSent = false
        withTimeout(120000) {
            while (true) {
                val jobs = ExportQueue.jobs.filter { it.destination in listOf(Uri.fromFile(cancelledFile).toString(), Uri.fromFile(queuedFile).toString()) }
                val firstJob = jobs.first { it.destination == Uri.fromFile(cancelledFile).toString() }
                if (!cancellationSent && firstJob.status == "Exporting") {
                    withContext(Dispatchers.Main) { ExportQueue.cancel(context, firstJob.id) }; cancellationSent = true
                }
                val secondJob = jobs.first { it.destination == Uri.fromFile(queuedFile).toString() }
                check(secondJob.status != "Failed") { "Queued export failed: ${secondJob.message}" }
                if (secondJob.status == "Completed") { check(firstJob.status == "Cancelled") { "Queue cancellation did not complete" }; break }
                delay(100)
            }
        }
        check(queuedFile.length() > 1000)
        val queueJson = org.json.JSONArray(File(context.filesDir, "export-jobs.json").readText())
        check((0 until queueJson.length()).any { queueJson.getJSONObject(it).getString("destination") == Uri.fromFile(queuedFile).toString() })
        withContext(Dispatchers.Main) {
            ExportQueue.jobs.removeAll { it.destination in listOf(Uri.fromFile(cancelledFile).toString(), Uri.fromFile(queuedFile).toString()) }
            ExportQueue.persist(context)
        }
        report("Foreground queue persists snapshots and continues after cancelling the preceding job")
        } else report("Headless: foreground queue/UI launch skipped")
        expected.forEach { it.recycle() }
        File(context.filesDir, "projects/${project.id}.json").delete()
        report("Review artifacts: ${dir.path}")
    }
    /**
     * Vulkan MLV-App grade vs MLV-App's own CPU code (-e gpuparity true): the
     * same MHC frame through both, each per-pixel stage alone and all together.
     * Reports max / mean |diff| in 16-bit units over the processed RGB16.
     */
    private fun bayerSource(): Uri = colorSource?.let(Uri::parse) ?: run {
        val root = targetContext.getSharedPreferences("gallery", 0).getString("tree", null) ?: error("Choose a source folder first")
        listTakes(targetContext, Uri.parse(root)).firstOrNull { t -> CrawClip.open(targetContext, t.uri)?.use { !it.info.rgb } == true }?.uri
            ?: error("Test requires a Bayer take")
    }

    /** Focused real-device checks for display transforms, fallback, playback and 10-bit export. */
    private suspend fun verifyMain10HighRes() {
        val context = targetContext
        val root = context.getSharedPreferences("gallery", 0).getString("tree", null) ?: error("Choose a source folder in the library first")
        val take = listTakes(context, Uri.parse(root)).firstOrNull { t ->
            CrawClip.open(context, t.uri)?.use { !it.info.rgb && it.info.width >= 3840 && it.info.frames >= 90 } == true
        } ?: error("Test requires a full-resolution (>= 3840 wide) Bayer take of >= 3 s")
        val dir = context.getExternalFilesDir("project-review")!!.apply { mkdirs() }
        val raw = ProjectMedia.importRaw(context, take.uri).copy(hasAudio = false)
        val rate = FrameRate(30)
        val frames = 90L
        for (info in android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!info.isEncoder || "video/hevc" !in info.supportedTypes) continue
            val caps = info.getCapabilitiesForType("video/hevc")
            val main10 = caps.profileLevels.any { it.profile == android.media.MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
            val v = caps.videoCapabilities ?: continue
            report("Encoder ${info.name}: Main10 advertised=$main10, max ${v.supportedWidths.upper}x${v.supportedHeights.upper}, " +
                "4080x2040@30 ${v.areSizeAndRateSupported(4080, 2040, 30.0)}, 3840x2160@30 ${v.areSizeAndRateSupported(3840, 2160, 30.0)}")
        }
        for ((width, height) in listOf(raw.width to raw.height, 3840 to 2160).distinct()) {
            val project = SequenceProject(name = "Main10 $width x $height", width = width, height = height, rate = rate).append(raw).let { p ->
                p.copy(clips = p.clips.map { it.copy(durationUs = rate.time(frames), grade = testGrade()) }).validated()
            }
            val settings = ExportSettings(codec = ExportVideoCodec.HEVC10, bitrateMbps = 100)
            val encoder = settings.encoder(project) ?: error("No Main10 encoder candidate for $width x $height")
            val out = settings.output(project)
            report("Requested $width x $height -> output ${out.width} x ${out.height}")
            val expected = ProjectFrameRenderer(context).use { r ->
                listOf(0L, 45L, 89L).map { f -> r.render(project, f).copy(Bitmap.Config.ARGB_8888, false)!! }
            }
            val output = File(dir, "main10-hires-${width}x${height}.mp4")
            val t0 = android.os.SystemClock.elapsedRealtime()
            SequenceExporter.export(context, project, settings, Uri.fromFile(output)) { _, _ -> }
            val seconds = (android.os.SystemClock.elapsedRealtime() - t0) / 1000.0
            val reader = MediaExtractor()
            val bits = try {
                reader.setDataSource(output.path)
                val v = (0 until reader.trackCount).first { reader.getTrackFormat(it).getString("mime") == "video/hevc" }
                val f = reader.getTrackFormat(v)
                check(f.getInteger("width") == out.width && f.getInteger("height") == out.height) { "Output size ${f.getInteger("width")}x${f.getInteger("height")}" }
                val csd = f.getByteBuffer("csd-0")!!.duplicate()
                HevcBitstream.bitDepth(ByteArray(csd.remaining()).also { csd.get(it) })
            } finally { reader.release() }
            check(bits == (10 to 10)) { "Bitstream is not 10-bit at $width x $height: $bits" }
            var count = 0
            MediaExtractor().apply { setDataSource(output.path); selectTrack((0 until trackCount).first { getTrackFormat(it).getString("mime")!!.startsWith("video/") }) }.also { e ->
                while (e.sampleTime >= 0) { count++; e.advance() }
                e.release()
            }
            check(count == frames.toInt()) { "Expected $frames frames, found $count" }
            val errors = MediaMetadataRetriever().use { m ->
                m.setDataSource(output.path)
                listOf(0L, 1_500_000L, 2_966_667L).mapIndexed { n, t ->
                    val b = m.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST) ?: error("Missing frame at $t")
                    error(b, expected[n]).also { b.recycle() }
                }
            }
            expected.forEach { it.recycle() }
            check(errors.all { it < 20 }) { "Frames differ from graded preview at $width x $height: MAE $errors" }
            report("Main10 ${out.width} x ${out.height} via $encoder: $count frames in %.1f s (%.1f fps), SPS 10/10, %.1f MB, MAE vs graded preview %s"
                .format(seconds, count / seconds, output.length() / 1e6, errors.joinToString("/") { "%.1f".format(it) }))
        }
        report("Review artifacts: ${dir.path}")
    }

    private fun testGrade() = RapidGradeSnapshot(RapidDefaults.schema.defaults().withValue("exposure", 1.0).withValue("contrast", 13.0).withValue("saturation", 15.0).encode())
    private fun tone(context: android.content.Context, sampleRate: Int, frequency: Double, amplitude: Double, duration: Long): SourceAsset {
        val source = SourceAsset(uri = "file:///test-tone", name = "Tone ${frequency.toInt()} Hz.wav", kind = AssetKind.AUDIO, durationUs = duration, hasAudio = true)
        val file = ProjectMedia.audioFile(context, source); file.parentFile!!.mkdirs()
        val frames = duration * sampleRate / 1_000_000
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(0); output.write(ByteArray(44))
            repeat(frames.toInt()) { n -> output.writeShort(java.lang.Short.reverseBytes((sin(2 * PI * frequency * n / sampleRate) * amplitude * 32767).toInt().toShort()).toInt()) }
            AudioImport.writeWavHeader(output, sampleRate, 1, frames * 2)
        }
        return source.copy(uri = Uri.fromFile(file).toString(), audioFile = file.name)
    }
    private fun energy(pcm: ShortArray, frequency: Double): Double {
        var real = 0.0; var imaginary = 0.0
        repeat(pcm.size / 2) { n -> val phase = 2 * PI * frequency * n / 48000; real += pcm[n * 2] * cos(phase); imaginary += pcm[n * 2] * sin(phase) }
        return sqrt(real * real + imaginary * imaginary) / (pcm.size / 2)
    }
    private fun verifyVideo(file: File, expected: List<Bitmap>, count: Int) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val v = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString("mime")!!.startsWith("video/") }
            extractor.selectTrack(v)
            var frames = 0; var last = -1L
            while (extractor.sampleTime >= 0) { check(extractor.sampleTime > last); last = extractor.sampleTime; frames++; extractor.advance() }
            check(frames == count) { "Expected $count frames, found $frames" }
        } finally { extractor.release() }
        MediaMetadataRetriever().use { reader ->
            reader.setDataSource(file.path)
            listOf(0L, 100000L, 200000L).forEachIndexed { n, time ->
                val bitmap = reader.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST) ?: error("Missing exported frame")
                val difference = error(bitmap, expected[n]); check(difference < 20) { "Export frame at $time differs from graded preview: MAE $difference" }
                bitmap.recycle()
            }
        }
    }
    private fun error(a: Bitmap, b: Bitmap): Double {
        val scaled = if (a.width == b.width && a.height == b.height) a else Bitmap.createScaledBitmap(a,b.width,b.height,true)
        val x=IntArray(b.width*b.height);val y=IntArray(x.size)
        scaled.getPixels(x,0,b.width,0,0,b.width,b.height);b.getPixels(y,0,b.width,0,0,b.width,b.height)
        var sum=0L; for (i in x.indices) for (shift in listOf(0,8,16)) sum += abs((x[i] shr shift and 255)-(y[i] shr shift and 255))
        if (scaled !== a) scaled.recycle()
        return sum.toDouble()/(x.size*3)
    }
    private fun mean(b: Bitmap): Double {
        val pixels=IntArray(b.width*b.height);b.getPixels(pixels,0,b.width,0,0,b.width,b.height)
        return pixels.sumOf { ((it shr 16 and 255)+(it shr 8 and 255)+(it and 255)).toDouble()/3 }/pixels.size
    }
}
