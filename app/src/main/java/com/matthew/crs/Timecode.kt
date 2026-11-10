// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs
/** Non-drop-frame display timecode at the rounded nominal project rate. */
fun timecode(frame: Int,fps: Double): String {
    val rate=kotlin.math.round(fps).toInt().coerceAtLeast(1); val value=frame.coerceAtLeast(0)
    return "%02d:%02d:%02d:%02d".format(value/rate/3600,value/rate/60%60,value/rate%60,value%rate)
}
