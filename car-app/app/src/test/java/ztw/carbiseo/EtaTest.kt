package ztw.carbiseo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// 이 테스트가 지키는 동작: 남은 초를 분으로 바꾸는 etaMinutes. 반올림하고, 0 이하는 모름(null)으로 본다.
class EtaTest {
    @Test fun 초를_분으로_반올림() {
        assertEquals(27, etaMinutes(1620))
        assertEquals(1, etaMinutes(89))
        assertEquals(2, etaMinutes(90))
        assertEquals(1, etaMinutes(29))
    }

    @Test fun 영_이하는_모름() {
        assertNull(etaMinutes(0))
        assertNull(etaMinutes(-5))
    }
}
