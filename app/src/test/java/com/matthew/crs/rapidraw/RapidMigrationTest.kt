package com.matthew.crs.rapidraw
import com.matthew.crs.project.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RapidMigrationTest {
    private fun source() = SourceAsset(uri="content://fixture/take",name="take.craw",kind=AssetKind.CRAW,
        durationUs=10_000_000,width=1920,height=1080,frames=300,fps=30.0,hasAudio=true)
    @Test fun everyOutputAndAllParametersSurviveProjectPersistence() {
        for(output in RapidOutput.entries) {
            val grade=RapidGradeSnapshot(RapidDefaults.schema.defaults(output).withValue("exposure",1.2)
                .withWheel(WheelRange.Shadows,RapidWheel(210.0,35.0,-10.0)).encode())
            val project=SequenceProject().append(source(),grade=grade)
            val reopened=ProjectStore.decode(ProjectStore.encode(project))
            assertEquals(project,reopened)
            assertEquals(grade,reopened.clips.first().grade)
        }
    }
    @Test fun timelineCutMoveAndTrimPreserveRapidGradeAndLinkedAudio() {
        val grade=RapidGradeSnapshot(RapidDefaults.schema.defaults().withValue("contrast",30).encode())
        val initial=SequenceProject().append(source(),grade=grade)
        val picture=initial.clips.first()
        val split=initial.split(picture.id,5_000_000)
        val right=split.clips.first { it.startUs==5_000_000L && split.track(it.trackId).kind==TrackKind.VIDEO }
        val moved=split.move(right.id,6_000_000)
        val trimmed=moved.trim(right.id,5_000_000,9_000_000)
        val restored=ProjectStore.decode(ProjectStore.encode(trimmed))
        assertEquals(grade,restored.clip(right.id).grade)
        assertEquals(2,restored.linked(right.id).size)
        val scheduled=SequenceSchedule(restored).videoAt(6_000_000)!!
        assertEquals(150,scheduled.sourceFrame)
        assertEquals(grade,scheduled.clip.grade)
    }
    @Test fun legacyGradesArePreservedAsDataUntilExplicitReset() {
        val old=JSONObject(ProjectStore.encode(SequenceProject().append(source()))).put("version",1)
        val clips=old.getJSONArray("clips")
        repeat(clips.length()) { i -> clips.getJSONObject(i).remove("grade"); clips.getJSONObject(i).put("receipt",JSONObject().put("exposure",70)) }
        val migrated=ProjectStore.decode(old.toString())
        assertEquals(2,migrated.version)
        assertEquals(70,JSONObject(migrated.clips.first().legacyGrade!!).getInt("exposure"))
        assertEquals(migrated,ProjectStore.decode(ProjectStore.encode(migrated)))
        val reset=migrated.copy(clips=migrated.clips.map { it.copy(grade=RapidGradeSnapshot(),legacyGrade=null) })
        assertTrue(ProjectStore.decode(ProjectStore.encode(reset)).clips.all { it.legacyGrade==null })
    }
    @Test fun parametricCurvesMatchExecutedUpstreamFloatBits() {
        val fixtures=JSONObject(javaClass.getResourceAsStream("/rapidraw-parametric-curves.json")!!.bufferedReader().readText()).getJSONArray("cases")
        repeat(fixtures.length()) { index ->
            val fixture=fixtures.getJSONObject(index)
            val actual=buildParametricPoints(fixture.getJSONObject("settings"))
            val expected=fixture.getJSONArray("float32_bits")
            actual.forEachIndexed { i,p ->
                assertEquals(expected.getJSONArray(i).getLong(0),p.x.toFloat().toRawBits().toLong() and 0xffffffffL)
                assertEquals(expected.getJSONArray(i).getLong(1),p.y.toFloat().toRawBits().toLong() and 0xffffffffL)
            }
        }
    }
}
