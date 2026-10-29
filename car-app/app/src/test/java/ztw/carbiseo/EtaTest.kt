package ztw.carbiseo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
