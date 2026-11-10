// SPDX-License-Identifier: AGPL-3.0-or-later
// Arithmetic and ordering ported from pinned Curves.tsx/buildParametricPoints.
package com.matthew.crs.rapidraw
import org.json.JSONObject
import kotlin.math.*
fun buildParametricPoints(settings: JSONObject): List<RapidPoint> {
    val vH=settings.getDouble("highlights")/100; val vL=settings.getDouble("lights")/100
    val vD=settings.getDouble("darks")/100; val vS=settings.getDouble("shadows")/100
    val s1=settings.getDouble("split1")/100; val s2=settings.getDouble("split2")/100; val s3=settings.getDouble("split3")/100
    val xH=(s3+1)/2; val xS=s1/2
    fun response(v: Double,x: Double): Double { val headroom=if(v>=0) 1-x else x; return tanh(v*1.2)*0.35*sqrt(headroom) }
    val xs=listOf(0.0,xS,s1,s2,s3,xH,1.0)
    val ys=listOf(0.0,xS+response(vS,xS),s1+(response(vS,s1)+response(vD,s1))/2,
        s2+(response(vD,s2)+response(vL,s2))/2,s3+(response(vL,s3)+response(vH,s3))/2,xH+response(vH,xH),1.0)
    return xs.indices.map { i -> RapidPoint(xs[i]*255,(ys[i].coerceIn(0.0,1.0)*255+
        if(i==0) settings.getDouble("blackLevel") else if(i==xs.lastIndex) settings.getDouble("whiteLevel") else 0.0).coerceIn(0.0,255.0)) }
}
