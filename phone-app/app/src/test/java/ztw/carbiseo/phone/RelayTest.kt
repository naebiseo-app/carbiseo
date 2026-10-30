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
}
