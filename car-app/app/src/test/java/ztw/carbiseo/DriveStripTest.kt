package ztw.carbiseo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveStripTest {
    @Test fun 운전_방에서_앱이_뒤에_있고_권한이_있을때만() {
        for (r in listOf("DRIVE_WORK", "DRIVE_REST")) {
            assertTrue(r, stripVisible(r, appFront = false, canOverlay = true))
            assertFalse(r, stripVisible(r, appFront = true, canOverlay = true))
            assertFalse(r, stripVisible(r, appFront = false, canOverlay = false))
        }
    }

    @Test fun 정차와_판정_전은_숨긴다() {
        for (r in listOf("PARK", "(판정 전)", null)) assertFalse(stripVisible(r, appFront = false, canOverlay = true))
    }
}
