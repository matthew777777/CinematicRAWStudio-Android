// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs.project

import android.content.Context
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class ProjectSession(private val context: Context, initial: SequenceProject) : AutoCloseable {
    var project by mutableStateOf(initial); private set
    var selectedId by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    var saved by mutableStateOf(true); private set
    var importing by mutableStateOf(false)
    val player = SequencePlayer(context, initial)
    val undo = mutableStateListOf<SequenceProject>()
    val redo = mutableStateListOf<SequenceProject>()
    val selected: SequenceClip? get() = project.clips.firstOrNull { it.id == selectedId }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saves = Channel<SequenceProject>(Channel.CONFLATED)
    init {
        scope.launch {
            for (snapshot in saves) {
                try { ProjectStore.save(context, snapshot); if (project == snapshot) saved = true }
                catch (failure: Exception) { error = "Project could not be saved: ${failure.message}" }
            }
        }.invokeOnCompletion { scope.cancel() }
        player.seek(0)
    }
    fun edit(transform: (SequenceProject) -> SequenceProject) {
        if (ExportQueue.running) { error = "Wait for the export queue or cancel its jobs before changing the project"; return }
        try {
            val next = transform(project).validated()
            if (next == project) return
            undo.add(project); if (undo.size > 80) undo.removeAt(0); redo.clear()
            apply(next)
        } catch (failure: Exception) { error = failure.message ?: "Cannot apply this edit" }
    }
    private fun apply(next: SequenceProject) {
        project = next; saved = false; player.update(next); saves.trySend(next)
        if (selectedId != null && selected == null) selectedId = null
    }
    fun undo() { if (undo.isNotEmpty() && !ExportQueue.running) { redo.add(project); apply(undo.removeAt(undo.lastIndex)) } }
    fun redo() { if (redo.isNotEmpty() && !ExportQueue.running) { undo.add(project); apply(redo.removeAt(redo.lastIndex)) } }
    override fun close() { player.close(); saves.trySend(project); saves.close() }
}
