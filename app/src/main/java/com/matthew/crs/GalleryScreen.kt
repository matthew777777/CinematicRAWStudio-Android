// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Take(val uri: Uri, val name: String, val bytes: Long, val modified: Long)
data class TakeCard(val thumb: Bitmap?, val info: ClipInfo?)

private const val PREFS = "gallery"
private const val KEY_TREE = "tree"

private object Thumbs {
    val cache = LruCache<String, TakeCard>(96)
    /** Last listing per folder: returning to the gallery shows it at once, then refreshes. */
    val listings = HashMap<String, List<Take>>()
}

/** One DocumentsContract query for name, size and date of every child (no per-file IPC). */
internal fun listTakes(context: Context, tree: Uri): List<Take> {
    val parent = android.provider.DocumentsContract.getTreeDocumentId(tree)
    val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
    val cols = arrayOf(
        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        android.provider.DocumentsContract.Document.COLUMN_SIZE,
        android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )
    val out = ArrayList<Take>()
    context.contentResolver.query(children, cols, null, null, null)?.use { c ->
        while (c.moveToNext()) {
            val name = c.getString(1) ?: continue
            if (!name.endsWith(".craw", ignoreCase = true)) continue
            val uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
            out += Take(uri, name, c.getLong(2), c.getLong(3))
        }
    }
    return out.sortedByDescending { it.modified }
}

/** Disk cache of 480 px thumbnails + metadata, keyed by Uri, size and mtime. */
// v2: codec, bit depth, make/model and AsShotNeutral in the metadata.
private fun diskKey(take: Take) = Integer.toHexString("v2|${take.uri}|${take.bytes}|${take.modified}".hashCode())

private fun readDisk(context: Context, take: Take): TakeCard? = runCatching {
    val dir = java.io.File(context.cacheDir, "thumbs")
    val json = java.io.File(dir, diskKey(take) + ".json").takeIf { it.exists() }?.readText() ?: return null
    val o = org.json.JSONObject(json)
    val info = ClipInfo(o.getInt("width"), o.getInt("height"), o.getInt("frames"), o.getDouble("fps"), o.getBoolean("rgb"),
        o.getString("camera"), o.getInt("iso"), o.getDouble("exposure"), o.getInt("audioRate"), o.getInt("audioChannels"),
        o.getInt("audioBits"), o.getLong("wavSize"), o.getBoolean("recovered"), o.optInt("codec"), o.optInt("bitDepth"),
        o.optString("make"), o.optString("model"), o.optJSONArray("neutral")?.let { a -> List(a.length()) { a.getDouble(it) } } ?: emptyList())
    val jpg = java.io.File(dir, diskKey(take) + ".jpg")
    TakeCard(if (jpg.exists()) android.graphics.BitmapFactory.decodeFile(jpg.path) else null, info)
}.getOrNull()

