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
private fun diskKey(take: Take) = Integer.toHexString("${take.uri}|${take.bytes}|${take.modified}".hashCode())

private fun readDisk(context: Context, take: Take): TakeCard? = runCatching {
    val dir = java.io.File(context.cacheDir, "thumbs")
    val json = java.io.File(dir, diskKey(take) + ".json").takeIf { it.exists() }?.readText() ?: return null
    val o = org.json.JSONObject(json)
    val info = ClipInfo(o.getInt("width"), o.getInt("height"), o.getInt("frames"), o.getDouble("fps"), o.getBoolean("rgb"),
        o.getString("camera"), o.getInt("iso"), o.getDouble("exposure"), o.getInt("audioRate"), o.getInt("audioChannels"),
        o.getInt("audioBits"), o.getLong("wavSize"), o.getBoolean("recovered"))
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
fun GalleryScreen(onOpen: (Uri) -> Unit, onProjects: () -> Unit = {}) {
    val context = LocalContext.current
    val landscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val window = (context as? android.app.Activity)?.window
    DisposableEffect(landscape, window) {
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (landscape) controller?.hide(WindowInsetsCompat.Type.statusBars())
        else controller?.show(WindowInsetsCompat.Type.statusBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.statusBars()) }
    }
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var tree by remember { mutableStateOf(prefs.getString(KEY_TREE, null)?.let(Uri::parse)) }
    var takes by remember { mutableStateOf<List<Take>?>(tree?.let { Thumbs.listings[it.toString()] }) }
    var exportJobs by remember { mutableStateOf(false) }
    var libraryMenu by remember { mutableStateOf(false) }
    var dngPicker by remember { mutableStateOf(false) }
    var dngUris by remember { mutableStateOf<List<Uri>?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var newest by rememberSaveable { mutableStateOf(true) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
    val filtered = remember(takes, query, newest) {
        takes.orEmpty().filter { it.name.contains(query, ignoreCase = true) }
            .let { if (newest) it.sortedByDescending { t -> t.modified } else it.sortedBy { t -> t.name.lowercase() } }
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
        if (tree != null) TextButton(onClick = { newest = !newest }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
            Icon(Icons.AutoMirrored.Filled.Sort, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp)); Text(if (newest) "Newest" else "Name", fontSize = 12.sp)
        }
    }
    val subtitle = if (tree == null) "Your footage. Ready to develop." else
        "${takes?.size ?: 0} takes · ${tree?.lastPathSegment?.substringAfterLast(':') ?: "Selected folder"}"
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (landscape) 36.dp else 40.dp) {
    Scaffold(topBar = {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
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
                        DropdownMenuItem(text = { Text("Export jobs") }, leadingIcon = { Icon(Icons.Default.FileUpload, null) }, onClick = { libraryMenu = false; exportJobs = true })
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
                        items(filtered, key = { it.uri.toString() }) { take -> TakeTile(take) { onOpen(take.uri) } }
                    }
                }
            }
        }
    }
    }
    if (exportJobs) com.matthew.crs.project.ExportPanel(onDismiss = { exportJobs = false })
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

@Composable
private fun TakeTile(take: Take, onClick: () -> Unit) {
    val context = LocalContext.current
    val card by produceState<TakeCard?>(initialValue = Thumbs.cache.get(take.uri.toString()), take.uri) {
        if (value == null) value = withContext(Dispatchers.IO) { loadCard(context, take) }
    }
    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black), contentAlignment = Alignment.Center) {
            val thumb = card?.thumb
            if (thumb != null) Image(thumb.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Default.Movie, null, tint = Color.DarkGray)
            card?.info?.let { i ->
                Text(formatDuration(i.durationSeconds), fontSize = 11.sp, color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                        .background(Color(0xAA000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
            }
        }
        Column(Modifier.padding(8.dp)) {
            Text(take.name.removeSuffix(".craw"), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
            val i = card?.info
            Text(
                if (i == null) "%.1f GB".format(take.bytes / 1e9)
                else "${i.width}×${i.height}${if (i.rgb) " RGB" else ""} · %.0f fps · %.1f GB".format(i.fps, take.bytes / 1e9),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
    }
}
