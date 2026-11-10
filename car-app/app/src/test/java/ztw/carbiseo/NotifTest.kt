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

    @Test fun 업무방은_광고외_전부_쉼방은_즐겨찾기만_정차는_시각따라() {
        val l = listOf(n(1, "김부장"), n(2, "엄마"), n(3, "쇼핑몰", "(광고) 할인"), InboxItem(4, "이", "x", false, 0L, ""))
        assertEquals(listOf(1L, 2L), notifNow(l, Room.DRIVE_WORK, favs).map { it.id })
        assertEquals(listOf(2L), notifNow(l, Room.DRIVE_REST, favs).map { it.id })
        assertEquals(listOf(1L, 2L), notifNow(l, Room.PARK, favs).map { it.id }) // P 업무 시간 = 업무 규칙
        assertEquals(listOf(2L), notifNow(l, Room.PARK, favs, off = true).map { it.id }) // P 퇴근 뒤 = 즐겨찾기만
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
        assertEquals(Cmd.No, voiceCmd("그만"))
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

    @Test fun 정차_중엔_못들어도_바_유지() {
        val t = NotifTalk(); val l = listOf(n(1, "김부장"))
        t.offer(l, favs)
        assertEquals(Turn(null), t.hear(null, favs, parked = true)) // 10초 듣기 타임아웃
        assertEquals(Turn(null), t.hear("오늘 날씨", favs, parked = true))
        assertEquals(NotifTalk.Step.OFFER, t.step)
        assertEquals(Turn("화면에 남겨 둘게요."), t.hear("나중에", favs, parked = true)) // 버튼을 누르면 닫힌다
        assertEquals(NotifTalk.Step.NONE, t.step)
    }

    @Test fun 문장_끝_마침표_보장() {
        assertEquals("보냈어요. 답장하시려면.", sentences("보냈어요 \n", null, "", "답장하시려면"))
        assertEquals("정말? 네!", sentences("정말?", "네!"))
        val t = NotifTalk(); t.offer(listOf(n(1, "김부장", "자료 보냈어요 ")), favs)
        assertEquals("김부장님, 카톡. 자료 보냈어요. 답장하시려면 '답장' 하고 말씀하세요.", t.hear("읽어줘", favs).say)
    }

    @Test fun 읽어준_알림은_카드에서_뺌() {
        val t = NotifTalk(); val l = listOf(n(1, "김부장"), n(2, "엄마"))
        t.offer(l, favs); assertEquals(listOf(1L, 2L), t.hear("읽어줘", favs).done)
        t.offer(l, favs); assertEquals(emptyList<Long>(), t.hear("나중에", favs).done) // 나중에 = 카드로 남김
        val call = InboxItem(3, "고객", "견적", false, 0, "")
        assertEquals(listOf(2L, 3L), unread(l + call, setOf(1L, 3L)).map { it.id }) // 통화 카드는 그대로
    }

    @Test fun 즐겨찾기_설정_문자열() {
        assertEquals(setOf("엄마", "아빠"), parseFavs(" 엄마, 아빠 ,,"))
        assertEquals(emptySet<String>(), parseFavs(""))
    }

    @Test fun 정차_낭독뒤_바는_답장하기_닫기() {
        val t = NotifTalk(); t.offer(listOf(n(1, "김부장")), favs); t.hear("읽어줘", favs, parked = true)
        assertEquals(Turn(null), t.hear(null, favs, parked = true)) // 듣기 타임아웃 → 바 유지
        assertEquals("답장하시려면 ‘답장하기’를 누르고 말씀하세요" to listOf("답장하기", "닫기"), askBar(t.step, listening = false, touch = true))
        assertEquals(listOf("나중에"), askBar(t.step, listening = true, touch = false).second) // 주행 중은 그대로
        assertEquals(Turn(null), t.hear("닫기", favs, parked = true)) // '닫기' 버튼 → 바 닫힘
        assertEquals(NotifTalk.Step.NONE, t.step)
    }

    @Test fun 무응답_묻기는_닫고_새_알림과_합쳐_다시_묻는다() {
        val t = NotifTalk()
        val first = n(1, "김부장")
        t.offer(listOf(first), favs)
        t.hear(null, favs, parked = true) // P에서 못 들음 → 바는 남는다(예전엔 여기서 새 알림을 못 물었다)
        assertEquals(true, t.waiting)
        val merged = (t.dropStale() + listOf(first, n(2, "박대리"))).distinctBy { it.id }.sortedBy { it.id } // 같은 알림은 한 번만
        assertEquals(NotifTalk.Step.NONE, t.step)
        assertEquals("카톡 2건, 김부장님 외 1명. 읽어드릴까요?", t.offer(merged, favs)?.say)
    }

    @Test fun 읽어준_뒤_대기는_합칠_알림이_없다() {
        val t = NotifTalk()
        t.offer(listOf(n(1, "김부장")), favs); t.hear("읽어줘", favs)
        assertEquals(true, t.waiting) // 답장 대기
        assertEquals(emptyList<InboxItem>(), t.dropStale())
        assertEquals(false, t.waiting)
    }

    @Test fun 번호는_한_자리씩_읽는다() {
        assertEquals("끝자리 육 공 공 공", spellDigits("끝자리 6000"))
        assertEquals("끝자리 일 이 삼 사님", spellDigits("****1234님"))
        assertEquals("전화 공 일 공 일 이 삼 사 오 육 칠 팔 로 왔어요", spellDigits("전화 010-1234-5678 로 왔어요"))
        assertEquals("공 일 공 일 이 삼 사 오 육 칠 팔", spellDigits("01012345678"))
    }

    @Test fun 시각_분_금액은_그대로() {
        for (t in listOf("15:30까지 연락 주세요", "25분 늦어요", "5000원 입금", "번호 3개, 전화 100건", "2026-11-02 일정", "3시 30분")) assertEquals(t, spellDigits(t))
    }

    private fun call(label: String = "김부장", num: String = "01000000000", sum: String) = InboxItem(1, label, sum, false, 0L, "", fromNumber = num)

    @Test fun 먼저_제안_기한이면_시각_문장() {
        assertEquals("확인하고 오후 3시 30분 전에 연락드리겠습니다", callbackMessage(call(sum = "오후 3시 30분까지 견적 부탁")))
        // 오전/오후가 없으면 1~7시는 업무 시간 기준 오후, 그 밖은 그대로
        assertEquals("확인하고 오후 3시 30분 전에 연락드리겠습니다", callbackMessage(call(sum = "3시 30분까지 연락 주세요")))
        assertEquals("확인하고 오후 2시 전에 연락드리겠습니다", callbackMessage(call(sum = "2시 전까지 회신")))
        assertEquals("확인하고 오전 10시 전에 연락드리겠습니다", callbackMessage(call(sum = "10시까지 회신")))
        assertEquals("확인하고 오전 3시 전에 연락드리겠습니다", callbackMessage(call(sum = "오전 3시까지 회신")))
        assertEquals("확인하고 오후 3시 30분 전에 연락드리겠습니다", callbackMessage(call(sum = "15:30까지 연락")))
        assertEquals("확인하고 곧 연락드리겠습니다", callbackMessage(call(sum = "회신 부탁드립니다")))
        assertEquals("김부장님께 '확인하고 곧 연락드리겠습니다'라고 문자 먼저 보내 드릴까요?", callbackAsk(call(sum = "x"), "확인하고 곧 연락드리겠습니다"))
        assertEquals("01000000000" to "확인하고 곧 연락드리겠습니다", taskDraft(callbackTask(call(sum = "x"), "확인하고 곧 연락드리겠습니다")))
    }

    @Test fun 먼저_제안_조건이_없으면_묻지_않는다() {
        assertNull(callbackMessage(call(sum = "그냥 전화 드렸어요")))
        assertNull(callbackMessage(call(num = "", sum = "회신 부탁")))
        assertNull(callbackMessage(call(label = "끝번호 0000", sum = "회신 부탁")))
        assertNull(callbackMessage(call(label = "", sum = "회신 부탁")))
    }
}
