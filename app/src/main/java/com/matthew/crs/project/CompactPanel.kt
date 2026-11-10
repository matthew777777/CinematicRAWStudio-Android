// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs.project
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

@Composable fun CompactPanel(uris: List<Uri>,onDismiss: ()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var busy by remember {mutableStateOf(false)};var error by remember {mutableStateOf<String?>(null)}
    val jobs=remember {mutableStateListOf<String>()}
    fun enqueue(grant: Uri?) {
        if(busy)return
        busy=true;error=null
        scope.launch {
            try {
                if(grant!=null)context.contentResolver.takePersistableUriPermission(grant,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                val sources=withContext(Dispatchers.IO) {uris.map {CompactConverter.prepare(context,it,grant)}}
                for((project,parent) in sources) {
                    ExportQueue.enqueue(context,project,ExportSettings(output=ExportOutput.CRAW_COMPACT),parent.toString())
                    jobs.add(ExportQueue.jobs.last().id)
                }
            } catch(failure: Exception) {error=failure.message}
            finally {busy=false}
        }
    }
    val folder=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) {if(it!=null)enqueue(it)}
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text("CRAW Compact")},text={
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) {
            Text("${uris.size} selected takes")
            Text("Creates a lossless Compact copy in the same directory as each original. All frames, metadata, audio and motion data are preserved.",Modifier.padding(top=12.dp))
            Text("Original files stay until the new files pass full verification. Then you can choose whether to remove the originals.",Modifier.padding(top=12.dp))
            Text("Compact is an offline conversion and can take time. Frames that are already smaller keep their original lossless payload.",Modifier.padding(top=12.dp),style=MaterialTheme.typography.bodySmall)
            if(busy)LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=12.dp))
            jobs.mapNotNull {id->ExportQueue.jobs.firstOrNull {it.id==id}}.forEach {job ->
                Text("${job.project.name} · ${job.status}",Modifier.padding(top=12.dp))
                if(job.status=="Exporting")LinearProgressIndicator(progress={job.progress},modifier=Modifier.fillMaxWidth())
                Text(job.message,style=MaterialTheme.typography.bodySmall)
                if(job.status in listOf("Queued","Exporting"))TextButton(onClick={ExportQueue.cancel(context,job.id)}) {Text("Cancel conversion")}
            }
            error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=12.dp))
                TextButton(enabled=!busy,onClick={folder.launch(null)}) {Text("Grant source-folder access")}}
        }
    },confirmButton={if(jobs.isEmpty())TextButton(enabled=!busy,onClick={enqueue(null)}) {Text("Convert beside originals")} else TextButton(onClick=onDismiss) {Text("Done")}},
        dismissButton={if(jobs.isEmpty())TextButton(enabled=!busy,onClick=onDismiss) {Text("Cancel")}})
}

/** Appears in the app only after a completed, fully verified Compact conversion. */
@Composable fun CompactOriginalRemovalPrompt() {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val pending=ExportQueue.jobs.firstOrNull(CompactRemovalPolicy::mayAsk)
    if(pending==null || ExportQueue.running)return
    key(pending.id) {
        var busy by remember {mutableStateOf(false)};var committing by remember {mutableStateOf(false)}
        var error by remember {mutableStateOf<String?>(null)};var task by remember {mutableStateOf<Job?>(null)}
        val result=pending.compact!!;val source=pending.project.assets.single()
        val codec=when(result.sourceCodec) {1->"Balanced";2->"Live v1 — Legacy";3->"Live";else->"Compact"}
        fun keep() {task?.cancel();ExportQueue.update(context,pending.id) {it.copy(sourceDecision="Kept")}}
        LaunchedEffect(pending.id) {
            val exists=withContext(Dispatchers.IO) {runCatching {CrawSiblingFiles.find(context,Uri.parse(source.uri),Uri.parse(pending.destination)).contains(Uri.parse(source.uri))}.getOrDefault(true)}
            if(!exists)ExportQueue.update(context,pending.id) {it.copy(sourceDecision="Original already absent")}
        }
        AlertDialog(onDismissRequest={if(!busy)keep()},title={Text("Remove original $codec take?")},text={
            Column {
                Text("${result.name} has passed every-frame RAW and container verification.")
                Text("Original: ${source.name}\n${"%.1f".format(result.sourceBytes/1e6)} MB → ${"%.1f".format(result.outputBytes/1e6)} MB",Modifier.padding(top=12.dp))
                Text("Keeping the original is optional. If you delete it, saved projects will continue using the verified Compact copy.",Modifier.padding(top=12.dp))
                if(result.outputBytes>=result.sourceBytes)Text("This conversion did not reduce the file size.",Modifier.padding(top=8.dp))
                if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=12.dp));Text(if(committing) "Removing original…" else "Rechecking both files before removal…")}
                error?.let {Text(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=12.dp))}
            }
        },confirmButton={TextButton(enabled=!busy,onClick={
            busy=true;error=null
            task=scope.launch {
                try {
                    withContext(Dispatchers.IO) {CompactConverter.removeOriginal(context,pending) {withContext(Dispatchers.Main) {committing=true}}}
                    ExportQueue.update(context,pending.id) {it.copy(sourceDecision="Removed",message="Compact verified; original removed")}
                } catch(cancelled: CancellationException) {throw cancelled}
                catch(failure: Exception) {error=failure.message ?: "Could not remove original"}
                finally {busy=false;committing=false}
            }
        }) {Text("Delete original $codec")}},dismissButton={TextButton(enabled=!committing,onClick=::keep) {Text(if(busy) "Cancel and keep original" else "Keep original")}})
    }
}
