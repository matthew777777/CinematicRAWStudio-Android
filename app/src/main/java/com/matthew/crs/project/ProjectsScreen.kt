// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var projects by remember { mutableStateOf<List<SequenceProject>?>(null) }
    var create by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var fps by remember { mutableIntStateOf(30) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { projects = withContext(Dispatchers.IO) { ProjectStore.list(context) } }
    Scaffold(topBar = { TopAppBar(title = { Text("Projects") }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to library") }
    }, actions = { FilledTonalButton(onClick = { create = true }, modifier = Modifier.padding(end = 16.dp)) {
        Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Text("New project")
    } }) }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            val list = projects
            if (list == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
            else if (list.isEmpty()) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.VideoLibrary, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Build a sequence", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
                Text("Combine RAW takes, trim your cuts, and mix audio tracks.", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                Button(onClick = { create = true }, modifier = Modifier.padding(top = 20.dp)) { Text("New project") }
            } else LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(list, key = { it.id }) { project ->
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.fillMaxWidth().clickable { onOpen(project.id) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Movie, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(project.name, style = MaterialTheme.typography.titleMedium)
                                Text("${project.clips.count { project.track(it.trackId).kind == TrackKind.VIDEO }} clips · ${com.matthew.crs.formatDuration(project.durationUs / 1e6)} · %.3f fps".format(project.rate.fps),
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.ChevronRight, "Open project")
                        }
                    }
                }
            }
            error?.let { Text(it, Modifier.align(Alignment.BottomCenter).padding(16.dp), color = MaterialTheme.colorScheme.error) }
        }
    }
    if (create) AlertDialog(onDismissRequest = { create = false }, title = { Text("New project") }, text = {
        Column {
            OutlinedTextField(name, { name = it }, label = { Text("Project name") }, singleLine = true)
            Text("1280 × 720 · Rec.709", Modifier.padding(top = 12.dp), fontSize = 12.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(24, 25, 30, 60).forEach { value -> FilterChip(selected = fps == value, onClick = { fps = value }, label = { Text("$value fps") }) }
            }
        }
    }, confirmButton = { TextButton(onClick = {
        scope.launch {
            try {
                val project = SequenceProject(name = name.trim().ifBlank { "Untitled sequence" }, rate = FrameRate(fps))
                withContext(Dispatchers.IO) { ProjectStore.save(context, project) }
                create = false; onOpen(project.id)
            } catch (failure: Exception) { error = failure.message; create = false }
        }
    }) { Text("Create") } }, dismissButton = { TextButton(onClick = { create = false }) { Text("Cancel") } })
}
