package ztw.carbiseo.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 이 테스트가 지키는 동작(Relay.kt 순수 함수):
// - toMsg: 메시지형 알림만 앱 이름을 붙여 넘기고, 묶음 요약·빈 값은 버리며, 내용 길이를 자른다.
// - normalizeContacts: 휴대폰 번호 꼴만 정리해 남기고, 같은 이름 중복과 3000건 초과를 막는다.
// - 하차 정보 응답: '없음'과 '읽지 못함'을 가르고, 저장본에서는 전화번호를 빼고 끝낸 일을 뺀다.
class RelayTest {
    // 알림 1건을 toMsg에 넣는 도우미. 기본값은 보낸이·내용이 있고 답장 버튼이 있는 일반 알림.
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

    @Test fun 구글_연결_문구는_상태별로() {
        assertEquals("연결", googleLabels(null).second)
        assertEquals("연결", googleLabels(GoogleState(false, false)).second)
        assertEquals("연결 끊기", googleLabels(GoogleState(true, false)).second)
        assertEquals("다시 연결", googleLabels(GoogleState(true, true)).second)
        assertEquals(true, googleLabels(GoogleState(true, true)).first.startsWith("다시 연결 필요"))
    }

    @Test fun 하차_정보_응답을_읽는다() {
        val j = """{"parked":{"lat":37.4,"lng":127.1,"address":"가명로 1","memo":"B2 3열","at":1700000000000,"tasks":[
            {"id":5,"title":"김민수님께 전화하기","number":"010-0000-0000"},{"id":-3,"title":"약국","number":null},{"id":7,"title":" "},{"title":"id 없음"},"깨짐"]}}"""
        assertEquals(Parked(37.4, 127.1, "가명로 1", "B2 3열", 1700000000000, listOf(ParkedTask(5, "김민수님께 전화하기", "010-0000-0000"), ParkedTask(-3, "약국", null))), parseParked(j))
        assertEquals(Parked(1.0, 2.0, "", "", 0, emptyList()), parseParked("""{"parked":{"lat":1,"lng":2,"address":null}}"""))
    }

    @Test fun 하차_정보가_없거나_깨지면_null() {
        assertNull(parseParked("""{"parked":null}"""))
        assertNull(parseParked("""{"parked":{"lng":2}}"""))
        assertNull(parseParked("not json"))
        assertNull(parseParked(""))
    }

    @Test fun 하차_정보의_보조_줄과_전체_건수() {
        val j = """{"parked":{"lat":1,"lng":2,"total":12,"tasks":[{"id":5,"title":"김민수님께 전화하기","sub":"오후 2:12 부재중","number":null},{"id":6,"title":"약국","sub":""}]}}"""
        assertEquals(Parked(1.0, 2.0, "", "", 0, listOf(ParkedTask(5, "김민수님께 전화하기", null, "오후 2:12 부재중"), ParkedTask(6, "약국", null, null)), 12), parseParked(j))
        assertEquals(2, parseParked("""{"parked":{"lat":1,"lng":2,"total":0,"tasks":[{"id":5,"title":"a"},{"id":6,"title":"b"}]}}""")!!.total) // total이 목록보다 작으면 목록 건수
    }

    @Test fun 응답_읽기는_없음과_실패를_가른다() {
        val ok = """{"parked":{"lat":1,"lng":2,"tasks":[]}}"""
        assertEquals(ParkedRead.Found(Parked(1.0, 2.0, "", "", 0, emptyList()), ok), parseParkedRead(ok))
        assertEquals(ParkedRead.None, parseParkedRead("""{"parked":null}"""))
        assertEquals(ParkedRead.Failed, parseParkedRead("<html>로그인</html>")) // 200 안내문
        assertEquals(ParkedRead.Failed, parseParkedRead("""{"error":"x"}"""))
        assertEquals(ParkedRead.Failed, parseParkedRead("""{"parked":{"lng":2}}"""))
        assertTrue(parseParkedRead("") is ParkedRead.Failed)
    }

    @Test fun `저장본엔_번호가_없고_끝낸_일은_빠진다`() {
        val raw = """{"parked":{"lat":1,"lng":2,"total":3,"tasks":[{"id":5,"title":"김민수님께 전화하기","number":"01000000000"},{"id":6,"title":"견적서 확인"}]}}"""
        assertEquals(listOf(5L to null, 6L to null), parseParked(parkedCache(raw))!!.tasks.map { it.id to it.number })
        val left = parseParked(parkedCache(raw, 5))!!
        assertEquals(listOf(6L), left.tasks.map { it.id }); assertEquals(2, left.total)
    }
}
