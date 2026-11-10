// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs.project
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Docs
import java.io.File

/** Only resolves a parent that actually contains the selected source document. */
internal class CrawSiblingFiles(private val context: Context,val parent: Uri) {
    private fun children(): List<Pair<Uri,String>> {
        if(parent.scheme=="file")return File(requireNotNull(parent.path)).listFiles().orEmpty().map {Uri.fromFile(it) to it.name}
        val uri=Docs.buildChildDocumentsUriUsingTree(parent,Docs.getDocumentId(parent))
        return context.contentResolver.query(uri,arrayOf(Docs.Document.COLUMN_DOCUMENT_ID,Docs.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use {c ->
            buildList {while(c.moveToNext())add(Docs.buildDocumentUriUsingTree(parent,c.getString(0)) to c.getString(1))}
        } ?: error("Cannot read the original take's directory")
    }
    fun contains(source: Uri): Boolean=children().any {(uri,_) -> sameDocument(uri,source)}
    fun readableUri(source: Uri): Uri=children().firstOrNull {sameDocument(it.first,source)}?.first ?: error("Original take is not in this directory")
    fun createPartial(source: Uri,jobId: String): Uri {
        require(contains(source)) {"Selected folder does not contain the original take"}
        require(jobId.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        val name=".compact-$jobId.partial"
        require(children().none {it.second==name}) {"A partial conversion already exists; remove or retry that job"}
        return if(parent.scheme=="file") {
            val f=File(requireNotNull(parent.path),name);check(f.createNewFile()) {"Cannot create Compact output"};Uri.fromFile(f)
        } else Docs.createDocument(context.contentResolver,parent,"application/octet-stream",name) ?: error("Cannot create a file next to the original take")
    }
    fun publish(partial: Uri,source: Uri,sourceName: String): Pair<Uri,String> {
        require(!sameDocument(partial,source))
        val base=sourceName.substringBeforeLast('.').ifBlank {"Take"}
        val existing=children().map {it.second.lowercase()}.toSet();var number=1
        var name="$base.compact.craw"
        while(name.lowercase() in existing) {number++;name="$base.compact-$number.craw"}
        val result=if(partial.scheme=="file") {
            val target=File(requireNotNull(parent.path),name);check(!target.exists() && File(requireNotNull(partial.path)).renameTo(target)) {"Cannot finish the Compact filename"};Uri.fromFile(target)
        } else Docs.renameDocument(context.contentResolver,partial,name) ?: error("This provider cannot finish the Compact file; original is unchanged")
        check(!sameDocument(result,source)) {"Compact output must be separate from the original"}
        val actual=ProjectMedia.name(context,result)
        check(actual.endsWith(".craw",true)) {"Provider returned an invalid Compact filename"}
        return result to actual
    }
    companion object {
        fun sameDocument(a: Uri,b: Uri): Boolean {
            if(a==b)return true
            if(a.scheme=="file" && b.scheme=="file")return File(requireNotNull(a.path)).canonicalPath==File(requireNotNull(b.path)).canonicalPath
            return a.authority==b.authority && runCatching {Docs.getDocumentId(a)==Docs.getDocumentId(b)}.getOrDefault(false)
        }
        fun find(context: Context,source: Uri,grant: Uri?=null): CrawSiblingFiles {
            if(source.scheme=="file")return CrawSiblingFiles(context,Uri.fromFile(File(requireNotNull(source.path)).parentFile ?: error("Source has no parent directory")))
            val trees=listOfNotNull(grant,source.takeIf {Docs.isTreeUri(it)},context.getSharedPreferences("gallery",0).getString("tree",null)?.let(Uri::parse)).distinct()
            for(tree in trees) {
                if(tree.authority!=source.authority)continue
                val parentId=runCatching {
                    val document=Docs.buildDocumentUriUsingTree(tree,Docs.getDocumentId(source))
                    Docs.findDocumentPath(context.contentResolver,document)?.path?.dropLast(1)?.lastOrNull()
                }.getOrNull()
                val candidates=listOfNotNull(parentId?.let {Docs.buildDocumentUriUsingTree(tree,it)},runCatching {Docs.buildDocumentUriUsingTree(tree,Docs.getTreeDocumentId(tree))}.getOrNull())
                for(uri in candidates) {
                    val directory=CrawSiblingFiles(context,uri)
                    if(runCatching {directory.contains(source)}.getOrDefault(false))return directory
                }
            }
            error("Grant access to the original take's containing folder, or open the take from that folder in Library")
        }
        fun delete(context: Context,uri: Uri): Boolean=if(uri.scheme=="file") File(requireNotNull(uri.path)).delete() else Docs.deleteDocument(context.contentResolver,uri)
        fun exists(context: Context,uri: Uri): Boolean=if(uri.scheme=="file")File(requireNotNull(uri.path)).exists() else runCatching {
            context.contentResolver.query(uri,arrayOf(Docs.Document.COLUMN_DOCUMENT_ID),null,null,null)?.use {it.moveToFirst()}==true
        }.getOrDefault(false)
    }
}
