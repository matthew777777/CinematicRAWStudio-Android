// SPDX-License-Identifier: AGPL-3.0-or-later
package com.matthew.crs.rapidraw
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject
import kotlinx.coroutines.*

@Composable internal fun RapidResourceControls(section: RapidSection,grade: RapidRawGrade,change: (RapidRawGrade)->Unit) {
    if(section!=RapidSection.Effects) return
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) };var error by remember { mutableStateOf<String?>(null) }
    val latest by rememberUpdatedState(grade)
    val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) {
        busy=true;error=null
        scope.launch {
            try {
                val path=withContext(Dispatchers.IO) {RapidResourceStore.importLut(context,uri)}
                change(latest.withValue("lutPath",path).withValue("lutName",com.matthew.crs.project.ProjectMedia.name(context,uri)))
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(failure: Exception) {error=failure.message}
            finally {busy=false}
        }
    } }
    TextButton(enabled=!busy,onClick={pick.launch(arrayOf("*/*"))}) {Text(if(busy) "Importing LUT…" else "Import LUT")}
    if(grade.value("lutPath")!==JSONObject.NULL) {
        Text(grade.value("lutName").toString(),style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={change(grade.withValue("lutPath",JSONObject.NULL).withValue("lutName",JSONObject.NULL).withValue("lutData",JSONObject.NULL).withValue("lutSize",0))}) {Text("Remove LUT")}
    }
    error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
}
