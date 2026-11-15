package ztw.carbiseo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 이 테스트가 지키는 동작: 내비 위 카드(DriveStrip)가 언제 보이는지(stripVisible)와 끌어 옮긴 위치의 화면 안 고정(clampCard).
// 운전 방에서 앱이 뒤에 있고 오버레이 권한이 있을 때만 보인다.
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

    @Test fun 끌어_옮긴_카드는_화면_안에_머문다() {
        assertEquals(100 to 200, clampCard(100, 200, 2560, 1440))
        assertEquals(0 to 0, clampCard(-50, -9, 2560, 1440))
        assertEquals((2560 - 595) to (1440 - 165), clampCard(9999, 9999, 2560, 1440))
    }
}
