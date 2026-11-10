// SPDX-License-Identifier: AGPL-3.0-or-later
package com.matthew.crs.rapidraw
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import org.json.JSONArray
import java.util.UUID

@Composable internal fun RapidMaskPanel(grade: RapidRawGrade,width: Int,height: Int,change: (RapidRawGrade)->Unit) {
    val masks=grade.value("masks") as JSONArray
    var selected by remember { mutableIntStateOf(0) }
    fun replace(index: Int,value: JSONObject?) {
        val next=JSONArray();repeat(masks.length()) { i -> if(i!=index)next.put(masks.getJSONObject(i)) else if(value!=null)next.put(value) }
        change(grade.withValue("masks",next))
    }
    Text("Local adjustments",style=MaterialTheme.typography.titleMedium)
    Row(Modifier.horizontalScroll(rememberScrollState())) { listOf("radial","linear","brush","all").forEach { type ->
        TextButton(onClick={
            val params=when(type) {
                "radial" -> JSONObject().put("centerX",width/2.0).put("centerY",height/2.0).put("radiusX",width/3.0).put("radiusY",height/3.0).put("rotation",0).put("feather",0.5)
                "linear" -> JSONObject().put("startX",0).put("startY",height/2.0).put("endX",width).put("endY",height/2.0).put("range",50)
                "brush" -> JSONObject().put("lines",JSONArray())
                else -> JSONObject()
            }
            val sub=JSONObject().put("id",UUID.randomUUID().toString()).put("type",type).put("visible",true).put("invert",false).put("opacity",100).put("mode","additive").put("parameters",params)
            val adjustments=JSONObject(RapidDefaults.schema.defaults().encode()).getJSONObject("parameters")
            val mask=JSONObject().put("id",UUID.randomUUID().toString()).put("name","${type.replaceFirstChar { it.uppercase() }} ${masks.length()+1}")
                .put("visible",true).put("invert",false).put("opacity",100).put("adjustments",adjustments).put("subMasks",JSONArray().put(sub))
            selected=masks.length();change(grade.withValue("masks",JSONArray(masks.toString()).put(mask)))
        }) {Text("+ $type")}
    } }
    if(masks.length()==0) {Text("Add a mask, then adjust the selected area.");return}
    val index=selected.coerceIn(0,masks.length()-1)
    val mask=masks.getJSONObject(index)
    Row(Modifier.horizontalScroll(rememberScrollState())) {repeat(masks.length()) { i ->
        FilterChip(i==index,{selected=i},label={Text(masks.getJSONObject(i).optString("name","Mask ${i+1}"))})
    } }
    Row {
        Checkbox(mask.optBoolean("visible",true),{replace(index,JSONObject(mask.toString()).put("visible",it))});Text("Visible")
        Checkbox(mask.optBoolean("invert",false),{replace(index,JSONObject(mask.toString()).put("invert",it))});Text("Invert")
        TextButton(onClick={replace(index,null);selected=0}) {Text("Delete")}
    }
    RapidValueControl("Opacity",mask.optDouble("opacity",100.0),ParameterRange(0.0,100.0,1.0)) {replace(index,JSONObject(mask.toString()).put("opacity",it))}
    val subs=mask.getJSONArray("subMasks")
    if(subs.length()>0) {
        val sub=subs.getJSONObject(0); val params=sub.getJSONObject("parameters")
        fun updateParams(value: JSONObject) {
            val next=JSONObject(mask.toString());next.getJSONArray("subMasks").getJSONObject(0).put("parameters",value);replace(index,next)
        }
        if(sub.getString("type")=="brush") RapidBrushCanvas(params,width,height,::updateParams)
        else RapidValueControl("Mask shape",params) {updateParams(it as JSONObject)}
    }
    HorizontalDivider()
    Text("Mask grade",style=MaterialTheme.typography.titleSmall)
    val adjustments=mask.getJSONObject("adjustments")
    for(key in listOf("exposure","contrast","highlights","shadows","whites","blacks","brightness","saturation","temperature","tint","vibrance","hue","clarity","dehaze","structure","sharpness","lumaNoiseReduction","colorNoiseReduction","colorGrading","hsl")) {
        RapidValueControl(key,adjustments.get(key),RapidDefaults.schema.range(key)) {
            val next=JSONObject(mask.toString());next.getJSONObject("adjustments").put(key,it);replace(index,next)
        }
    }
}
@Composable private fun RapidBrushCanvas(params: JSONObject,width: Int,height: Int,change: (JSONObject)->Unit) {
    var points by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var brush by remember { mutableFloatStateOf(minOf(width,height)/12f) }
    Text("Brush · original source coordinates",style=MaterialTheme.typography.labelMedium)
    Slider(brush,{brush=it},valueRange=1f..minOf(width,height).toFloat().coerceAtLeast(2f))
    val latest by rememberUpdatedState(params)
    val onChange by rememberUpdatedState(change)
    Canvas(Modifier.fillMaxWidth().height(180.dp).background(Color(0xFF20252B)).pointerInput(width,height,brush) {
        fun source(p: Offset)=Offset((p.x/size.width*width).coerceIn(0f,width.toFloat()),(p.y/size.height*height).coerceIn(0f,height.toFloat()))
        detectDragGestures(onDragStart={points=listOf(source(it))},onDragCancel={points=emptyList()},onDragEnd={
            if(points.isNotEmpty()) {
                val line=JSONObject().put("tool","brush").put("brushSize",brush).put("feather",0.5)
                    .put("points",JSONArray(points.map {JSONObject().put("x",it.x.toDouble()).put("y",it.y.toDouble())}))
                val next=JSONObject(latest.toString());next.put("lines",JSONArray(next.optJSONArray("lines")?.toString() ?: "[]").put(line));onChange(next)
            };points=emptyList()
        },onDrag={event,_ -> event.consume();points=points+source(event.position)})
    }) {
        fun drawStroke(ps: List<Offset>,diameter: Float) {
            val scaled=ps.map {Offset(it.x/width*size.width,it.y/height*size.height)}
            scaled.forEach {drawCircle(Color.White.copy(alpha=.55f),diameter/width*size.width/2,it)}
            scaled.zipWithNext().forEach {(a,b)->drawLine(Color.White.copy(alpha=.55f),a,b,diameter/width*size.width)}
        }
        params.optJSONArray("lines")?.let {lines ->repeat(lines.length()) {i ->val l=lines.getJSONObject(i);val ps=l.getJSONArray("points")
            drawStroke((0 until ps.length()).map {ps.getJSONObject(it).let {p->Offset(p.getDouble("x").toFloat(),p.getDouble("y").toFloat())}},l.getDouble("brushSize").toFloat())}}
        drawStroke(points,brush)
    }
    TextButton(onClick={change(JSONObject(params.toString()).put("lines",JSONArray()))}) {Text("Clear brush")}
}
