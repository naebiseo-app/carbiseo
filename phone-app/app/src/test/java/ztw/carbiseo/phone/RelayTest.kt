package ztw.carbiseo.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayTest {
    private fun m(pkg: String, cat: String? = null, style: Boolean = false, summary: Boolean = false, title: String? = "김부장", text: String? = "회의 자료 보냈어요", reply: Boolean = true) =
        toMsg(pkg, "k", cat, style, summary, title, text, reply)

    @Test fun 메시지앱은_이름을_붙여_넘긴다() {
        assertEquals(Msg("카카오톡", "김부장", "회의 자료 보냈어요", true, "k"), m("com.kakao.talk"))
        assertEquals("문자", m("com.google.android.apps.messaging")!!.app)
    }

    @Test fun 그밖의_앱은_메시지분류나_메시지스타일만() {
        assertNull(m("com.example.shop")) // 쇼핑·뉴스 등 일반 알림은 안 넘김
        assertEquals("메시지", m("com.example.chat", cat = "msg")!!.app)
        assertEquals("메시지", m("com.android.shell", style = true)!!.app)
    }

    @Test fun 묶음요약_빈값은_버리고_길이는_자른다() {
        assertNull(m("com.kakao.talk", summary = true))
        assertNull(m("com.kakao.talk", title = " "))
        assertNull(m("com.kakao.talk", text = null))
        assertEquals(500, m("com.kakao.talk", text = "가".repeat(900))!!.text.length)
        assertEquals(false, m("com.kakao.talk", reply = false)!!.replyable)
    }

    @Test fun 연락처_정리() {
        val r = normalizeContacts(listOf(
            " 이지은 " to "010-0000-0001", "김도윤" to "+82 10 0000 0002", "김민수" to "82-10-0000-0003",
            "김도윤" to "01000000009", // 같은 이름은 먼저 온 것만
            "박도윤" to "02-000-0000", "" to "01000000004", null to "01000000005", "박도윤" to "0100000", "박도윤" to null,
            "가".repeat(41) to "01000000006", "가".repeat(40) to "0100000007", // 41자 버림, 40자·10자리는 통과
        ))
        assertEquals(listOf("이지은" to "01000000001", "김도윤" to "01000000002", "김민수" to "01000000003", "가".repeat(40) to "0100000007"), r)
    }

    @Test fun 연락처는_3000건까지() {
        assertEquals(3000, normalizeContacts(List(3500) { "이름$it" to "0100000%04d".format(it) }).size)
    }
}
