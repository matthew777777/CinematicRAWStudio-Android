// SPDX-License-Identifier: AGPL-3.0-or-later
package com.matthew.crs

import android.content.Context
import androidx.compose.runtime.*
import com.matthew.crs.rapidraw.RapidGradeSnapshot

/** Versioned clipboard for complete RapidRAW grades. */
object SettingsClipboard {
    var copied by mutableStateOf<RapidGradeSnapshot?>(null); private set
    private var loaded = false
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        copied = context.getSharedPreferences("rapidraw_clipboard",0).getString("grade",null)?.let {
            runCatching { RapidGradeSnapshot(it) }.getOrNull()
        }
    }
    fun copy(context: Context, grade: RapidGradeSnapshot) {
        copied = grade
        context.getSharedPreferences("rapidraw_clipboard",0).edit().putString("grade",grade.encoded).apply()
    }
}
