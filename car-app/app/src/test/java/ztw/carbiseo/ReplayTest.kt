package ztw.carbiseo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 이 테스트가 지키는 동작: 재현 통화 대본의 해석(parseReplay)과 진행(ReplayRun), 줄별 화면·음성 처리(replayAct).
// 정차 중 도움은 카드로, 운전 중은 귓속말 음성으로 나오고, 동승자 모드에서는 읽지 않고 자막과 카드만 보인다.
class ReplayTest {
    private val raw = "한빛물산 김민수 과장\t9000\n" +
        "0\tai\t\tAI 비서가 함께 듣습니다\n" +
        "3000\tpeer\t\t납품 일정이 궁금해서요.\n" +
        "4000\thint\t추천 답변\t오늘 3시 미팅에서 확정하자고 답하세요\n" +
        "6000\tdriver\t\t네 알겠습니다.\n" +
        "7000\tpeer\t\t감사합니다."

    @Test fun 대본_해석_제목과_줄() {
        val s = parseReplay(raw)!!
        assertEquals("한빛물산 김민수 과장", s.title); assertEquals(9000L, s.endMs)
        assertEquals(listOf("ai", "peer", "hint", "driver", "peer"), s.lines.map { it.who })
        assertEquals("추천 답변", s.lines[2].label)
    }

    @Test fun 형식이_틀리면_null_모르는_줄은_건너뜀() {
        assertNull(parseReplay("")); assertNull(parseReplay("제목만")); assertNull(parseReplay("제목\tabc\n0\tai\t\t고지"))
        assertNull(parseReplay("제목\t1000")) // 줄 없음
        val s = parseReplay("제목\t1000\n0\tai\t\t고지\n500\tbad\t\t무시\n쓰레기\n700\tpeer\t\t")!!
        assertEquals(1, s.lines.size)
    }

    @Test fun 진행_순서와_한번씩만() {
        val r = ReplayRun(parseReplay(raw)!!)
        assertEquals(listOf("ai"), r.due(0).map { it.who })
        assertTrue(r.due(2000).isEmpty())
        assertEquals(listOf("peer", "hint"), r.due(4000).map { it.who })
        assertTrue(r.due(4000).isEmpty())
        assertEquals(listOf("driver", "peer"), r.due(8000).map { it.who }) // 틱이 밀려도 건너뛰지 않는다
        assertTrue(!r.done(8000)); assertTrue(r.done(9000))
    }

    @Test fun 첫_줄은_고지_상대는_읽고_운전자는_자막만() {
        val v = ReplayView("t"); val l = parseReplay(raw)!!.lines
        assertEquals("AI 비서가 함께 듣습니다", replayAct(v, l[0], Room.PARK, false).speech)
        val peer = replayAct(v, l[1], Room.DRIVE_WORK, false)
        assertEquals("납품 일정이 궁금해서요.", peer.speech); assertEquals(l[1], peer.view.caption)
        val me = replayAct(v, l[3], Room.DRIVE_WORK, false)
        assertNull(me.speech); assertEquals(l[3], me.view.caption)
    }

    @Test fun 정차_중_도움은_카드_운전_중은_귓속말() {
        val h = parseReplay(raw)!!.lines[2]; val v = ReplayView("t")
        for (room in listOf(Room.PARK, null)) replayAct(v, h, room, false).let { assertEquals(h, it.view.hint); assertNull(it.speech) }
        for (room in listOf(Room.DRIVE_WORK, Room.DRIVE_REST)) replayAct(v, h, room, false).let { assertNull(it.view.hint); assertEquals("추천 답변. 오늘 3시 미팅에서 확정하자고 답하세요.", it.speech) }
    }

    @Test fun 상대가_다시_말하면_카드를_내린다() {
        val l = parseReplay(raw)!!.lines
        val shown = replayAct(ReplayView("t"), l[2], Room.PARK, false).view
        assertEquals(l[2], shown.hint)
        replayAct(shown, l[3], Room.PARK, false).view.let { assertEquals(l[2], it.hint); assertEquals(l[3], it.caption) } // 운전자 자막은 카드를 유지
        assertNull(replayAct(shown, l[4], Room.PARK, false).view.hint)
    }

    @Test fun 동승자_모드는_읽지_않고_자막과_카드만() {
        val l = parseReplay(raw)!!.lines; val v = ReplayView("t")
        assertNull(replayAct(v, l[0], Room.DRIVE_WORK, true).speech)
        assertNull(replayAct(v, l[1], Room.DRIVE_WORK, true).speech)
        assertNull(replayAct(v, l[2], Room.DRIVE_WORK, true).speech)
        assertEquals(l[2], replayAct(v, l[2], Room.PARK, true).view.hint)
    }
}
