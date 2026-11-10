// SPDX-License-Identifier: AGPL-3.0-or-later
package com.matthew.crs.rapidraw
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import kotlin.math.*

@Composable internal fun RapidWheelControl(value: JSONObject,change: (Any)->Unit) {
    val latest by rememberUpdatedState(value)
    val onChange by rememberUpdatedState(change)
    val gesture=LocalRapidEditGesture.current
    Canvas(Modifier.size(180.dp).pointerInput(Unit) {
        fun set(p: Offset) {
            val x=p.x-size.width/2f;val y=p.y-size.height/2f
            val hue=(atan2(y,x)*180f/PI.toFloat()+360f)%360f
            val saturation=(sqrt(x*x+y*y)/(minOf(size.width,size.height)/2f)*100f).coerceIn(0f,100f)
            onChange(JSONObject(latest.toString()).put("hue",hue.toDouble()).put("saturation",saturation.toDouble()))
        }
        detectDragGestures(onDragStart={gesture.begin();set(it)},onDragEnd={gesture.end()},onDragCancel={gesture.end()},onDrag={event,_->event.consume();set(event.position)})
    }) {
        val colors=listOf(Color.Red,Color.Yellow,Color.Green,Color.Cyan,Color.Blue,Color.Magenta,Color.Red)
        drawCircle(Brush.sweepGradient(colors),size.minDimension/2)
        drawCircle(Brush.radialGradient(listOf(Color.White,Color.Transparent),radius=size.minDimension/2),size.minDimension/2)
        val angle=latest.getDouble("hue")*PI/180
        val r=latest.getDouble("saturation")/100*size.minDimension/2
        val p=center+Offset((cos(angle)*r).toFloat(),(sin(angle)*r).toFloat())
        drawCircle(Color.Black,6.dp.toPx(),p);drawCircle(Color.White,4.dp.toPx(),p)
    }
}
