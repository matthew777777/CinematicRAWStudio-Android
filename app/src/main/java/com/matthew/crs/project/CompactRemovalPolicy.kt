// SPDX-License-Identifier: GPL-3.0-or-later
package com.matthew.crs.project
internal object CompactRemovalPolicy {
    fun mayAsk(job: ExportJob): Boolean=job.settings.output==ExportOutput.CRAW_COMPACT && job.status=="Completed" &&
        job.sourceDecision=="Pending" && job.compact!=null && job.project.assets.size==1 &&
        job.project.assets.single().kind==AssetKind.CRAW && job.compact.uri!=job.project.assets.single().uri
}
