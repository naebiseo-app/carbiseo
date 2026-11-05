package ztw.carbiseo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeTest {
    @Test fun 이름만_부르면_빈_문장() {
        assertEquals("", wakeSplit("내비서", "내비서"))
        assertEquals("", wakeSplit("야 내비서", "내비서"))
    }

    @Test fun 이름_뒤_문장을_공백_없이_돌려준다() {
        assertEquals("오늘일정알려줘", wakeSplit("내비서 오늘 일정 알려줘", "내비서"))
    }

    @Test fun 내와_네는_같은_것() {
        assertEquals("야", wakeSplit("네비서야", "내비서"))
        assertEquals("", wakeSplit("내비서", "네비서"))
    }

    @Test fun 이름이_없으면_null() {
        assertNull(wakeSplit("내일 비가 온대", "내비서"))
        assertNull(wakeSplit("", "내비서"))
        assertNull(wakeSplit("내비서", ""))
    }

    @Test fun 다른_이름도_된다() {
        assertEquals("불러", wakeSplit("마루야 불러", "마루야"))
        assertNull(wakeSplit("내비서", "마루"))
    }
}
