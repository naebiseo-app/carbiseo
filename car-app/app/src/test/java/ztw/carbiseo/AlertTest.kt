package ztw.carbiseo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// 이 테스트가 지키는 동작: 과속·주의구간 경고의 판정(alertFor)과 해제 지연(AlertLatch), 경고 중 음성 보류(SpeechHold).
// 과속은 제한속도 +3km/h까지 허용하고 주의구간보다 우선한다. 과속 경고는 제한속도 이하가 2초 이어져야 풀리고, 주의구간은 바로 풀린다.
// 경고 중에 들어온 문장·전화는 쌓았다가 풀릴 때 한 번에 말한다.
class AlertTest {
    @Test fun 경계값_제한속도_더하기_3까지는_과속_아님() {
        assertEquals(Alert.NONE, alertFor(53f, 50, false, false))
        assertEquals(Alert.OVERSPEED, alertFor(53.1f, 50, false, false))
    }

    @Test fun 우선순위_과속이_주의구간보다_위() {
        assertEquals(Alert.OVERSPEED, alertFor(80f, 50, false, true))
        assertEquals(Alert.OVERSPEED, alertFor(10f, 50, true, true))
        assertEquals(Alert.CAUTION, alertFor(40f, 50, false, true))
    }

    @Test fun null이면_그_조건_무시() {
        assertEquals(Alert.NONE, alertFor(null, 50, false, false))
        assertEquals(Alert.NONE, alertFor(120f, null, false, false))
        assertEquals(Alert.OVERSPEED, alertFor(null, null, true, false))
        assertEquals(Alert.CAUTION, alertFor(null, null, false, true))
    }

    @Test fun 과속_해제는_제한속도_이하_2초_유지() {
        val c = FakeClock(0); val l = AlertLatch(c)
        assertEquals(Alert.OVERSPEED, l.update(60f, 50, false, false))
        c.ms = 100; assertEquals(Alert.OVERSPEED, l.update(52f, 50, false, false)) // 여유 안이지만 제한 초과 -> 유지
        c.ms = 200; assertEquals(Alert.OVERSPEED, l.update(50f, 50, false, false)) // 진정 시작
        c.ms = 2100; assertEquals(Alert.OVERSPEED, l.update(49f, 50, false, false))
        c.ms = 2200; assertEquals(Alert.NONE, l.update(49f, 50, false, false))
    }

    @Test fun 해제_대기중_다시_넘으면_시계_리셋() {
        val c = FakeClock(0); val l = AlertLatch(c)
        l.update(60f, 50, false, false)
        c.ms = 100; l.update(45f, 50, false, false)
        c.ms = 1500; l.update(51f, 50, false, false) // 제한 초과 -> 리셋
        c.ms = 1600; l.update(45f, 50, false, false)
        c.ms = 3500; assertEquals(Alert.OVERSPEED, l.update(45f, 50, false, false))
        c.ms = 3600; assertEquals(Alert.NONE, l.update(45f, 50, false, false))
    }

    @Test fun 과속_해제후_주의구간이면_CAUTION() {
        val c = FakeClock(0); val l = AlertLatch(c)
        l.update(null, null, true, true)
        c.ms = 10; assertEquals(Alert.OVERSPEED, l.update(null, null, false, true))
        c.ms = 2010; assertEquals(Alert.CAUTION, l.update(null, null, false, true))
        c.ms = 2020; assertEquals(Alert.NONE, l.update(null, null, false, false)) // CAUTION은 즉시 해제
    }

    @Test fun 음성_보류_후_문장은_최신_1개만() {
        val h = SpeechHold()
        assertEquals("a", h.offer("a", false))
        assertNull(h.offer("b", true))
        assertNull(h.offer("c", true))
        assertNull(h.release(true, Room.DRIVE_WORK))
        assertEquals("c.", h.release(false, Room.DRIVE_WORK)) // 끝 마침표 보장
        assertNull(h.release(false, Room.DRIVE_WORK))
    }

    private fun call(id: Long, urgent: Boolean = false) = InboxItem(id, "x", "y", urgent, 0L, "")

    @Test fun 경고중_전화는_쌓였다가_급함_건수_한_문장() {
        val h = SpeechHold()
        assertNull(h.offerItems(listOf(call(1, urgent = true)), Room.DRIVE_WORK, true))
        assertNull(h.offerItems(listOf(call(2)), Room.DRIVE_WORK, true))
        assertEquals("급한 전화 1건 포함 2건, AI가 용건을 받아 두었습니다.", h.release(false, Room.DRIVE_WORK))
        assertNull(h.release(false, Room.DRIVE_WORK))
    }

    @Test fun 보류분은_비우면_사라진다() {
        val h = SpeechHold()
        h.offerItems(listOf(call(1)), Room.DRIVE_WORK, true); h.offer("t", true)
        h.clear()
        assertNull(h.release(false, Room.DRIVE_WORK))
    }
}