private fun writeDisk(context: Context, take: Take, card: TakeCard) {
    runCatching {
        val i = card.info ?: return
        val dir = java.io.File(context.cacheDir, "thumbs").apply { mkdirs() }
        card.thumb?.let { b -> java.io.File(dir, diskKey(take) + ".jpg").outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
        java.io.File(dir, diskKey(take) + ".json").writeText(org.json.JSONObject().apply {
            put("width", i.width); put("height", i.height); put("frames", i.frames); put("fps", i.fps); put("rgb", i.rgb)
            put("camera", i.camera); put("iso", i.iso); put("exposure", i.exposureSeconds); put("audioRate", i.audioRate)
            put("audioChannels", i.audioChannels); put("audioBits", i.audioBits); put("wavSize", i.wavSize); put("recovered", i.recovered)
            put("codec", i.codec); put("bitDepth", i.bitDepth); put("make", i.make); put("model", i.model)
            put("neutral", org.json.JSONArray(i.neutral))
        }.toString())
    }
}

/** First frame, developed and scaled to a card thumbnail, plus metadata. */
private fun loadCard(context: Context, take: Take): TakeCard {
    val uri = take.uri
    Thumbs.cache.get(uri.toString())?.let { return it }
    readDisk(context, take)?.let { Thumbs.cache.put(uri.toString(), it); return it }
    val card = CrawClip.open(context, uri)?.use { clip ->
        val full = clip.newPreviewBitmap()
        val ok = clip.info.frames > 0 && clip.render(0, full)
        val thumb = if (ok) {
            val w = 480
            Bitmap.createScaledBitmap(full, w, (w.toLong() * full.height / full.width).toInt().coerceAtLeast(1), true)
        } else null
        full.recycle()
        TakeCard(thumb, clip.info)
    } ?: TakeCard(null, null)
    Thumbs.cache.put(uri.toString(), card)
    writeDisk(context, take, card)
    return card
}

fun formatDuration(seconds: Double): String {
    val s = seconds.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(onOpen: (Uri) -> Unit, onOpenProject: (String) -> Unit = {}) {
    val context = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    // System bars are hidden app-wide (MainActivity): full-screen look.
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    LaunchedEffect(Unit) { SettingsClipboard.load(context) }
    var tree by remember { mutableStateOf(prefs.getString(KEY_TREE, null)?.let(Uri::parse)) }
    var takes by remember { mutableStateOf<List<Take>?>(tree?.let { Thumbs.listings[it.toString()] }) }
    var exportJobs by remember { mutableStateOf(false) }
    var libraryMenu by remember { mutableStateOf(false) }
    var dngPicker by remember { mutableStateOf(false) }
    var dngUris by remember { mutableStateOf<List<Uri>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(TakeSort.Newest) }
    var filter by rememberSaveable { mutableStateOf(TakeFilter.All) }
    var sortSheet by remember { mutableStateOf(false) }
    // Long-press menu and what it opens.
    var menuFor by remember { mutableStateOf<Take?>(null) }
    var infoFor by remember { mutableStateOf<Take?>(null) }
    var exportFor by remember { mutableStateOf<Take?>(null) }
    var projectFor by remember { mutableStateOf<Take?>(null) }
    var deleteFor by remember { mutableStateOf<Take?>(null) }
    var added by remember { mutableStateOf<String?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                prefs.edit().putString(KEY_TREE, uri.toString()).apply()
                takes = Thumbs.listings[uri.toString()]
                tree = uri
                refresh++
            }.onFailure { error = "Cannot access this folder. Please choose it again." }
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            onOpen(uri)
        }
    }
    LaunchedEffect(tree, refresh) {
        val t = tree ?: return@LaunchedEffect
        error = null
        val result = withContext(Dispatchers.IO) { runCatching { listTakes(context, t) } }
        result.onSuccess { takes = it; Thumbs.listings[t.toString()] = it }
            .onFailure { error = "This folder is unavailable. Choose the folder again to restore access." }
    }
    // Duration sort and the RAW/RGB/audio filters need each take's metadata:
    // loaded in the background (disk-cached after the first time) only then.
    val needInfo = sort == TakeSort.Longest || filter != TakeFilter.All
    val infos by produceState(emptyMap<String, ClipInfo?>(), takes, needInfo) {
        if (!needInfo) return@produceState
        val list = takes.orEmpty()
        val out = HashMap<String, ClipInfo?>()
        list.forEach { t -> out[t.uri.toString()] = Thumbs.cache.get(t.uri.toString())?.info }
        value = out.toMap()
        withContext(Dispatchers.IO) {
            list.filter { out[it.uri.toString()] == null }.forEach { t ->
                out[t.uri.toString()] = loadCard(context, t).info
                value = out.toMap()
            }
        }
    }
    val filtered = remember(takes, query, sort, filter, infos) {
        takes.orEmpty().filter { it.name.contains(query, ignoreCase = true) }
            .filter { t ->
                val i = infos[t.uri.toString()]
                when (filter) {
                    TakeFilter.All -> true
                    TakeFilter.Bayer -> i == null || !i.rgb
                    TakeFilter.Rgb -> i == null || i.rgb
                    TakeFilter.Audio -> i == null || i.hasAudio
                }
            }
            .let { list -> when (sort) {
                TakeSort.Newest -> list.sortedByDescending { it.modified }
                TakeSort.Oldest -> list.sortedBy { it.modified }
                TakeSort.Name -> list.sortedBy { it.name.lowercase() }
                TakeSort.Largest -> list.sortedByDescending { it.bytes }
                TakeSort.Longest -> list.sortedByDescending { infos[it.uri.toString()]?.durationSeconds ?: -1.0 }
            } }
    }
    @Composable fun Search(modifier: Modifier) {
        Row(modifier.height(if (landscape) 34.dp else 40.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            BasicTextField(value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f), decorationBox = { field ->
                    Box {
                        if (query.isEmpty()) Text("Search takes", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        field()
                    }
                })
            if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, "Clear search", Modifier.size(14.dp))
            }
        }
    }
    @Composable fun Sort() {
        if (tree != null) TextButton(onClick = { sortSheet = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
            Icon(if (filter == TakeFilter.All) Icons.AutoMirrored.Filled.Sort else Icons.Default.FilterList, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp)); Text(if (filter == TakeFilter.All) sort.title.substringBefore(' ') else filter.title, fontSize = 12.sp)
        }
    }
    val subtitle = if (tree == null) "Your footage. Ready to develop." else
        "${takes?.size ?: 0} takes · ${tree?.lastPathSegment?.substringAfterLast(':') ?: "Selected folder"}"
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (landscape) 36.dp else 40.dp) {
    Scaffold(topBar = {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top))
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .height(if (landscape) 40.dp else 52.dp).padding(start = 16.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("Cinematic RAW Studio", fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                // Projects and Exports are bottom-navigation tabs (MainActivity).
                FilledTonalButton(onClick = { pickFile.launch(arrayOf("*/*")) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp), modifier = Modifier.height(32.dp)) {
                    Icon(Icons.Default.Add, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Open", fontSize = 12.sp)
                }
                Box {
                    IconButton(onClick = { libraryMenu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.MoreVert, "Library options", Modifier.size(20.dp)) }
                    DropdownMenu(libraryMenu, { libraryMenu = false }) {
                        DropdownMenuItem(text = { Text("Choose takes folder") }, leadingIcon = { Icon(Icons.Default.FolderOpen, null) },
                            onClick = { libraryMenu = false; pickFolder.launch(null) })
                        DropdownMenuItem(text = { Text("Refresh library") }, enabled = tree != null,
                            leadingIcon = { Icon(Icons.Default.Refresh, null) }, onClick = { libraryMenu = false; refresh++ })
                    }
                }
            }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            Row(Modifier.fillMaxWidth().height(if (landscape) 48.dp else 62.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Library", fontSize = if (landscape) 14.sp else 20.sp, fontWeight = FontWeight.Medium)
                    Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (landscape && tree != null && !takes.isNullOrEmpty()) {
                    Search(Modifier.width(200.dp))
                    Spacer(Modifier.width(8.dp))
                }
                TextButton(onClick = { dngPicker = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text("CinemaDNG", fontSize = 11.sp) }
                Sort()
            }
            if (!landscape && tree != null && !takes.isNullOrEmpty()) {
                Search(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                Spacer(Modifier.height(4.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    error != null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FolderOff, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                        Text(error!!, Modifier.padding(vertical = 16.dp))
                        Button(onClick = { pickFolder.launch(null) }) { Text("Choose folder") }
                    }
                    tree == null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                            Icon(Icons.Default.Movie, null, Modifier.padding(24.dp).size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                        Text("Start with a take", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 24.dp))
                        Text("Open a .craw file or connect your RawLens folder.", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                        Button(onClick = { pickFolder.launch(null) }, modifier = Modifier.padding(top = 20.dp)) {
                            Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Choose takes folder")
                        }
                        Text("RawLens saves takes in DCIM/RawLens", fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                    }
                    takes == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    filtered.isEmpty() -> Text(if (query.isBlank()) "No .craw takes in this folder" else "No takes match “$query”",
                        Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> LazyVerticalGrid(columns = GridCells.Adaptive(if (landscape) 240.dp else 170.dp),
                        contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(filtered, key = { it.uri.toString() }) { take ->
                            TakeTile(take, onLongClick = { menuFor = take }) { onOpen(take.uri) }
                        }
                    }
                }
            }
        }
    }
    }
    if (exportJobs) com.matthew.crs.project.ExportPanel(onDismiss = { exportJobs = false })
    if (sortSheet) SortFilterSheet(sort, filter, onSort = { sort = it }, onFilter = { filter = it }, onDismiss = { sortSheet = false })
    menuFor?.let { take ->
        TakeActionsSheet(take, onDismiss = { menuFor = null },
            onEdit = { menuFor = null; onOpen(take.uri) },
            onInfo = { menuFor = null; infoFor = take },
            onExport = { menuFor = null; exportFor = take },
            onExportDng = { menuFor = null; dngUris = listOf(take.uri) },
            onAddToProject = { menuFor = null; projectFor = take },
            onDelete = { menuFor = null; deleteFor = take },
            // Copied settings onto this take (keeps its RAW corrections); RGB takes are not graded.
            onPaste = SettingsClipboard.copied?.takeIf { Thumbs.cache.get(take.uri.toString())?.info?.rgb != true }?.let { copied -> {
                menuFor = null
                val key = take.uri.toString()
                ReceiptStore.save(context, key, copied.onto(ReceiptStore.load(context, key) ?: Receipt()))
            } })
    }
    infoFor?.let { take -> TakeInfoSheet(take, Thumbs.cache.get(take.uri.toString())?.info, onDismiss = { infoFor = null }) }
    exportFor?.let { take ->
        com.matthew.crs.project.ExportPanel(prepare = {
            singleTakeProject(context, take.uri, ReceiptStore.load(context, take.uri.toString()) ?: Receipt())
        }, onDismiss = { exportFor = null })
    }
    projectFor?.let { take ->
        AddToProjectDialog(take, onDismiss = { projectFor = null }, onAdded = { id -> projectFor = null; added = id })
    }
    added?.let { id ->
        AlertDialog(onDismissRequest = { added = null }, title = { Text("Added to project") },
            confirmButton = { TextButton(onClick = { added = null; onOpenProject(id) }) { Text("Open project") } },
            dismissButton = { TextButton(onClick = { added = null }) { Text("Stay here") } })
    }
    deleteFor?.let { take ->
        AlertDialog(onDismissRequest = { deleteFor = null }, title = { Text("Delete take?") },
            text = { Text("“${take.name}” (%.1f GB) will be deleted from the folder. This cannot be undone.".format(take.bytes / 1e9)) },
            confirmButton = { TextButton(onClick = {
                deleteFor = null
                runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, take.uri) }
                    .onSuccess { Thumbs.cache.remove(take.uri.toString()); takes = takes?.filterNot { it.uri == take.uri }; refresh++ }
                    .onFailure { deleteError = "Cannot delete this take. If the folder was chosen in an older version, choose it again (Library options) to allow changes." }
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteFor = null }) { Text("Cancel") } })
    }
    deleteError?.let { msg -> AlertDialog(onDismissRequest = { deleteError = null }, text = { Text(msg) },
        confirmButton = { TextButton(onClick = { deleteError = null }) { Text("OK") } }) }
    val browseDng = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri -> runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            dngUris = uris
        }
    }
    if (dngPicker) com.matthew.crs.project.LibraryTakesDialog(onDismiss = { dngPicker = false },
        onPick = { dngPicker = false; dngUris = it }, onBrowse = { dngPicker = false; browseDng.launch(arrayOf("*/*")) }, title = "Select takes for CinemaDNG", action = "Select")
    dngUris?.let { com.matthew.crs.project.CinemaDngPanel(it, onDismiss = { dngUris = null }) }

}

