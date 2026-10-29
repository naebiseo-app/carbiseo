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
        assertEquals("부재중 전화 한 건, AI가 용건을 받아 두었습니다.", speechFor(listOf(item(1)), Room.DRIVE_REST))
    }

    @Test fun 급함이면_급한_전화() {
        assertEquals("급한 전화 한 건, AI가 용건을 받아 두었습니다.", speechFor(listOf(item(1, urgent = true)), Room.DRIVE_WORK))
    }

    @Test fun 여러_건이면_건수() {
        assertEquals("부재중 전화 2건, AI가 용건을 받아 두었습니다.", speechFor(listOf(item(1), item(2)), Room.DRIVE_WORK))
        assertTrue(speechFor(listOf(item(1), item(2, urgent = true)), Room.DRIVE_WORK)!!.startsWith("급한 전화 2건"))
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
}
