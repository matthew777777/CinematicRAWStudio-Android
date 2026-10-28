// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs.project

import android.content.Context
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
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.matthew.crs.CrawClip
import com.matthew.crs.ReceiptStore
import kotlinx.coroutines.*
import org.json.JSONObject

object OriginalDngExporter {
    suspend fun export(context: Context, project: SequenceProject, destination: Uri, progress: (Float, String) -> Unit) {
        require(project.assets.size == 1 && project.assets.single().kind == AssetKind.CRAW) { "Original CinemaDNG conversion accepts one source take per job" }
        val source = project.assets.single()
        val tree = DocumentFile.fromTreeUri(context, destination) ?: error("Choose a writable destination folder")
        require(tree.canWrite()) { "Destination folder is no longer writable" }
        val base = source.name.substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9_-]"), "_").take(70)
        val folder = tree.createDirectory("${base}_${newId().take(8)}") ?: error("Cannot create CinemaDNG folder")
        try {
            CrawClip.open(context, Uri.parse(source.uri))?.use { clip ->
                for (frame in 0 until clip.info.frames) {
                    currentCoroutineContext().ensureActive()
                    val file = folder.createFile("image/x-adobe-dng", "${base}_%06d.dng".format(frame)) ?: error("Cannot create DNG frame")
                    context.contentResolver.openFileDescriptor(file.uri, "w")?.use { fd ->
                        check(clip.writeOriginalDng(frame, fd.fd)) { "Cannot write original DNG frame ${frame + 1}" }
                    } ?: error("Cannot write DNG frame")
                    progress((frame + 1).toFloat() / clip.info.frames * 0.97f, "CinemaDNG frame ${frame + 1} / ${clip.info.frames}")
                }
                currentCoroutineContext().ensureActive()
                if (source.hasAudio) {
                    val wav = ProjectMedia.ensureAudio(context, source)
                    val file = folder.createFile("audio/wav", "$base.wav") ?: error("Cannot create original audio file")
                    context.contentResolver.openOutputStream(file.uri, "w")?.use { output -> wav.inputStream().use { it.copyTo(output) } }
                        ?: error("Cannot write original audio")
                }
                val receipt = ReceiptStore.load(context, source.uri)
                val manifest = JSONObject().put("format", "Original CinemaDNG").put("sourceName", source.name)
                    .put("frames", clip.info.frames).put("fps", clip.info.fps).put("imageProcessingApplied", false)
                    .put("note", "Original source samples and capture calibration. Editing adjustments are not baked into these DNG files.")
                receipt?.let { manifest.put("editorReceipt", JSONObject(it.toJson())) }
                val sidecar = folder.createFile("application/json", "conversion.json") ?: error("Cannot create conversion manifest")
                context.contentResolver.openOutputStream(sidecar.uri, "w")?.use { it.write(manifest.toString(2).toByteArray()) }
                    ?: error("Cannot write conversion manifest")
            } ?: error("Cannot open ${source.name}. Select the source again to grant access.")
            progress(1f, "Original CinemaDNG sequence saved")
        } catch (failure: Throwable) { runCatching { folder.delete() }; throw failure }
    }
}

@Composable
fun CinemaDngPanel(uris: List<Uri>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var queued by remember { mutableStateOf(false) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) scope.launch {
            busy = true; error = null
            try {
                context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                val sources = withContext(Dispatchers.IO) { uris.map { ProjectMedia.importRaw(context, it) } }
                sources.forEach { source ->
                    val project = SequenceProject(name = source.name.removeSuffix(".craw"), rate = FrameRate.fromFps(source.fps)).append(source)
                    ExportQueue.enqueue(context, project, ExportSettings(output = ExportOutput.ORIGINAL_CINEMA_DNG), tree.toString())
                }
                queued = true
            } catch (failure: Exception) { error = failure.message ?: "Cannot prepare CinemaDNG conversion" }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Original CinemaDNG") }, text = {
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text("${uris.size} selected takes")
            Text("Exports original source frames with their camera calibration and original WAV audio. Each take gets its own folder.", Modifier.padding(top = 12.dp), fontSize = 13.sp)
            Text("For grading later. Editor adjustments are not baked in; an edit receipt is included when available.", Modifier.padding(top = 12.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp)); Text("Preparing source files…", fontSize = 12.sp) }
            if (queued) {
                Text("Added to export jobs", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
                ExportQueue.jobs.filter { it.settings.output == ExportOutput.ORIGINAL_CINEMA_DNG }.takeLast(uris.size).forEach { job ->
                    Text("${job.project.name} · ${job.status}", fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                    if (job.status == "Exporting") LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth())
                    if (job.status in listOf("Queued", "Exporting")) TextButton(onClick = { ExportQueue.cancel(context, job.id) }) { Text("Cancel conversion") }
                    if (job.status == "Failed") Text(job.message, color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
        }
    }, confirmButton = { if (!queued) TextButton(onClick = { folder.launch(null) }, enabled = !busy) { Text("Choose output folder") } else TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { if (!queued) TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } })
}
