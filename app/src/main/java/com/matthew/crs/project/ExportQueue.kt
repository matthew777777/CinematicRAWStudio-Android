// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs.project

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.AtomicFile
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persisted snapshots: later edits to a source/project cannot change an already queued job. */
data class ExportJob(val id: String = newId(), val project: SequenceProject, val settings: ExportSettings,
    val destination: String, val status: String = "Queued", val progress: Float = 0f, val message: String = "")
object ExportQueue {
    val jobs = mutableStateListOf<ExportJob>()
    private var loaded = false
    @Volatile var activeTask: Job? = null
    val running: Boolean get() = jobs.any { it.status == "Exporting" || it.status == "Queued" }
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "export-jobs.json"))
    @Synchronized fun load(context: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val array = JSONArray(file(context).readFully().toString(Charsets.UTF_8))
            repeat(array.length()) { i ->
                val o = array.getJSONObject(i); val s = o.getJSONObject("settings")
                val old = o.getString("status")
                jobs += ExportJob(id = o.getString("id"), project = ProjectStore.decode(o.getJSONObject("project").toString()),
                    settings = ExportSettings(ExportVideoCodec.valueOf(s.getString("codec")), s.getInt("height"), s.getInt("bitrate"), s.getBoolean("audio"), ExportOutput.valueOf(s.optString("output", "MP4"))),
                    destination = o.getString("destination"), status = if (old == "Exporting") "Interrupted" else old,
                    message = if (old == "Exporting") "Export was interrupted. Retry to render the saved snapshot." else o.optString("message"),
                    progress = o.optDouble("progress", 0.0).toFloat())
            }
        }
    }
    @Synchronized fun persist(context: Context) {
        val array = JSONArray(jobs.map { j -> JSONObject().apply {
            put("id", j.id); put("project", JSONObject(ProjectStore.encode(j.project)))
            put("settings", JSONObject().put("codec", j.settings.codec.name).put("height", j.settings.height)
                .put("bitrate", j.settings.bitrateMbps).put("audio", j.settings.audio).put("output", j.settings.output.name))
            put("destination", j.destination); put("status", j.status); put("progress", j.progress.toDouble()); put("message", j.message)
        } })
        val f = file(context); val stream = f.startWrite()
        try { stream.write(array.toString().toByteArray()); f.finishWrite(stream) }
        catch (error: Throwable) { f.failWrite(stream); throw error }
    }
    fun enqueue(context: Context, project: SequenceProject, settings: ExportSettings, destination: String) {
        load(context)
        require(settings.output == ExportOutput.ORIGINAL_CINEMA_DNG || settings.encoder(project) != null) { "Selected export settings are unsupported" }
        jobs += ExportJob(project = project.validated(), settings = settings, destination = destination)
        persist(context)
        context.startForegroundService(Intent(context, ExportService::class.java))
    }
    fun update(context: Context, id: String, persist: Boolean = true, change: (ExportJob) -> ExportJob) {
        val i = jobs.indexOfFirst { it.id == id }
        if (i >= 0) jobs[i] = change(jobs[i])
        if (persist) persist(context)
    }
    fun cancel(context: Context, id: String) {
        val job = jobs.firstOrNull { it.id == id } ?: return
        if (job.status == "Exporting") activeTask?.cancel()
        else update(context, id) { it.copy(status = "Cancelled", message = "Cancelled before export") }
    }
    fun retry(context: Context, id: String) {
        update(context, id) { it.copy(status = "Queued", progress = 0f, message = "") }
        context.startForegroundService(Intent(context, ExportService::class.java))
    }
}

class ExportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var worker: Job? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("exports", "Video exports", NotificationManager.IMPORTANCE_LOW))
        wakeLock = getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "$packageName:export").apply {
            setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L)
        }
        ExportQueue.load(this)
        startForeground(410, notification("Preparing export", 0))
    }
    private fun notification(text: String, progress: Int): Notification = Notification.Builder(this, "exports")
        .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("Cinematic RAW Studio")
        .setContentText(text).setOnlyAlertOnce(true).setOngoing(true).setProgress(100, progress, false)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, com.matthew.crs.MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker?.isActive != true) worker = scope.launch {
            while (true) {
                val job = ExportQueue.jobs.firstOrNull { it.status == "Queued" } ?: break
                ExportQueue.update(this@ExportService, job.id) { it.copy(status = "Exporting", message = "Preparing sources") }
                val task = scope.async(Dispatchers.IO) {
                    var last = 0L
                    SequenceExporter.export(this@ExportService, job.project, job.settings, android.net.Uri.parse(job.destination)) { fraction, phase ->
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - last >= 200 || fraction >= 1f) {
                            last = now
                            scope.launch {
                                ExportQueue.update(this@ExportService, job.id, persist = false) { it.copy(progress = fraction, message = phase) }
                                getSystemService(NotificationManager::class.java).notify(410, notification("${job.project.name} · $phase", (fraction * 100).toInt()))
                            }
                        }
                    }
                }
                ExportQueue.activeTask = task
                try { task.await(); ExportQueue.update(this@ExportService, job.id) { it.copy(status = "Completed", progress = 1f, message = if (job.settings.output == ExportOutput.ORIGINAL_CINEMA_DNG) "Original CinemaDNG sequence saved" else "Saved MP4") } }
                catch (cancelled: CancellationException) {
                    if (!scope.isActive) throw cancelled
                    ExportQueue.update(this@ExportService, job.id) { it.copy(status = "Cancelled", message = "Export cancelled") }
                } catch (failure: Exception) { ExportQueue.update(this@ExportService, job.id) { it.copy(status = "Failed", message = failure.message ?: "Export failed") } }
                finally { ExportQueue.activeTask = null }
            }
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { ExportQueue.activeTask?.cancel(); stopSelf() }
    override fun onDestroy() { scope.cancel(); wakeLock?.let { if (it.isHeld) it.release() }; super.onDestroy() }
}
