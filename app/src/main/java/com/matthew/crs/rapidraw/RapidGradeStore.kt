// SPDX-License-Identifier: AGPL-3.0-or-later
package com.matthew.crs.rapidraw
import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
object RapidGradeStore {
    private fun file(context: Context,source: String): AtomicFile {
        val key=MessageDigest.getInstance("SHA-256").digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(context.filesDir,"rapidraw-grades/$key.json"))
    }
    @Synchronized fun load(context: Context,source: String,schema: RapidSchema): RapidRawGrade? {
        val f=file(context,source); if(!f.baseFile.exists() && !File(f.baseFile.path+".bak").exists()) return null
        return schema.decode(f.openRead().bufferedReader().use { it.readText() })
    }
    @Synchronized fun copy(context: Context,source: String,target: String) {
        val input=file(context,source)
        if(!input.baseFile.exists() && !File(input.baseFile.path+".bak").exists())return
        val bytes=input.readFully();val output=file(context,target);output.baseFile.parentFile!!.mkdirs();val stream=output.startWrite()
        try {stream.write(bytes);output.finishWrite(stream)} catch(failure: Throwable) {output.failWrite(stream);throw failure}
    }
    @Synchronized fun save(context: Context,source: String,grade: RapidRawGrade) {
        val f=file(context,source); f.baseFile.parentFile!!.mkdirs(); val stream=f.startWrite()
        try { stream.write(grade.encode().toByteArray(Charsets.UTF_8)); f.finishWrite(stream) }
        catch(failure: Throwable) { f.failWrite(stream); throw failure }
    }
}
