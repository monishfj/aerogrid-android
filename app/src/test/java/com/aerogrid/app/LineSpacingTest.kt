package com.aerogrid.app

import com.aerogrid.app.engine.MissionEditor
import com.aerogrid.app.engine.Photogrammetry
import com.aerogrid.app.engine.Stats
import com.aerogrid.app.engine.Validator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

class LineSpacingTest {
    @Test fun fixedLineSpacingDrivesGridAndOverlap() {
        val base = Fixtures.project()
        val p = MissionEditor.regenerate(base.copy(settings = base.settings.copy(lineSpacingM = 30.0)), null).first
        val st = Stats.compute(p)
        assertEquals(ceil(300 / 30.0).toInt(), st.lineCount)
        assertEquals((1 - 30.0 / st.footprintW) * 100, st.sideOverlap, 1e-9)
        // spacing stays fixed when height changes; overlap follows
        val higher = MissionEditor.setGlobalHeight(p, 80.0)
        assertEquals(30.0, Photogrammetry.lineSpacing(higher.camera, higher.settings), 0.0)
        assertTrue(Stats.compute(higher).sideOverlap > st.sideOverlap)
    }

    @Test fun spacingWiderThanFootprintWarnsOfGaps() {
        val base = Fixtures.project()
        val p = MissionEditor.regenerate(base.copy(settings = base.settings.copy(lineSpacingM = 120.0)), null).first
        assertTrue(Validator.validate(p, null).any { it.message.contains("gaps between strips") })
    }

    @Test fun autoSpacingStillFollowsOverlap() {
        val s = Fixtures.project().settings
        val cam = Fixtures.project().camera
        assertEquals(Photogrammetry.footprint(cam, 50.0).widthM * 0.25, Photogrammetry.lineSpacing(cam, s), 1e-9)
    }

    @Test fun overlapAdviceTiersAndValidatorWarning() {
        assertEquals(0, com.aerogrid.app.engine.OverlapAdvice.side(75.0).level)
        assertEquals(1, com.aerogrid.app.engine.OverlapAdvice.side(65.0).level)
        assertEquals(2, com.aerogrid.app.engine.OverlapAdvice.side(40.0).level)
        assertEquals(3, com.aerogrid.app.engine.OverlapAdvice.side(-5.0).level)
        val base = Fixtures.project()
        val p = MissionEditor.regenerate(base.copy(settings = base.settings.copy(lineSpacingM = 45.0)), null).first
        assertTrue(Validator.validate(p, null).any { it.message.startsWith("Side overlap only") })
    }
}
