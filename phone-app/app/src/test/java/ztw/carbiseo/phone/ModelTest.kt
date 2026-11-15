package ztw.carbiseo.phone

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

// 이 테스트가 지키는 동작(Model.kt 순수 함수):
// - 기록(Event)은 저장했다 읽어도 그대로이고, 깨진 줄은 버려지며, 최대 MAX_EVENTS건까지만 남는다.
// - 홈 배너·첫 실행 버튼·날짜 이름·타임라인 문구는 폰이 아는 것(권한, 서버 전달 결과)만으로 정해진다.
// - 하차 정보: 서버 읽기가 실패하면 저장본을 그대로 보이고, '없음'일 때만 지우며, 틱으로 숨긴 일은 total에서도 뺀다.
class ModelTest {
    private val z = ZoneId.of("Asia/Seoul")
    // 2026년 11월 d일 h시 m분(서울)의 epoch 밀리초.
    private fun t(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, z).toInstant().toEpochMilli()
    private val now = t(1, 17, 41) // 기준 시각: 11월 1일 17:41

    @Test fun 기록은_저장했다_그대로_읽힌다_탭과_줄바꿈은_지운다() {
        val es = listOf(Event(1, Kind.SENT, "카카오톡", "팀\t단톡\n방"), Event(2, Kind.REPLIED, "문자", "김부장"))
        assertEquals(listOf(es[0].copy(who = "팀 단톡 방"), es[1]), decode(encode(es)))
        assertEquals(emptyList<Event>(), decode(null))
        assertEquals(1, decode("1\tSENT\ta\tb\n깨진줄\n2\tNOPE\ta\tb").size)
    }

    @Test fun 오늘_보낸_알림만_센다_최대개수로_자른다() {
        val es = listOf(Event(t(1, 9), Kind.SENT, "a", "b"), Event(t(1, 10), Kind.SEND_FAIL, "a", "b"),
            Event(t(1, 11), Kind.REPLIED, "a", "b"), Event(t(10, 23, 59).minus(10L * 86_400_000), Kind.SENT, "a", "b"))
        assertEquals(1, sentToday(es, now, z)) // 오늘의 SENT 1건만(실패·답장·10월 31일 것은 제외)
        var big = emptyList<Event>(); repeat(MAX_EVENTS + 5) { big = add(big, Event(it.toLong(), Kind.SENT, "a", "b")) }
        assertEquals(MAX_EVENTS, big.size); assertEquals((MAX_EVENTS + 4).toLong(), big.first().at)
    }

    @Test fun 배너는_폰이_아는_것만_말한다() {
        assertEquals(Tone.WARN, banner(false, emptyList(), now, z).tone)
        assertEquals("차와 연결 대기", banner(true, emptyList(), now, z).title)
        assertEquals("차와 연결 대기", banner(true, listOf(Event(t(1, 9) - 86_400_000, Kind.SENT, "a", "b")), now, z).title) // 어제 것은 대기
        val ok = banner(true, listOf(Event(t(1, 17, 20), Kind.REPLIED, "a", "b"), Event(t(1, 17, 2), Kind.SENT, "a", "b")), now, z)
        assertEquals(Tone.ACTIVE, ok.tone); assert("17:02" in ok.sub)
        assertEquals(Tone.WARN, banner(true, listOf(Event(t(1, 17), Kind.SEND_FAIL, "a", "b"), Event(t(1, 16), Kind.SENT, "a", "b")), now, z).tone)
    }

    @Test fun 첫실행_버튼은_남은_단계를_차례로() {
        assertEquals(Step.PICK_CARRIER, step(null, false, false, false))
        assertEquals(Step.FORWARD, step("KT", false, false, true))
        assertEquals(Step.CONFIRM_FORWARD, step("KT", true, false, false))
        assertEquals(Step.LISTENER, step("KT", true, true, false))
        assertEquals(Step.DONE, step(null, false, true, true))
        assertEquals("착신전환 켜기", ctaLabel(Step.FORWARD))
        assertEquals(listOf(true, false), progress(true, false))
    }

    @Test fun 날짜_이름과_타임라인_문구() {
        assertEquals("오늘", dayLabel(t(1, 1), now, z)); assertEquals("10월 30일", dayLabel(t(1, 1) - 2 * 86_400_000, now, z))
        assertEquals("어제", dayLabel(t(1, 1) - 86_400_000, now, z))
        assertEquals("음성 답장 보냄" to "카카오톡 · 팀 단톡방에게", line(Event(0, Kind.REPLIED, "카카오톡", "팀 단톡방")))
    }

    // 폰 저장본으로 쓰는 서버 응답 원문 예.
    private val saved = """{"parked":{"lat":1,"lng":2,"memo":"B2","tasks":[{"id":5,"title":"a","number":"010-0000-0000"}]}}"""
    private val live = Parked(3.0, 4.0, "", "", 0, emptyList())

    @Test fun 하차_정보_읽은_뒤_처리() {
        assertEquals(ParkedNext(live, "raw", false), parkedNext(ParkedRead.Found(live, "raw"), saved)) // 성공+있음 = 그리고 저장
        assertEquals(ParkedNext(null, null, true), parkedNext(ParkedRead.None, saved)) // 성공+없음 = 지우고 숨김
        assertEquals(ParkedNext(parseParked(saved), null, false), parkedNext(ParkedRead.Failed, saved)) // 실패 = 저장본 그대로
        assertEquals("B2", parkedNext(ParkedRead.Failed, saved).show!!.memo)
        assertEquals(ParkedNext(null, null, false), parkedNext(ParkedRead.Failed, null)) // 저장본 없음
        assertEquals(ParkedNext(null, null, false), parkedNext(ParkedRead.Failed, "깨짐"))
    }

    @Test fun 하차_정보_숨긴_일은_total도_줄인다() {
        val p = Parked(1.0, 2.0, "", "", 0, listOf(ParkedTask(1, "a", null), ParkedTask(2, "b", null), ParkedTask(3, "c", null)), 7)
        assertEquals(p.tasks to 7, parkedLeft(p, emptySet()))
        assertEquals(p.tasks.drop(1) to 6, parkedLeft(p, setOf(1L, 99L))) // 목록에 없는 id는 세지 않는다
        assertEquals(emptyList<ParkedTask>() to 4, parkedLeft(p, setOf(1L, 2L, 3L)))
        assertEquals(emptyList<ParkedTask>() to 0, parkedLeft(Parked(1.0, 2.0, "", "", 0, p.tasks), setOf(1L, 2L, 3L)))
    }
}