/** Thumbnail with codec/RGB/audio and duration badges; tap opens, long press shows the take's actions. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TakeTile(take: Take, onLongClick: () -> Unit, onClick: () -> Unit) {
    val context = LocalContext.current
    val card by produceState<TakeCard?>(initialValue = Thumbs.cache.get(take.uri.toString()), take.uri) {
        if (value == null) value = withContext(Dispatchers.IO) { loadCard(context, take) }
    }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(Modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
        .combinedClickable(onClick = onClick, onLongClickLabel = "Take actions", onLongClick = {
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); onLongClick()
        })) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
            val thumb = card?.thumb
            if (thumb != null) Image(thumb.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.Movie, null, tint = Color.DarkGray)
            card?.info?.let { i ->
                Row(Modifier.align(Alignment.TopStart).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Badge(if (i.rgb) "RGB" else "RAW", accent = !i.rgb)
                    if (i.codecName.isNotEmpty()) Badge(i.codecName.uppercase())
                    if (i.hasAudio) Badge("♪")
                }
                Badge(formatDuration(i.durationSeconds), Modifier.align(Alignment.BottomEnd).padding(6.dp))
                if (i.recovered) Badge("RECOVERED", Modifier.align(Alignment.BottomStart).padding(6.dp))
            }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(take.name.removeSuffix(".craw"), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
            val i = card?.info
            Text(
                if (i == null) "%.1f GB".format(take.bytes / 1e9)
                else "${i.width}×${i.height} · %s fps · %.1f GB".format("%.3f".format(i.fps).trimEnd('0').trimEnd('.'), take.bytes / 1e9),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    Text(text, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp,
        color = if (accent) MaterialTheme.colorScheme.onPrimary else Color.White,
        modifier = modifier.background(if (accent) MaterialTheme.colorScheme.primary else Color(0xB3000000), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp))
}
