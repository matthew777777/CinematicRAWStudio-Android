// SPDX-License-Identifier: AGPL-3.0-or-later
package com.matthew.crs.rapidraw

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.matthew.crs.SettingsClipboard
import com.matthew.crs.project.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray

/** Single-take editor shares the sequence scheduler/audio clock and the export renderer. */
@Composable fun RapidEditorScreen(onBack: () -> Unit,source: Uri?=null) {
    val context=LocalContext.current
    var project by remember(source) { mutableStateOf<SequenceProject?>(null) }
    var player by remember(source) { mutableStateOf<SequencePlayer?>(null) }
    var saved by rememberSaveable(source?.toString()) { mutableStateOf(RapidDefaults.schema.defaults().encode()) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    var export by remember { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf(RapidSection.Basic.name) }
    val undo=remember(source) { mutableStateListOf<String>() }
    val redo=remember(source) { mutableStateListOf<String>() }
    var continuous by remember { mutableStateOf(false) }
    var recorded by remember { mutableStateOf(false) }
    val gesture=RapidEditGesture(begin={if(!continuous){continuous=true;recorded=false}},end={continuous=false;recorded=false})
    val grade=remember(saved) { RapidDefaults.schema.decode(saved) }
    var chart by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    fun change(next: RapidRawGrade) {
        if(next.encode()==saved) return
        if(!continuous || !recorded) {undo.add(saved);if(undo.size>80)undo.removeAt(0);recorded=continuous}
        redo.clear(); saved=next.encode()
    }
    DisposableEffect(source) { onDispose { player?.close() } }
    LaunchedEffect(source) {
        if(source==null) return@LaunchedEffect
        try {
            val loaded=withContext(Dispatchers.IO) {
                val asset=ProjectMedia.importRaw(context,source)
                val stored=RapidGradeStore.load(context,source.toString(),RapidDefaults.schema) ?: RapidDefaults.schema.defaults()
                SequenceProject(name=asset.name.substringBeforeLast('.'),width=asset.width and -2,height=asset.height and -2,
                    rate=FrameRate.fromFps(asset.fps)).append(asset,grade=RapidGradeSnapshot(stored.encode()))
            }
            saved=loaded.clips.first().grade.encoded; project=loaded
            player=SequencePlayer(context,loaded).also { it.seek(0) }
        } catch(cancelled: CancellationException) { throw cancelled }
        catch(failure: Exception) { error=failure.message }
    }
    LaunchedEffect(saved,project?.id) {
        val current=project
        if(source!=null && current!=null) {
            val updated=current.copy(clips=current.clips.map { it.copy(grade=RapidGradeSnapshot(saved)) })
            project=updated; player?.update(updated)
            try { withContext(Dispatchers.IO) { RapidGradeStore.save(context,source.toString(),grade) } }
            catch(failure: Exception) { error="Cannot save grade: ${failure.message}" }
        } else if(source==null) {
            try {
                chart=withContext(Dispatchers.IO) { RapidComputeSession(128,72).use { compute ->
                    compute.uploadLinearFrame(RapidComputeSession.chart(128,72),true)
                    compute.render(grade,RapidSourceMetadata(true,6504.0,0.0))
                } }
            } catch(failure: Exception) { error=failure.message }
        }
    }
    LaunchedEffect(ExportQueue.running) { if(ExportQueue.running)player?.releaseForExport() else player?.let {it.seek(it.index)} }
    BackHandler(onBack=onBack)
    CompositionLocalProvider(LocalRapidEditGesture provides gesture) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),verticalAlignment=Alignment.CenterVertically) {
                TextButton(onClick=onBack) { Text("Back") }
                Text("RapidRAW",style=MaterialTheme.typography.titleMedium)
                TextButton(enabled=undo.isNotEmpty(),onClick={ redo.add(saved); saved=undo.removeAt(undo.lastIndex) }) { Text("Undo") }
                TextButton(enabled=redo.isNotEmpty(),onClick={ undo.add(saved); saved=redo.removeAt(redo.lastIndex) }) { Text("Redo") }
                TextButton(onClick={ SettingsClipboard.copy(context,RapidGradeSnapshot(saved)) }) { Text("Copy") }
                TextButton(onClick={ change(RapidDefaults.schema.defaults(grade.output)) }) { Text("Reset all") }
                TextButton(enabled=project!=null,onClick={ player?.pause(); export=true }) { Text("Export") }
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                val wide=maxWidth>=600.dp
                val preview: @Composable () -> Unit = {
                    Column(Modifier.fillMaxSize(),horizontalAlignment=Alignment.CenterHorizontally) {
                        Box(Modifier.weight(1f).fillMaxWidth().background(androidx.compose.ui.graphics.Color.Black),contentAlignment=Alignment.Center) {
                            (player?.frame ?: chart)?.let { Image(it.asImageBitmap(),"Graded frame",Modifier.fillMaxSize(),contentScale=ContentScale.Fit) }
                            if(player?.busy==true) CircularProgressIndicator()
                        }
                        (error ?: player?.error)?.let { Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(8.dp)) }
                        val p=project; val engine=player
                        if(p!=null && engine!=null) {
                            var seek by remember { mutableFloatStateOf(engine.index.toFloat()) }
                            var dragging by remember { mutableStateOf(false) }
                            LaunchedEffect(engine.index) { if(!dragging) seek=engine.index.toFloat() }
                            Slider(seek,{ dragging=true; seek=it; engine.seek(it.toLong()) },valueRange=0f..(p.frameCount-1).coerceAtLeast(1).toFloat(),
                                onValueChangeFinished={ dragging=false; engine.seek(seek.toLong()) },enabled=!ExportQueue.running,modifier=Modifier.padding(horizontal=12.dp))
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                TextButton(onClick={ engine.seek(engine.index-1) },enabled=!ExportQueue.running) { Text("‹ Frame") }
                                FilledTonalButton(onClick={ if(engine.playing)engine.pause() else engine.play() },enabled=!ExportQueue.running) { Text(if(engine.playing) "Pause" else "Play") }
                                TextButton(onClick={ engine.seek(engine.index+1) },enabled=!ExportQueue.running) { Text("Frame ›") }
                            }
                            Text("${engine.index+1} / ${p.frameCount} · ${"%.3f".format(p.rate.fps)} fps · proxy",style=MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                val controls: @Composable () -> Unit = {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.horizontalScroll(rememberScrollState())) { RapidSection.entries.forEach { item ->
                            FilterChip(selected=section==item.name,onClick={section=item.name},label={Text(item.title)})
                        } }
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            RapidPanel((RapidSection.entries.firstOrNull {it.name==section} ?: RapidSection.Basic),RapidDefaults.schema,grade,project?.assets?.firstOrNull()?.width ?: 128,project?.assets?.firstOrNull()?.height ?: 72,::change)
                        }
                    }
                }
                if(wide) Row { Box(Modifier.weight(1f)) { preview() }; Box(Modifier.width(380.dp)) { controls() } }
                else Column { Box(Modifier.weight(1f)) { preview() }; Box(Modifier.weight(1f)) { controls() } }
            }
        }
    }
    }
    if(export)ExportPanel(prepare={requireNotNull(project)},beforeStart={player?.pause()},onDismiss={export=false})
}

