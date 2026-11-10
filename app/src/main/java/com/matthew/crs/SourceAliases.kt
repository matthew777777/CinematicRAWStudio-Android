// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs
import androidx.compose.runtime.*
import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Keep saved/in-memory project links working after explicit replacement of a source take. */
object SourceAliases {
    var revision by mutableIntStateOf(0); private set
    fun refreshLibrary() {revision++}
    private fun file(context: Context)=AtomicFile(File(context.filesDir,"source-replacements.json"))
    private fun read(context: Context): JSONObject {
        val f=file(context)
        return if(!f.baseFile.exists() && !File(f.baseFile.path+".bak").exists()) JSONObject()
            else JSONObject(f.readFully().toString(Charsets.UTF_8))
    }
    private fun identity(uri: Uri): String = if(uri.scheme=="file") "file:"+File(requireNotNull(uri.path)).canonicalPath
        else runCatching {"document:"+uri.authority+":"+android.provider.DocumentsContract.getDocumentId(uri)}.getOrDefault(uri.toString())
    @Synchronized fun resolve(context: Context,uri: Uri): Uri {
        val aliases=read(context)
        return Uri.parse(SourceAliasGraph.resolve(uri.toString()) {key ->
            if(aliases.has(key)) aliases.getString(key) else identity(Uri.parse(key)).let {if(aliases.has(it))aliases.getString(it) else null}
        })
    }
    @Synchronized fun replace(context: Context,source: Uri,replacement: Uri) {
        require(identity(source)!=identity(replacement) && identity(resolve(context,replacement))!=identity(source))
        val values=read(context).put(source.toString(),replacement.toString()).put(identity(source),replacement.toString())
        val f=file(context);val stream=f.startWrite()
        try {stream.write(values.toString().toByteArray(Charsets.UTF_8));f.finishWrite(stream)} catch(failure: Throwable) {f.failWrite(stream);throw failure}
    }
}
