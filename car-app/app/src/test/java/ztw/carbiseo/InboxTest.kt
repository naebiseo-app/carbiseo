package ztw.carbiseo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class InboxTest {
    private fun item(id: Long, urgent: Boolean = false, label: String = "김", summary: String = "급건 아님", raw: String = "") =
        InboxItem(id, label, summary, urgent, 0L, raw)

    @Test fun 주행중_새항목이면_부재중_문장() {
        assertEquals("부재중 전화 한 건, AI가 용건을 받아 두었습니다.", speechFor(listOf(item(1)), Room.DRIVE_WORK))
    }

    @Test fun 응대_모드별_문장_끔은_무음() {
        assertEquals("부재중 전화 한 건, 안내 멘트로 응대했어요.", speechFor(listOf(item(1)), Room.DRIVE_WORK, "ment"))
        assertNull(speechFor(listOf(item(1, urgent = true)), Room.DRIVE_WORK, "off"))
    }

    @Test fun 정차_때_안_본_급한_통화만_알린다() {
        val items = listOf(item(1, urgent = true), item(2, urgent = true), item(3))
        assertEquals("급한 전화 1건, 아직 안 보셨어요.", parkReminder(items, setOf(1L)))
        assertNull(parkReminder(items, setOf(1L, 2L)))
    }

    @Test fun 퇴근길은_급한_전화만_읽는다() {
        assertNull(speechFor(listOf(item(1), item(2)), Room.DRIVE_REST))
        assertEquals("급한 전화 한 건, AI가 용건을 받아 두었습니다.", speechFor(listOf(item(1), item(2, urgent = true)), Room.DRIVE_REST))
    }

    @Test fun 급함이면_급한_전화() {
        assertEquals("급한 전화 한 건, AI가 용건을 받아 두었습니다.", speechFor(listOf(item(1, urgent = true)), Room.DRIVE_WORK))
    }

    @Test fun 여러_건이면_건수() {
        assertEquals("부재중 전화 2건, AI가 용건을 받아 두었습니다.", speechFor(listOf(item(1), item(2)), Room.DRIVE_WORK))
        assertEquals("급한 전화 1건 포함 2건, AI가 용건을 받아 두었습니다.", speechFor(listOf(item(1), item(2, urgent = true)), Room.DRIVE_WORK))
        assertTrue(speechFor(listOf(item(1, urgent = true), item(2, urgent = true)), Room.DRIVE_WORK)!!.startsWith("급한 전화 2건,"))
    }

    @Test fun PARK_미판정_항목없음은_읽지_않는다() {
        assertNull(speechFor(listOf(item(1)), Room.PARK))
        assertNull(speechFor(listOf(item(1)), null))
        assertNull(speechFor(emptyList(), Room.DRIVE_WORK))
    }

    @Test fun 같은_통화의_웹훅_이벤트는_건수에_안_센다() {
        val ev = InboxItem(2, "", "", false, 0L, "{}", kind = "event")
        assertEquals("부재중 전화 한 건, AI가 용건을 받아 두었습니다.", speechFor(listOf(item(1), ev, ev.copy(id = 3)), Room.DRIVE_WORK))
        assertNull(speechFor(listOf(ev), Room.DRIVE_WORK))
    }

    @Test fun 서버가_비면_카드도_비우고_모르면_그대로() {
        val cards = listOf(item(5), item(3))
        assertEquals(emptyList<InboxItem>(), pruneGone(cards, Long.MAX_VALUE)) // 관리자 inbox 비우기 뒤
        assertEquals(listOf(5L), pruneGone(cards, 4).map { it.id })          // 오래된 쪽만 지워짐
        assertEquals(cards, pruneGone(cards, null))                          // 옛 서버: min_id 없음
    }

    @Test fun since는_최대_id로_갱신_되돌아가지_않는다() {
        assertEquals(7L, nextSince(3, listOf(item(7), item(5))))
        assertEquals(9L, nextSince(9, listOf(item(7))))
        assertEquals(4L, nextSince(4, emptyList()))
    }

    @Test fun 줄표시_라벨요약_없으면_raw_80자() {
        val raw = "가".repeat(100)
        val line = itemLine(item(1, label = "", summary = "", raw = raw), ZoneOffset.UTC)
        assertEquals("가".repeat(80), line.substringAfter("00:00 "))
        assertEquals("[급함] 01-01 00:00 김 / 급건 아님", itemLine(item(1, urgent = true), ZoneOffset.UTC))
    }

    @Test fun 오늘_통화만_센다() {
        val z = ZoneOffset.ofHours(9)
        val today = java.time.LocalDate.of(2026, 10, 29)
        val at = { h: Int, d: Int -> java.time.LocalDateTime.of(2026, 10, d, h, 0).toInstant(z).toEpochMilli() }
        val items = listOf(
            InboxItem(1, "", "", false, at(9, 29), ""), InboxItem(2, "", "", false, at(23, 29), ""),
            InboxItem(3, "", "", false, at(10, 29), "", kind = "event"), InboxItem(4, "", "", false, at(23, 28), ""),
        )
        assertEquals(2, todayCalls(items, today, z))
        assertEquals(0, todayCalls(emptyList(), today, z))
    }

    @Test fun 주차화면_급한것_먼저_그안에서_최신순() {
        val l = parkOrder(listOf(item(1), item(2, urgent = true), item(3), item(4, urgent = true)))
        assertEquals(listOf(4L, 2L, 3L, 1L), l.map { it.id })
    }

    @Test fun 할일_본문에서_번호와_내용() {
        assertEquals("01012345678" to "3시 도착", taskDraft("박 대리 010-1234-5678에게 문자 / 내용: 3시 도착"))
        assertEquals("" to "박 대리에게 문자", taskDraft("박 대리에게 문자"))
    }

    @Test fun 보낸문자_상태_IP차단은_정직하게() {
        val s = InboxItem(1, "x", "y", false, 0L, "", kind = "sent")
        assertEquals("보냄", sentStatus(s.copy(status = "sent")))
        assertEquals("못 보냄(서버 IP 제한)", sentStatus(s.copy(status = "failed", code = "overseas_ip_blocked")))
        assertEquals("못 보냄(http_500)", sentStatus(s.copy(status = "failed", code = "http_500")))
    }

    @Test fun 확인할_건수는_안_연_카드만_보낸문자_제외() {
        val l = listOf(item(1), item(2), InboxItem(3, "", "", false, 0L, "", kind = "sent"), InboxItem(4, "", "", false, 0L, "", kind = "hint"))
        assertEquals(2, todo(l, setOf(1L)))
    }

    // H7: 업무 외에 받은 통화 = 넘긴 건 → 다음 날 첫 업무 운전에 한 문장 + P 화면 묶음
    private val utc = ZoneOffset.UTC
    private fun at(day: Int, h: Int) = (java.time.LocalDate.of(2026, 11, day).atTime(h, 0).toEpochSecond(utc)) * 1000
    private fun call(id: Long, t: Long, room: String = "") = InboxItem(id, "김", "용건", false, t, "", room = room)
    private val mon = java.time.LocalDate.of(2026, 11, 2).toEpochDay()

    @Test fun 퇴근길_업무외에_받은_통화만_넘긴다() {
        val l = listOf(call(1, 0), InboxItem(2, "", "", false, 0L, "", kind = "hint"))
        assertEquals(mapOf(1L to mon), deferNew(l, false, true, mon, RoomSettings(), utc))
        assertEquals(emptyMap<Long, Long>(), deferNew(l, false, false, mon, RoomSettings(), utc)) // 업무 중 받은 건 넘기지 않음
    }

    @Test fun 재시작_첫_폴링은_받은_시각과_서버방으로_어제오늘만() {
        val l = listOf(call(1, at(2, 19)), call(2, at(2, 10)), call(3, at(2, 10), room = "DRIVE_REST"), call(4, at(1, 20)), call(5, at(3, 2)))
        // 월 19시(업무 외), 월 10시(업무), 월 10시인데 서버 방이 쉼, 일요일 20시(이틀 전 → 제외), 화 02시(오늘)
        assertEquals(mapOf(1L to mon, 3L to mon, 5L to mon + 1), deferNew(l, true, false, mon + 1, RoomSettings(), utc))
    }

    @Test fun 넘긴_건은_다음날부터_안_본_것만() {
        val items = listOf(call(1, 0), call(2, 0), call(3, 0))
        val d = mapOf(1L to mon, 2L to mon, 3L to mon + 1)
        assertEquals(listOf(1L, 2L), carryOver(items, d, emptySet(), mon + 1).map { it.id })
        assertEquals(listOf(2L), carryOver(items, d, setOf(1L), mon + 1).map { it.id })
        assertEquals(emptyList<Long>(), carryOver(items, d, emptySet(), mon).map { it.id })  // 당일 저녁엔 아직 '어제 넘긴 건' 아님
        assertEquals("어제 넘긴 전화 2건 있어요. 차 세우면 보여 드릴게요.", carrySpeech(carryOver(items, d, emptySet(), mon + 1), d, mon + 1))
        assertEquals("지난번에 넘긴 전화 3건 있어요. 차 세우면 보여 드릴게요.", carrySpeech(carryOver(items, d, emptySet(), mon + 3), d, mon + 3)) // 주말 넘김
        assertNull(carrySpeech(emptyList(), d, mon + 1))
    }

    @Test fun 넘긴_건_저장_형식() {
        val m = mapOf(3L to 20000L, 1L to 20001L)
        assertEquals(m, decodeDeferred(encodeDeferred(m)))
        assertEquals(emptyMap<Long, Long>(), decodeDeferred(null))
        assertEquals(mapOf(1L to 2L), decodeDeferred("1:2,x:y,3"))
        assertEquals(300, decodeDeferred(encodeDeferred((1L..400L).associateWith { 1L })).size)
    }

    @Test fun 차에서_만든_카드는_서버_정리에_안_지워진다() {
        val local = item(LOCAL_ID_BASE + 5)
        assertEquals(listOf(local.id, 7L), pruneGone(listOf(local, item(7), item(3)), 5).map { it.id })
        assertEquals(listOf(local.id), pruneGone(listOf(local, item(7)), Long.MAX_VALUE).map { it.id })
    }

    @Test fun 메일_줄이_있으면_메일_초안() {
        assertEquals(Triple("park@example.com", "회의 자료", "내일 3시 자료 보냅니다"), mailDraft("메일: park@example.com / 회의 자료 / 내일 3시 자료 보냅니다"))
        assertEquals(Triple("박 대리", "제목", "본문 / 슬래시 포함"), mailDraft("박 대리에게 보내기\n메일：박 대리 / 제목 / 본문 / 슬래시 포함")) // 내용 칸은 나머지 전부
        assertEquals(Triple("a@b.co", "", "제목 없이"), mailDraft("메일:a@b.co /  / 제목 없이"))
        assertNull(mailDraft("박 대리 010-1234-5678에게 문자 / 내용: 3시 도착")) // 문자 초안
        assertNull(mailDraft("메일: park@example.com / 제목만"))               // 칸 모자람
        assertNull(mailDraft("메일: / 제목 / 내용"))                           // 받는 사람 없음
    }

    @Test fun 바로_연결_전화는_피로여도_한_줄로_읽는다() {
        val d = item(1, urgent = true, label = "김민수", summary = "견적 확인 부탁")
        assertEquals("바로 연결: 김민수님 전화, 용건 견적 확인 부탁.", directSpeech(listOf(d)))
        assertEquals("바로 연결: 김민수님 전화, 용건 견적 확인 부탁.", directSpeech(listOf(item(1, label = "김민수", summary = "견적 확인 부탁").copy(direct = true)))) // meta.direct만 있어도
        assertEquals("바로 연결: 알 수 없는 번호님 전화.", directSpeech(listOf(item(2, urgent = true, label = " ", summary = ""))))
        assertEquals(2, directSpeech(listOf(d, item(3, urgent = true, label = "박", summary = "급함")))!!.split("바로 연결").size - 1)
        assertNull(directSpeech(listOf(item(4)))) // 일반 부재중은 피로에 읽지 않는다
        assertNull(directSpeech(listOf(d.copy(kind = "hint"))))
        assertNull(directSpeech(listOf(d), "off"))
        assertEquals("바로 연결: 김민수님 전화, 용건 견적 확인 부탁.", directSpeech(listOf(d, d)))  // 같은 id는 한 번
    }

    @Test fun 퇴근길도_direct_카드는_읽는다() {
        assertEquals("급한 전화 한 건, AI가 용건을 받아 두었습니다.", speechFor(listOf(item(1), item(2).copy(direct = true)), Room.DRIVE_REST))
    }

    @Test fun 완료한_카드는_목록과_건수에서_빠진다() {
        val l = listOf(item(1), item(2, urgent = true), item(3))
        val shown = hideCards(l, setOf(2L))
        assertEquals(listOf(1L, 3L), shown.map { it.id })
        assertEquals(2, todo(shown, emptySet()))
        assertNull(parkReminder(shown, emptySet()))          // 완료한 급한 전화는 정차 알림에서도 빠진다
        assertEquals(l, hideCards(l, emptySet()))
    }
}