@Composable internal fun RapidPanel(section: RapidSection,schema: RapidSchema,grade: RapidRawGrade,sourceWidth: Int=128,sourceHeight: Int=72,change: (RapidRawGrade)->Unit) {
    if(section==RapidSection.Basic) {
        Text("Output transform",style=MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState())) { RapidOutput.entries.forEach { output ->
            FilterChip(selected=grade.output==output,onClick={change(grade.withOutput(output))},label={Text(output.title)})
        } }
    }
    val tools=when(section) {
        RapidSection.Basic -> listOf("basic")
        RapidSection.Curves -> listOf("curves")
        RapidSection.Color -> listOf("whiteBalance","colorPresence","hue","hsl","colorGrading","colorCalibration","monochrome")
        RapidSection.Details -> listOf("sharpening","presence","noiseReduction","chromaticAberration")
        RapidSection.Effects -> listOf("creative","grain","lut","vignette")
        else -> emptyList()
    }
    Row(Modifier.horizontalScroll(rememberScrollState())) { tools.forEach { tool -> FilterChip(grade.active(tool),{change(grade.withActive(tool,!grade.active(tool)))},label={Text(tool)}) } }
    TextButton(onClick={change(grade.reset(section))}) { Text("Reset ${section.title}") }
    RapidResourceControls(section,grade,change)
    if(section==RapidSection.Masks) { RapidMaskPanel(grade,sourceWidth,sourceHeight,change); return }
    val excluded=setOf("aiDenoiseEnabled","aiDenoiseMethod","aiDenoiseAmount","aiDenoiseDetail","negativeConversionEnabled","negativeRedWeight","negativeGreenWeight","negativeBlueWeight","negativeExposure","negativeContrast")
    if(section==RapidSection.Curves) RapidCurvePanel(grade,change) else schema.sectionKeys(section).filter { it !in excluded && !it.startsWith("lensBlur") && !it.startsWith("relight") && !it.startsWith("fog") }.forEach { key ->
        if(key !in setOf("lutName","lutPath","lutData","lutSize","lensBlurDepthMap","relightNormalMap","fogDepthMap") && (key!="toneMapper" || grade.output==RapidOutput.Upstream)) RapidValueControl(key,grade.value(key),schema.range(key)) { change(grade.withValue(key,it)) }
    }
}
@Composable internal fun RapidValueControl(label: String,value: Any,bounds: ParameterRange?=null,change: (Any)->Unit) {
    when(value) {
        is Boolean -> Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(value,{change(it)}); Text(label) }
        is Number -> {
            val number=value.toDouble()
            if(bounds!=null) {
                Text("$label · ${"%.2f".format(number)}",style=MaterialTheme.typography.labelMedium)
                val gesture=LocalRapidEditGesture.current
                Slider(number.toFloat(),{gesture.begin();change(it.toDouble())},onValueChangeFinished={gesture.end()},valueRange=bounds.min.toFloat()..bounds.max.toFloat())
            } else {
                var text by remember(value) { mutableStateOf(value.toString()) }
                OutlinedTextField(text,{ next -> text=next; next.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(change) },label={Text(label)},singleLine=true)
            }
        }
        is JSONObject -> {
            Text(label,style=MaterialTheme.typography.titleSmall)
            if(label in WheelRange.entries.map { it.key } && value.has("hue") && value.has("saturation") && value.has("luminance")) RapidWheelControl(value,change)
            value.keys().asSequence().toList().forEach { key ->
                val range=if(label in WheelRange.entries.map {it.key}) when(key) {
                    "hue" -> ParameterRange(0.0,360.0,1.0)
                    "saturation" -> ParameterRange(0.0,100.0,1.0)
                    "luminance" -> ParameterRange(-100.0,100.0,1.0)
                    else -> null
                } else null
                RapidValueControl(key,value.get(key),range) { next -> change(JSONObject(value.toString()).put(key,next)) }
            }
        }
        is String -> {
            val choices=when(label) { "toneMapper" -> listOf("basic","agx"); "monochromeFilm" -> listOf("neutral","panchromatic","balanced","orthoPanchromatic","extendedRed","orthochromatic","infrared"); else -> emptyList() }
            if(choices.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState())) { choices.forEach { item -> FilterChip(value==item,{change(item)},label={Text(item)}) } }
            else { var text by remember(value) { mutableStateOf(value) }; OutlinedTextField(text,{text=it;change(it)},label={Text(label)},singleLine=true) }
        }
        is JSONArray -> { Text("$label · ${value.length()} entries",style=MaterialTheme.typography.labelSmall) }
        else -> Text("$label · no resource selected",style=MaterialTheme.typography.labelSmall)
    }
}
@Composable private fun RapidCurvePanel(grade: RapidRawGrade,change: (RapidRawGrade)->Unit) {
    var channel by remember { mutableStateOf(CurveChannel.Luma) }
    Row { CurveChannel.entries.forEach { item -> FilterChip(item==channel,{channel=item},label={Text(item.key)}) } }
    Row { listOf("point","parametric").forEach { mode -> FilterChip(grade.value("curveMode")==mode,{change(grade.withCurveMode(mode))},label={Text(mode)}) } }
    if(grade.value("curveMode")=="parametric") {
        val settings=grade.at("parametricCurve.${channel.key}") as JSONObject
        settings.keys().forEach { key ->
            val range=when(key) {
                "split1" -> ParameterRange(10.0,settings.getDouble("split2")-10.0,1.0)
                "split2" -> ParameterRange(settings.getDouble("split1")+10.0,settings.getDouble("split3")-10.0,1.0)
                "split3" -> ParameterRange(settings.getDouble("split2")+10.0,90.0,1.0)
                else -> ParameterRange(-100.0,100.0,1.0)
            }
            RapidValueControl(key,settings.get(key),range) { change(grade.withParametric(channel,key,(it as Number).toDouble())) }
        }
    } else {
        val data=grade.at("pointCurves.${channel.key}") as JSONArray
        val points=(0 until data.length()).map { data.getJSONObject(it).let { p -> RapidPoint(p.getDouble("x"),p.getDouble("y")) } }
        points.forEachIndexed { index,p ->
            Row {
                Text("${p.x.toInt()}",Modifier.width(40.dp))
                Slider(p.y.toFloat(),{ value -> change(grade.withCurve(channel,points.mapIndexed { i,point -> if(i==index) point.copy(y=value.toDouble()) else point })) },valueRange=0f..255f,modifier=Modifier.weight(1f))
                if(index>0 && index<points.lastIndex) TextButton(onClick={change(grade.withCurve(channel,points.filterIndexed { i,_ -> i!=index }))}) { Text("−") }
            }
        }
        TextButton(enabled=points.size<16,onClick={
            val pair=points.zipWithNext().maxBy { it.second.x-it.first.x }
            change(grade.withCurve(channel,(points+RapidPoint((pair.first.x+pair.second.x)/2,(pair.first.y+pair.second.y)/2)).sortedBy { it.x }))
        }) { Text("Add point") }
    }
}
