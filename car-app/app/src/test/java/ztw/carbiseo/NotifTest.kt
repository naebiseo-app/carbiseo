package ztw.carbiseo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotifTest {
    private fun n(id: Long, sender: String, text: String = "회의 자료 보냈어요", app: String = "카카오톡", reply: Boolean = true) =
        InboxItem(id, sender, text, false, 0L, "", kind = "notif", app = app, replyable = reply)
    private val favs = setOf("엄마")

    @Test fun 광고는_이름_표기만_본다() {
        assertEquals(true, isAd(n(1, "쇼핑몰", app = "광고 문자")))
        assertEquals(true, isAd(n(1, "(광고)쇼핑몰")))
        assertEquals(true, isAd(n(1, "쇼핑몰", "(광고) 오늘만 할인")))
        assertEquals(false, isAd(n(1, "김부장", "광고 시안 확인 부탁"))) // 본문 중간의 '광고'는 업무일 수 있다
    }

    @Test fun 업무방은_광고외_전부_쉼방은_즐겨찾기만_정차는_없음() {
        val l = listOf(n(1, "김부장"), n(2, "엄마"), n(3, "쇼핑몰", "(광고) 할인"), InboxItem(4, "이", "x", false, 0L, ""))
        assertEquals(listOf(1L, 2L), notifNow(l, Room.DRIVE_WORK, favs).map { it.id })
        assertEquals(listOf(2L), notifNow(l, Room.DRIVE_REST, favs).map { it.id })
        assertEquals(emptyList<Long>(), notifNow(l, Room.PARK, favs).map { it.id })
        assertEquals(emptyList<Long>(), notifNow(l, null, favs).map { it.id })
    }

    @Test fun 묶음_문장() {
        assertEquals("카톡 3건, 김부장님 외 2명. 읽어드릴까요?", notifSpeech(listOf(n(1, "김부장"), n(2, "이대리"), n(3, "엄마")), favs))
        assertEquals("카톡 2건, 엄마. 읽어드릴까요?", notifSpeech(listOf(n(1, "엄마"), n(2, "엄마")), favs))
        assertEquals("알림 2건, 김부장님 외 1명. 읽어드릴까요?", notifSpeech(listOf(n(1, "김부장"), n(2, "엄마", app = "문자")), favs))
        assertEquals("문자 1건, 엄마. 읽어드릴까요?", notifSpeech(listOf(n(1, "엄마", app = "메시지")), favs))
        assertNull(notifSpeech(emptyList(), favs))
    }

    @Test fun 음성_의도_키워드() {
        assertEquals(Cmd.Read, voiceCmd("읽어줘"))
        assertEquals(Cmd.Read, voiceCmd("응 읽어 줘"))
        assertEquals(Cmd.Later, voiceCmd("나중에"))
        assertEquals(Cmd.Later, voiceCmd("이따 봐"))
        assertEquals(Cmd.Yes, voiceCmd("응"))
        assertEquals(Cmd.Yes, voiceCmd("네, 보내 주세요"))
        assertEquals(Cmd.Yes, voiceCmd("보내"))
        assertEquals(Cmd.No, voiceCmd("아니"))
        assertEquals(Cmd.No, voiceCmd("보내지 마"))
        assertEquals(Cmd.No, voiceCmd("취소"))
        assertEquals(Cmd.Unknown, voiceCmd(""))
        assertEquals(Cmd.Unknown, voiceCmd(null))
        assertEquals(Cmd.Unknown, voiceCmd("오늘 날씨"))
        assertEquals(Cmd.Reply("지금 운전 중"), voiceCmd("답장, 지금 운전 중이라고 해줘"))
        assertEquals(Cmd.Reply("곧 간다"), voiceCmd("답장 곧 간다고 해 줘"))
        assertEquals(Cmd.Reply("10분 뒤 도착"), voiceCmd("답장 10분 뒤 도착이라고 보내줘"))
        assertEquals(Cmd.Reply("알겠어요"), voiceCmd("답장 알겠어요"))
        assertEquals(Cmd.Unknown, voiceCmd("답장"))
    }

    @Test fun 대화_읽기_답장_확인_전송() {
        val t = NotifTalk()
        val l = listOf(n(1, "김부장"), n(2, "엄마", "저녁 먹고 와?"))
        assertEquals(Turn("카톡 2건, 김부장님 외 1명. 읽어드릴까요?", listen = true), t.offer(l, favs))
        val r = t.hear("읽어줘", favs)
        assertEquals("김부장님, 카톡. 회의 자료 보냈어요. 엄마, 카톡. 저녁 먹고 와? 답장하시려면 '답장' 하고 말씀하세요.", r.say)
        assertEquals(true, r.listen)
        assertEquals(Turn("엄마님께 '곧 간다' 보낼까요?", listen = true), t.hear("답장 곧 간다고 해줘", favs)) // 마지막 답장 가능 알림
        assertEquals(Turn("보낼게요.", send = 2L to "곧 간다"), t.hear("응", favs))
        assertEquals(NotifTalk.Step.NONE, t.step)
    }

    @Test fun 대화_나중에_취소_못들음() {
        val t = NotifTalk(); val l = listOf(n(1, "김부장"))
        t.offer(l, favs); assertEquals(Turn("화면에 남겨 둘게요."), t.hear("나중에", favs))
        t.offer(l, favs); assertEquals(Turn("화면에 남겨 둘게요."), t.hear(null, favs)) // 못 들으면 카드로
        t.offer(l, favs); t.hear("답장 네 알겠습니다라고 해줘", favs)
        assertEquals(Turn("답장을 취소했어요."), t.hear("아니", favs))
        t.offer(listOf(n(1, "김부장", reply = false)), favs)
        assertEquals("김부장님, 카톡. 회의 자료 보냈어요.", t.hear("읽어줘", favs).say) // 답장 불가면 안내 없이 끝
        assertEquals(NotifTalk.Step.NONE, t.step)
        t.offer(listOf(n(1, "김부장", reply = false)), favs)
        assertEquals(Turn("이 알림은 답장할 수 없어요. 화면에 남겨 둘게요."), t.hear("답장 네", favs))
    }

    @Test fun 즐겨찾기_설정_문자열() {
        assertEquals(setOf("엄마", "아빠"), parseFavs(" 엄마, 아빠 ,,"))
        assertEquals(emptySet<String>(), parseFavs(""))
    }
}
