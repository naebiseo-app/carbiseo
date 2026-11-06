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
        assertEquals("오늘 일정 알려줘", wakeSplit("내비서 오늘 일정 알려줘", "내비서"))
        assertEquals("오늘 일정", wakeSplit("내 비서 오늘 일정", "내비서"))
    }

    @Test fun 내와_네는_같은_것() {
        assertEquals("", wakeSplit("네비서야.", "내비서")) // 부름 조사만 남으면 이름만 부른 것
        assertEquals("", wakeSplit("내비서", "네비서"))
    }

    @Test fun 기본_이름은_잘못_적힌_꼴도_받는다() {
        assertEquals("", wakeSplit("내비사", "내비서"))
        assertEquals("오늘날씨어때?", wakeSplit("내디서오늘날씨어때?", "내비서"))
        assertEquals("졸려.", wakeSplit("내리서? 졸려.", "내비서")?.trimStart('?', ' '))
        assertEquals("졸려.", wakeSplit("앱이서졸려.", "내비서"))
        assertEquals("오늘일정알려줘.", wakeSplit("내미성오늘일정알려줘.", "내비서"))
        assertEquals("오늘일정알려줘.", wakeSplit("내비석. 오늘일정알려줘.", "내비서"))
        assertEquals("", wakeSplit("내미서.", "내비서"))
        assertEquals("오늘일정알려줘.", wakeSplit("내비소오늘일정알려줘.", "내비서"))
        assertEquals("", wakeSplit("예비소", "내비서"))
        assertNull(wakeSplit("내미서", "철수"))
        assertEquals("오늘일정알려줘", wakeSplit("예비소오늘일정알려줘", "내비서"))
        assertEquals("오늘일정알려줘갖고", wakeSplit("네미서오늘일정알려줘갖고", "내비서"))
        assertEquals("배고파", wakeSplit("내미서. 배고파", "내비서"))
        assertEquals("오늘 일정", wakeSplit("넷이서 오늘 일정", "내비서"))
        assertNull(wakeSplit("우리 넷이서 밥 먹자", "내비서")) // 넷이서는 맨 앞만
        assertNull(wakeSplit("넷이서 가자", "마루"))
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
