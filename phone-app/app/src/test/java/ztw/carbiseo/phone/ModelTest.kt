package ztw.carbiseo.phone

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ModelTest {
    private val z = ZoneId.of("Asia/Seoul")
    private fun t(d: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, d, h, m, 0, 0, z).toInstant().toEpochMilli()
    private val now = t(1, 17, 41)

    @Test fun 기록은_저장했다_그대로_읽힌다_탭과_줄바꿈은_지운다() {
        val es = listOf(Event(1, Kind.SENT, "카카오톡", "팀\t단톡\n방"), Event(2, Kind.REPLIED, "문자", "김부장"))
        assertEquals(listOf(es[0].copy(who = "팀 단톡 방"), es[1]), decode(encode(es)))
        assertEquals(emptyList<Event>(), decode(null))
        assertEquals(1, decode("1\tSENT\ta\tb\n깨진줄\n2\tNOPE\ta\tb").size)
    }

    @Test fun 오늘_보낸_알림만_센다_최대개수로_자른다() {
        val es = listOf(Event(t(1, 9), Kind.SENT, "a", "b"), Event(t(1, 10), Kind.SEND_FAIL, "a", "b"),
            Event(t(1, 11), Kind.REPLIED, "a", "b"), Event(t(10, 23, 59).minus(10L * 86_400_000), Kind.SENT, "a", "b"))
        assertEquals(1, sentToday(es, now, z))
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
}
