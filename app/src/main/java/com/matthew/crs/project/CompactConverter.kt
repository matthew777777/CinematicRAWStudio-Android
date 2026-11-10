// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs.project
import android.content.Context
import android.net.Uri
import com.matthew.crs.CrawClip
import com.matthew.crs.SourceAliases
import com.matthew.crs.rapidraw.*
import kotlinx.coroutines.*

object CompactConverter {
    fun prepare(context: Context,uri: Uri,grant: Uri?=null): Pair<SequenceProject,Uri> {
        check(SourceAliases.resolve(context,uri)==uri) {"This original has already been replaced; select the Compact take in Library"}
        val parent=CrawSiblingFiles.find(context,uri,grant)
        val name=ProjectMedia.name(context,uri)
        val source=CrawClip.open(context,uri)?.use {c ->val i=c.info
            require(!i.recovered && i.frames>0 && i.fps>0) {"Repair incomplete/recovered takes before compacting"}
            SourceAsset(uri=uri.toString(),name=name,kind=AssetKind.CRAW,durationUs=(i.frames*1_000_000.0/i.fps).toLong(),
                width=i.width,height=i.height,frames=i.frames,fps=i.fps,hasAudio=i.hasAudio,rgb=i.rgb)
        } ?: error("Cannot open the original take")
        // This snapshot identifies the whole take; compaction never bakes timeline trims/grades.
        return SequenceProject(name=name.substringBeforeLast('.'),rate=FrameRate.fromFps(source.fps)).append(source) to parent.parent
    }
    suspend fun export(context: Context,job: ExportJob,progress: (Float,String)->Unit): CompactResult {
        val source=job.project.assets.single();val original=Uri.parse(source.uri)
        val directory=CrawSiblingFiles.find(context,original,Uri.parse(job.destination))
        var owned: Uri?=null
        try {
            currentCoroutineContext().ensureActive()
            val partial=directory.createPartial(original,job.id);owned=partial
            check(!CrawSiblingFiles.sameDocument(original,partial))
            withContext(Dispatchers.Main) {ExportQueue.update(context,job.id) {it.copy(partialUri=partial.toString())}}
            context.contentResolver.openFileDescriptor(directory.readableUri(original),"r")?.use {input ->
                context.contentResolver.openFileDescriptor(partial,"rw")?.use {output ->
                    CompactNative.run(input.fd,output.fd,false) {f,p ->progress(f*.85f,p)}
                } ?: error("Cannot write Compact output")
            } ?: error("Cannot read original take")
            currentCoroutineContext().ensureActive()
            val (published,name)=directory.publish(partial,original,source.name);owned=published
            withContext(Dispatchers.Main) {ExportQueue.update(context,job.id) {it.copy(partialUri=published.toString())}}
            val result=verify(context,original,published,name,directory.parent) {f,p ->progress(.85f+f*.15f,p)}
            RapidGradeStore.copy(context,source.uri,published.toString())
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.Main) {SourceAliases.refreshLibrary()}
            progress(1f,"Compact file verified; original kept")
            return result
        } catch(failure: Throwable) {
            owned?.let {runCatching {CrawSiblingFiles.delete(context,it)}}
            throw failure
        }
    }
    suspend fun verify(context: Context,original: Uri,output: Uri,name: String,grant: Uri?=null,progress: (Float,String)->Unit): CompactResult {
        require(!CrawSiblingFiles.sameDocument(original,output))
        val readable=CrawSiblingFiles.find(context,original,grant).readableUri(original)
        val report=context.contentResolver.openFileDescriptor(readable,"r")?.use {source ->
            context.contentResolver.openFileDescriptor(output,"r")?.use {target ->CompactNative.run(source.fd,target.fd,true,progress)}
                ?: error("Verified Compact file is no longer readable")
        } ?: error("Original take is no longer readable")
        return CompactResult.verified(report,output.toString(),name)
    }
    suspend fun removeOriginal(context: Context,job: ExportJob,committing: suspend ()->Unit) {
        require(CompactRemovalPolicy.mayAsk(job))
        val original=Uri.parse(job.project.assets.single().uri);val result=requireNotNull(job.compact)
        val target=Uri.parse(result.uri)
        // Re-read actual files, not aliases, immediately before the explicit deletion.
        val checked=verify(context,original,target,result.name,Uri.parse(job.destination)) {_,_ ->}
        require(checked.frames==result.frames && checked.chunks==result.chunks && checked.sourceBytes==result.sourceBytes && checked.outputBytes==result.outputBytes) {"A take changed since conversion; original was kept"}
        currentCoroutineContext().ensureActive();committing();currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            RapidGradeStore.copy(context,original.toString(),result.uri)
            SourceAliases.replace(context,original,target)
            check(CrawSiblingFiles.delete(context,CrawSiblingFiles.find(context,original,Uri.parse(job.destination)).readableUri(original))) {"Could not remove original; both takes are kept"}
            withContext(Dispatchers.Main) {SourceAliases.refreshLibrary()}
        }
    }
}
