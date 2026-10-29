package ztw.carbiseo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BgTest {
    @Test fun 주차만_재생_옅은_스크림() = assertEquals(BgPolicy(true, 0.25f), bgPolicy("PARK"))

    @Test fun 운전중과_판정전은_정지_짙은_스크림() {
        for (r in listOf("DRIVE_WORK", "DRIVE_REST", "(판정 전)")) {
            val p = bgPolicy(r)
            assertFalse(r, p.animate)
            assertTrue(r, p.scrimAlpha >= 0.55f)
        }
    }

    @Test fun 이름_검사() {
        assertTrue(validName("원 대표 1"))
        assertTrue(validName("가".repeat(10)))
        assertFalse(validName(""))
        assertFalse(validName("   "))
        assertFalse(validName("가".repeat(11)))
        assertFalse(validName("하이!"))
    }
}
