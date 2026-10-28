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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
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
private fun listTakes(context: Context, tree: Uri): List<Take> {
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
fun GalleryScreen(onOpen: (Uri) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var tree by remember { mutableStateOf(prefs.getString(KEY_TREE, null)?.let(Uri::parse)) }
    var takes by remember { mutableStateOf<List<Take>?>(tree?.let { Thumbs.listings[it.toString()] }) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putString(KEY_TREE, uri.toString()).apply()
            tree = uri
        }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onOpen(uri)
    }
    LaunchedEffect(tree) {
        val t = tree ?: return@LaunchedEffect
        takes = withContext(Dispatchers.IO) { runCatching { listTakes(context, t) }.getOrDefault(emptyList()) }
            .also { Thumbs.listings[t.toString()] = it }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cinematic RAW Studio") },
                actions = {
                    TextButton(onClick = { pickFile.launch(arrayOf("*/*")) }) { Text("Open file") }
                    IconButton(onClick = { pickFolder.launch(null) }) { Icon(Icons.Default.FolderOpen, "Choose folder") }
                },
            )
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val list = takes
            when {
                tree == null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Choose the folder with your .craw takes", style = MaterialTheme.typography.titleMedium)
                    Text("RawLens saves them in DCIM/RawLens", color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
                    Button(onClick = { pickFolder.launch(null) }, modifier = Modifier.padding(top = 16.dp)) { Text("Choose folder") }
                }
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() -> Text("No .craw takes in this folder", Modifier.align(Alignment.Center), color = Color.Gray)
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(170.dp),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(list, key = { it.uri.toString() }) { take -> TakeTile(take) { onOpen(take.uri) } }
                }
            }
        }
    }
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
                fontSize = 11.sp, color = Color.Gray, maxLines = 1,
            )
        }
    }
}
