package ztw.carbiseo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 이 테스트가 지키는 동작: 방별 배경 정책(bgPolicy)과 이름 입력 검사(validName).
// 배경 움직임은 정차(PARK)에서만 켜고, 운전 중·판정 전에는 멈춘 채 짙은 스크림을 깐다.
class BgTest {
    @Test fun 주차만_재생_옅은_스크림() = assertEquals(BgPolicy(true, 0.25f), bgPolicy("PARK"))

    @Test fun 운전중과_판정전은_정지_짙은_스크림() {
        for (r in listOf("DRIVE_WORK", "DRIVE_REST", "(판정 전)")) {
            val p = bgPolicy(r)
            assertFalse(r, p.animate)
            assertTrue(r, p.scrimAlpha >= 0.55f)
        }
    }

    // validName: 글자·숫자·공백만, 공백 아닌 글자가 있어야 하고 10자 이하.
    @Test fun 이름_검사() {
        assertTrue(validName("원 대표 1"))
        assertTrue(validName("가".repeat(10)))
        assertFalse(validName(""))
        assertFalse(validName("   "))
        assertFalse(validName("가".repeat(11)))
        assertFalse(validName("하이!"))
    }
}
