package com.matthew.crs.project
import com.matthew.crs.SourceAliasGraph
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class CompactPolicyTest {
    private val source=SourceAsset(uri="content://fixture/original",name="take.craw",kind=AssetKind.CRAW,durationUs=1_000_000,width=64,height=48,frames=30,fps=30.0)
    private val result=CompactResult("content://fixture/compact","take.compact.craw",30,28,2,34,8_000_000_000,6_000_000_000,3)
    private fun job()=ExportJob(project=SequenceProject().append(source),settings=ExportSettings(output=ExportOutput.CRAW_COMPACT),destination="content://fixture/parent",status="Completed",compact=result)
    @Test fun removalIsOnlyOfferedForCompletedVerifiedDistinctCompactFiles() {
        val good=job();assertTrue(CompactRemovalPolicy.mayAsk(good))
        for(status in listOf("Queued","Exporting","Failed","Cancelled","Interrupted"))assertFalse(CompactRemovalPolicy.mayAsk(good.copy(status=status)))
        assertFalse(CompactRemovalPolicy.mayAsk(good.copy(compact=null)))
        assertFalse(CompactRemovalPolicy.mayAsk(good.copy(settings=ExportSettings())))
        assertFalse(CompactRemovalPolicy.mayAsk(good.copy(compact=result.copy(uri=source.uri))))
        for(decision in listOf("Kept","Removed","Original already absent"))assertFalse(CompactRemovalPolicy.mayAsk(good.copy(sourceDecision=decision)))
    }
    @Test fun verificationRecordRoundTripsLargeFileSizesExactly() {assertEquals(result,CompactResult.decode(result.encode()))}
    @Test fun incompleteNativeReportCannotBecomeVerificationRecord() {
        val report=result.encode().put("done",false).put("verifying",true)
        try {CompactResult.verified(report,result.uri,result.name);fail("Incomplete report accepted")}catch(expected:IllegalArgumentException){}
    }
    @Test fun projectSourceReplacementChainsResolveAndCyclesAreRejected() {
        val aliases=mapOf("Live" to "Compact","Compact" to "New Compact")
        assertEquals("New Compact",SourceAliasGraph.resolve("Live",aliases::get))
        assertEquals("Unchanged",SourceAliasGraph.resolve("Unchanged",aliases::get))
        try {SourceAliasGraph.resolve("A",mapOf("A" to "B","B" to "A")::get);fail("Cycle accepted")}catch(expected:IllegalStateException){}
    }
}
