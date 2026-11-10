// SPDX-License-Identifier: AGPL-3.0-or-later
package com.matthew.crs.rapidraw
import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

/** Imported resources are owned by the app so queued exports keep access after restart. */
object RapidResourceStore {
    fun importLut(context: Context,uri: Uri): String {
        val name=com.matthew.crs.project.ProjectMedia.name(context,uri)
        val ext=name.substringAfterLast('.',"cube").lowercase()
        require(ext in listOf("cube","3dl","png","jpg","jpeg","tiff","tif")) { "Choose a .cube, .3dl or Hald image LUT" }
        val bytes=read(context,uri)
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val file=File(context.filesDir,"rapidraw-resources/$hash.$ext");file.parentFile!!.mkdirs()
        val atomic=AtomicFile(file);val stream=atomic.startWrite()
        try { stream.write(bytes);atomic.finishWrite(stream) } catch(failure: Throwable) { atomic.failWrite(stream);throw failure }
        return file.absolutePath
    }
    private fun read(context: Context,uri: Uri): ByteArray = context.contentResolver.openInputStream(uri)?.use { input ->
        val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(65536)
        while(true) { val n=input.read(buffer);if(n<0)break;require(output.size()+n<=32*1024*1024) { "Resource exceeds 32 MiB" };output.write(buffer,0,n) }
        output.toByteArray()
    } ?: error("Cannot read selected resource")
}
