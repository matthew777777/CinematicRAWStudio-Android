package com.matthew.crs.rapidraw
import androidx.compose.runtime.staticCompositionLocalOf
internal class RapidEditGesture(val begin: ()->Unit={},val end: ()->Unit={})
internal val LocalRapidEditGesture=staticCompositionLocalOf { RapidEditGesture() }
