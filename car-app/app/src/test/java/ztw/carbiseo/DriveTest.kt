package ztw.carbiseo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveTest {
    private val s = RoomSettings()
    private val far = LatLng(37.5663, 126.9779)

    @Test fun 집_문턱_조건() {
        assertTrue(homeSoon(Room.DRIVE_REST, s.home, s, 5))
        assertFalse(homeSoon(Room.DRIVE_REST, s.home, s, 6))
        assertFalse(homeSoon(Room.DRIVE_REST, s.home, s, null))
        assertFalse(homeSoon(Room.DRIVE_WORK, s.home, s, 3))
        assertFalse(homeSoon(Room.DRIVE_REST, far, s, 3)) // 업무 종료 후 쉼 방이어도 목적지가 집이 아니면 아님
        assertFalse(homeSoon(Room.DRIVE_REST, null, s, 3))
    }

    @Test fun 조수석_기준선에서_바뀔때만() {
        for (empty in listOf(false, true)) { // true = 에뮬처럼 반전된 값
            val e = SeatEdge()
            assertEquals(null, e.update(null))
            assertEquals(null, e.update(empty)) // 시작 값 = 기준선, 띠 없음
            assertFalse(e.occupied)
            assertEquals(null, e.update(empty))
            assertEquals(true, e.update(!empty)) // 탔다
            assertTrue(e.occupied)
            assertEquals(false, e.update(empty)) // 내렸다
            assertFalse(e.occupied)
        }
    }

    @Test fun 운전_1회당_한번() {
        val o = Once()
        assertFalse(o.fire(false))
        assertTrue(o.fire(true))
        assertFalse(o.fire(true))
        o.reset()
        assertTrue(o.fire(true))
        assertEquals(false, o.fire(true))
    }

    @Test fun 연속_주행_기준() {
        assertFalse(restDue(null, 10_000_000, 1))
        assertFalse(restDue(0, 59_999, 1))
        assertTrue(restDue(0, 60_000, 1))
        assertFalse(restDue(0, 119 * 60_000L, REST_DEFAULT_MIN))
        assertTrue(restDue(0, 120 * 60_000L, REST_DEFAULT_MIN))
    }

    @Test fun 휴게소는_앞쪽에서_가장_가까운_곳() {
        val a = RestStop("A", LatLng(37.30, 127.0)); val b = RestStop("B", LatLng(37.20, 127.0)); val c = RestStop("C", LatLng(37.00, 127.0))
        val stops = listOf(a, b, c)
        val loc = LatLng(37.25, 127.0)
        assertEquals("B", nearestRestStop(loc, LatLng(36.5, 127.0), stops)!!.name) // 남쪽으로 가는 중: A는 뒤
        assertEquals("A", nearestRestStop(loc, LatLng(37.6, 127.0), stops)!!.name) // 북쪽으로 가는 중
        assertEquals(null, nearestRestStop(null, null, stops))
    }

    // H11: 지나온 휴게소로 유턴시키지 않는다. 앞쪽이 없거나 40km 밖이거나 방향을 모르면 null(내비 검색 화면).
    @Test fun 휴게소_앞쪽에_없으면_null() {
        val stops = listOf(RestStop("A", LatLng(37.30, 127.0)), RestStop("C", LatLng(37.00, 127.0)))
        assertEquals(null, nearestRestStop(LatLng(36.9, 127.0), LatLng(36.0, 127.0), stops))     // 둘 다 지나옴
        assertEquals(null, nearestRestStop(LatLng(37.5, 127.0), LatLng(36.0, 127.0), listOf(stops[1]))) // 앞쪽이지만 55km
        assertEquals(null, nearestRestStop(LatLng(37.21, 127.0), null, stops))                   // 목적지 없음 = 방향 모름
        assertEquals(null, nearestRestStop(LatLng(36.35, 127.38), LatLng(35.18, 129.08), stops))  // 대전 → 부산: 둘 다 뒤
        assertEquals(null, nearestRestStop(LatLng(36.35, 127.38), LatLng(35.18, 129.08)))        // 목록을 못 읽으면(빈 목록) null = 내비 검색
    }

    @Test fun 휴게소_파일_파싱은_주석_빈줄_좌표없는_행을_버린다() {
        val t = "# 주석\r\n\r\n휴게소\t기흥휴게소\t37.2363\t127.0938\t1\r\n졸음쉼터\t오창\t36.73\t127.45\t0\n졸음쉼터\t좌표없음\t\t\t1\n졸음쉼터\t깨짐\tabc\t127.1\t1\n휴게소\t\t37.0\t127.0\t1\n졸음쉼터\t열없음\t36.5\t127.5\n"
        val l = parseRestStops(t)
        assertEquals(listOf("기흥휴게소", "오창", "열없음"), l.map { it.name })
        assertEquals(RestStop("기흥휴게소", LatLng(37.2363, 127.0938), true, "휴게소"), l[0])
        assertEquals(RestStop("오창", LatLng(36.73, 127.45), false, "졸음쉼터"), l[1])
        assertFalse(l[2].toilet) // 화장실 칸이 없으면 없음으로
        assertEquals(emptyList<RestStop>(), parseRestStops(""))
    }

    @Test fun 내장_휴게소_파일은_모두_읽힌다() {
        val f = java.io.File("src/main/assets/reststops.tsv")
        val data = f.readLines().count { it.isNotBlank() && !it.startsWith("#") }
        val l = parseRestStops(f.readText())
        assertEquals(data, l.size)
        assertTrue(l.count { it.kind == "졸음쉼터" } > 200 && l.count { it.kind == "휴게소" } > 50)
        assertTrue(l.all { it.at.lat in 33.0..39.0 && it.at.lng in 124.0..132.0 })
    }

    @Test fun 급해요는_화장실_없는_더_가까운_졸음쉼터를_건너뛴다() {
        val near = RestStop("가까운", LatLng(37.20, 127.0), false, "졸음쉼터"); val far = RestStop("먼휴게소", LatLng(37.10, 127.0))
        val loc = LatLng(37.25, 127.0); val dest = LatLng(36.5, 127.0)
        assertEquals("가까운", nearestRestStop(loc, dest, listOf(near, far))!!.name)                    // S2는 졸음쉼터도
        assertEquals("먼휴게소", nearestRestStop(loc, dest, listOf(near, far), toiletOnly = true)!!.name) // Q3는 화장실 있는 곳만
        assertEquals(null, nearestRestStop(loc, dest, listOf(near), toiletOnly = true))
        assertEquals("가장 가까운 가까운 졸음쉼터를 경유지로 넣었어요", restStopSpeech(near, true))
        assertEquals("가장 가까운 오창졸음쉼터로 길 안내를 시작해요", restStopSpeech(RestStop("오창졸음쉼터", near.at, true, "졸음쉼터"), false)) // 이름에 있으면 중복 X
    }

    @Test fun 짧은_P는_같은_운전() {
        assertTrue(newDrive(null))
        assertFalse(newDrive(30_000))
        assertFalse(newDrive(SHORT_PARK_MS - 1))
        assertTrue(newDrive(SHORT_PARK_MS))
    }

    @Test fun 방_선언은_하루에_방이_바뀔때만() {
        val d = DayRoom()
        assertTrue(d.shouldSay(1, Room.DRIVE_WORK))
        assertFalse(d.shouldSay(1, Room.DRIVE_WORK))   // 같은 날 다시 업무 운전
        assertTrue(d.shouldSay(1, Room.DRIVE_REST))    // 퇴근길로 바뀜
        assertTrue(d.shouldSay(2, Room.DRIVE_REST))    // 다음 날
    }

    // H12: SDK가 누적값(300분)을 줘도 시동 직후엔 0분. 줄면 기준 재설정, 24시간 초과 차이는 버림.
    @Test fun 트립시간은_운전시작_기준_차이만() {
        val t = TripDelta()
        assertEquals(null, t.of(null))
        assertEquals(0, t.of(300))
        assertEquals(125, t.of(425))
        assertFalse(fatigued(Room.DRIVE_WORK, TripDelta().of(300)))
        assertEquals(0, t.of(5))        // 차가 트립을 초기화
        assertEquals(10, t.of(15))
        t.reset(); assertEquals(0, t.of(900))
        assertEquals(null, t.of(900 + 24 * 60 + 1))
    }

    @Test fun 상태는_정직하게() {
        assertEquals("서버 연결 끊김 · 알림이 늦어져요", readiness(true, true, "ai"))
        assertEquals("AI 응대 준비 안 됨(착신 설정 필요)", readiness(false, false, "ai"))
        assertEquals("AI가 대신 받는 중", readiness(false, true, "ai"))
        assertEquals("안내 멘트로 응대 중", readiness(false, true, "ment"))
    }

    @Test fun 저장값_off는_한_마디_안내로_보인다() {
        assertEquals("ment", modeShown("off")); assertEquals("ment", modeShown("ment")); assertEquals("ai", modeShown("ai"))
    }

    @Test fun 휴식_문장은_누르라고_하지_않는다() {
        assertFalse(REST_SPEECH.contains("누르"))
        assertFalse(roomSpeech(Room.DRIVE_REST)!!.contains("퇴근")) // 토요일 운전도 DRIVE_REST
    }

    @Test fun 다음_일정은_지금_이후_가장_이른_것() {
        val e = { t: String, at: Long -> Event(t, at, "곳", LatLng(37.0, 127.0)) }
        val list = listOf(e("셋", 300), e("하나", 100), e("둘", 200))
        assertEquals("둘", nextEvent(list, 150)!!.title)
        assertEquals("하나", nextEvent(list, 0)!!.title)
        assertEquals(null, nextEvent(list, 300)) // 시작한 일정은 제외
        assertEquals(null, nextEvent(emptyList(), 0))
    }

    @Test fun 조작중이면_조용히() {
        assertTrue(busyManeuver(true, Gear.DRIVE, 0f))
        assertTrue(busyManeuver(false, Gear.REVERSE, null))
        assertTrue(busyManeuver(false, Gear.DRIVE, -90f))
        assertFalse(busyManeuver(false, Gear.DRIVE, 89.9f))
        assertFalse(busyManeuver(false, Gear.DRIVE, null))
    }

    @Test fun 피로모드는_주행중_120분부터() {
        assertFalse(fatigued(Room.DRIVE_WORK, 119))
        assertTrue(fatigued(Room.DRIVE_WORK, 120))
        assertTrue(fatigued(Room.DRIVE_REST, 300))
        assertFalse(fatigued(Room.PARK, 300))
        assertFalse(fatigued(Room.DRIVE_WORK, null))
        assertFalse("누르" in fatigueSpeech(Gear.DRIVE)) // H10: 주행 중엔 버튼이 안 보인다
        assertTrue(fatigueSpeech(Gear.DRIVE).endsWith("다음 휴게소에서 쉬어 가세요."))
        assertTrue("휴게소 버튼" in fatigueSpeech(Gear.PARK))
    }

    @Test fun 최근_목적지에서_집_찾기() {
        val h = LatLng(37.1, 127.1)
        assertEquals(h, homeFromRecent(listOf("꽃집" to far, null to far, " 우리집 " to h)))
        assertEquals(h, homeFromRecent(listOf("Home" to h)))
        assertEquals(null, homeFromRecent(listOf("집합 장소" to far)))
    }

    @Test fun 신호_곧_초록불_출발알림() {
        assertEquals(true, greenSoon(5, Gear.DRIVE, 0f, busy = false))
        assertEquals(false, greenSoon(6, Gear.DRIVE, 0f, busy = false)) // 아직 이르다
        assertEquals(false, greenSoon(3, Gear.DRIVE, 5f, busy = false)) // 이미 출발
        assertEquals(false, greenSoon(3, Gear.PARK, 0f, busy = false))
        assertEquals(false, greenSoon(3, Gear.DRIVE, 0f, busy = true)) // 통화·경고·알림 대화 중
        assertEquals(false, greenSoon(-1, Gear.DRIVE, 0f, busy = false)) // 값 없음
    }

    @Test fun 지각_예측은_일정_시작_2분_넘게_늦을때만() {
        val now = 1_000_000L; val start = now + 30 * 60_000L
        assertEquals(null, lateBy(now, 30, start))            // 딱 맞게 도착
        assertEquals(null, lateBy(now, 32, start))            // 정확히 2분 = 유예
        assertEquals(3, lateBy(now, 33, start))
        assertEquals(15, lateBy(now, 45, start))
        assertEquals(null, lateBy(now, null, start))          // 목적지 없음
        assertEquals(null, lateBy(now, 10, start))
    }

    @Test fun 지연_문자는_할일_카드_형식이고_연락처가_번호로_들어간다() {
        val e = Event("거래처 미팅", 0, "곳", LatLng(37.0, 127.0), "010-1234-5678")
        assertEquals("01012345678" to "운전 중이라 약 7분 늦을 것 같습니다. 죄송합니다.", taskDraft(lateTask(e, 7)))
        assertEquals("" to "운전 중이라 약 7분 늦을 것 같습니다. 죄송합니다.", taskDraft(lateTask(e.copy(contact = ""), 7)))
        assertEquals("다음 일정에 약 7분 늦을 것 같아요. 늦는다고 문자 보낼까요?", lateSpeech(7))
    }

    @Test fun 응_아니_대답만_본다() {
        assertEquals(true, yesNo("응")); assertEquals(true, yesNo("보내")); assertEquals(true, yesNo("네, 보내 주세요"))
        assertEquals(false, yesNo("아니")); assertEquals(false, yesNo("괜찮아, 됐어"))
        assertEquals(null, yesNo("글쎄")); assertEquals(null, yesNo(null))
    }

    @Test fun 급해요_화장실은_운전중_휴게소_요청() {
        assertTrue(urgentNeed("급해요")); assertTrue(urgentNeed("나 급해")); assertTrue(urgentNeed("화장실 가고 싶어"))
        assertFalse(urgentNeed("급한 전화")); assertFalse(urgentNeed("답장, 급해서 못 받는다고 해줘"))
        assertFalse(urgentNeed(null)); assertFalse(urgentNeed("응"))
        val demo = listOf(RestStop("죽전휴게소", LatLng(37.3290, 127.1040)), RestStop("기흥휴게소", LatLng(37.2363, 127.0938)))
        val stop = nearestRestStop(LatLng(37.30, 127.10), LatLng(36.5, 127.3), demo)!!  // 죽전은 지나온 곳(목적지에서 더 멀다), 기흥이 앞쪽 가장 가까운 곳
        assertEquals("기흥휴게소", stop.name)
        assertEquals("가장 가까운 기흥휴게소를 경유지로 넣었어요", restStopSpeech(stop, true))
        assertEquals("가장 가까운 기흥휴게소로 길 안내를 시작해요", restStopSpeech(stop, false))
    }

    // S1: 피로 중 [말하기]는 급해·화장실만 통과. 피로가 아니면 전부 통과.
    @Test fun 피로_중_업무_말은_거절_급해요만_통과() {
        for (said in listOf("아까 전화 뭐 왔어", "다음 일정 알려줘", "문자 보내줘", "음", null)) assertEquals(FATIGUE_BLOCK_SPEECH, fatigueGate(carIntent(said), true))
        assertEquals(null, fatigueGate(carIntent("급해요"), true)); assertEquals(null, fatigueGate(carIntent("화장실 어디야"), true))
        for (said in listOf("아까 전화 뭐 왔어", "다음 일정 알려줘", "음")) assertEquals(null, fatigueGate(carIntent(said), false))
        assertTrue(FATIGUE_BLOCK_SPEECH.contains("화장실") && FATIGUE_BLOCK_SPEECH.contains("급해"))
        assertTrue(fatigued(Room.DRIVE_WORK, 120)); assertFalse(fatigued(Room.DRIVE_WORK, 119)); assertFalse(fatigued(Room.PARK, 300))
    }

    @Test fun 휴식_제안_아니라고_하면_30분_쉰다() {
        val th = REST_DEFAULT_MIN; val t = th * 60_000L
        assertTrue(restDue(0, t, th))
        assertFalse(restDue(0, t, th, snoozeUntilMs = t + REST_SNOOZE_MS))
        assertFalse(restDue(0, t + REST_SNOOZE_MS - 1, th, t + REST_SNOOZE_MS))
        assertTrue(restDue(0, t + REST_SNOOZE_MS, th, t + REST_SNOOZE_MS))
        assertFalse(REST_ASK.contains("누르")); assertTrue(REST_ASK.endsWith("?"))
    }

    @Test fun 지각_예측은_목적지가_그_일정_장소_1km_안일때만() {
        val e = Event("미팅", 0, "판교역", LatLng(37.3948, 127.1112))
        assertTrue(headingTo(LatLng(37.3950, 127.1115), e))      // 거의 같은 곳
        assertTrue(headingTo(LatLng(37.4030, 127.1112), e))      // 약 0.9km
        assertFalse(headingTo(LatLng(37.4100, 127.1112), e))     // 약 1.7km: 다른 곳으로 가는 중
        assertFalse(headingTo(null, e))                          // 목적지 모름 = 묻지 않는다
    }

    @Test fun 예약_대행은_식당_주차장류_이름만() {
        for (n in listOf("판교 한정식", "대박 횟집", "스타 카페", "공영주차장", "OO파킹", "맛집 거리", "숯불고기", "이탈리안 레스토랑", "착한식당")) assertTrue(n, reservable(n))
        for (n in listOf("판교역", "한빛물산", "", "우리집")) assertFalse(n, reservable(n))
        assertFalse(reservable(null))
    }

    @Test fun 예약_초안은_할일_카드_형식_도착시각_포함() {
        val now = java.time.Instant.parse("2026-11-02T03:50:00Z").toEpochMilli()
        assertEquals("04:20", arriveAt(now, 30, java.time.ZoneOffset.UTC))
        assertEquals("13:20", arriveAt(now, 30, java.time.ZoneId.of("Asia/Seoul")))
        assertEquals("판교 한정식에 도착 시각 04:20쯤이에요. 예약 문자 초안 만들어 둘까요?", reserveSpeech("판교 한정식", "04:20"))
        val t = reserveTask("판교 한정식", "04:20")
        assertEquals("" to "안녕하세요, 04:20쯤 도착 예정인데 자리(주차) 가능할까요?", taskDraft(t))
        assertTrue(t.startsWith("판교 한정식 예약 / "))
        assertFalse(RESERVE_DONE_SPEECH.contains("누르"))
    }

    @Test fun 예약_가게번호_휴대폰_유선_없음_세갈래() {
        val mob = reserveTask("가명 식당", "04:20", "010-0000-0000")
        assertEquals("01000000000", taskDraft(mob).first); assertEquals("01000000000", sendable(taskDraft(mob).first)); assertEquals(null, shopPhone(mob))
        val land = reserveTask("가명 식당", "04:20", "02-0000-0000")
        assertEquals("02-0000-0000", shopPhone(land)); assertEquals(null, sendable(taskDraft(land).first))
        assertTrue(landlineSpeech("가명 식당").startsWith("가명 식당은 ")); assertTrue(landlineSpeech("가명 카페").startsWith("가명 카페는 ")); assertTrue(landlineSpeech("가명 식당").contains("지금 전화 연결할까요"))
        val none = reserveTask("가명 식당", "04:20", null)
        assertEquals(null, shopPhone(none)); assertEquals("" to "안녕하세요, 04:20쯤 도착 예정인데 자리(주차) 가능할까요?", taskDraft(none)); assertEquals(none, reserveTask("가명 식당", "04:20", ""))
        assertEquals("가명 식당", reserveShop(none)); assertEquals(null, reserveShop("김민수 회신 / 내용: 확인"))
    }

    @Test fun 바로_연결_번호_입력_파싱() {
        assertEquals(listOf("01000001234", "0212345678"), parseDirect("010-0000-1234, 0212345678"))
        assertEquals(listOf("01000001234", "01000005678"), parseDirect("01000001234 01000005678"))
        assertEquals(emptyList<String>(), parseDirect("  "))
        assertEquals(null, parseDirect("01000001234, 123"))           // 자릿수 틀림
        assertEquals(null, parseDirect("01000001234, 김과장"))
        assertEquals(null, parseDirect((1..11).joinToString(",") { "0100000%04d".format(it) })) // 11개
        assertEquals(10, parseDirect((1..10).joinToString(",") { "0100000%04d".format(it) })!!.size)
    }

    @Test fun 음성_발송은_번호를_알때만_초안을_읽고_확인한다() {
        assertEquals("01012345678", sendable("010-1234-5678")); assertEquals(null, sendable("02-1234-5678")); assertEquals(null, sendable("1588-1234")); assertEquals("0101234567", sendable("010-123-4567"))
        assertEquals("번호를 다시 확인해 주세요", sendFailReason("bad_to_number")); assertEquals("잠시 뒤 다시 시도해 주세요", sendFailReason("http_500"))
        assertEquals(null, sendable("")); assertEquals(null, sendable(null)); assertEquals(null, sendable("김부장")); assertEquals(null, sendable("1234"))
        assertEquals("'운전 중이라 약 7분 늦을 것 같습니다. 죄송합니다.' 이대로 보낼까요?", sendConfirmSpeech("운전 중이라 약 7분 늦을 것 같습니다. 죄송합니다."))
        // F4: 서버가 contact_number를 주면 그 번호가 초안 번호가 된다(옛 contact보다 우선). 없으면 지금처럼.
        val e = Event("거래처 미팅", 0, "곳", LatLng(37.0, 127.0), "김과장", "010-9999-8888")
        assertEquals("01099998888" to "운전 중이라 약 7분 늦을 것 같습니다. 죄송합니다.", taskDraft(lateTask(e, 7)))
        assertEquals("" to "운전 중이라 약 7분 늦을 것 같습니다. 죄송합니다.", taskDraft(lateTask(e.copy(contactNumber = ""), 7)))
        assertEquals("01012345678", sendable(taskDraft(lateTask(e.copy(contactNumber = "", contact = "010-1234-5678"), 7)).first))
        assertEquals(null, sendable(taskDraft(lateTask(e.copy(contactNumber = ""), 7)).first)) // 번호 모름 = 카드만
        assertFalse(SEND_KEEP_SPEECH.contains("누르라")); assertTrue(SEND_FAIL_SPEECH.contains("할 일"))
    }

    @Test fun 차_음성_입구_의도는_키워드로만_가른다() {
        for (s in listOf("아까 전화 뭐 왔어", "아까전화 누구였지", "전화 뭐 왔어?", "누구 전화 왔어")) assertEquals(s, CarIntent.RECENT_CALLS, carIntent(s))
        for (s in listOf("급해요", "화장실 가고 싶어", "나 급해")) assertEquals(s, CarIntent.URGENT, carIntent(s))
        for (s in listOf("다음 일정 알려줘", "일정이 뭐야", "오늘 일정")) assertEquals(s, CarIntent.NEXT_EVENT, carIntent(s))
        for (s in listOf("날씨 어때", "", "안녕", "전화 걸어줘")) assertEquals(s, CarIntent.UNKNOWN, carIntent(s))
        assertEquals(CarIntent.UNKNOWN, carIntent(null))
        assertEquals(CarIntent.UNKNOWN, carIntent("답장 급해서 늦는다고 해줘")) // 답장 속 '급해서'는 휴게소로 가지 않는다
        assertEquals(CarIntent.URGENT, carIntent("급해요 일정은 나중에")) // 안전(휴게소)이 먼저
    }

    @Test fun 최근_통화는_최대_3건_이름과_요약_한_문장씩() {
        fun c(id: Long, name: String, sum: String, kind: String = "call") = InboxItem(id, name, sum, false, 0, "", kind = kind)
        val l = listOf(c(1, "오래된", "옛날 일"), c(2, "김부장", "3시 30분까지 연락 주세요. 급한 건 아니에요."), c(3, "", "견적 문의"), c(4, "박대리", ""), c(5, "x", "문자", "notif"))
        assertEquals("최근 전화 3건이에요. 박대리님, 용건을 정리하고 있어요. 모르는 번호, 견적 문의. 김부장님, 3시 30분까지 연락 주세요.", recentCallsSpeech(l))
        assertEquals("최근 전화는 없어요", recentCallsSpeech(emptyList()))
        assertEquals("최근 전화 1건이에요. 김님, 하나.", recentCallsSpeech(listOf(c(1, "김", "하나. 둘"))))
    }

    @Test fun 다음_일정_한_문장() {
        val z = java.time.ZoneOffset.UTC
        val at = { h: Int, m: Int -> java.time.LocalDateTime.of(2026, 11, 2, h, m).toInstant(z).toEpochMilli() }
        assertEquals("다음 일정은 오후 2시 30분 거래처 미팅, 판교역이에요", nextEventSpeech(Event("거래처 미팅", at(14, 30), "판교역", LatLng(0.0, 0.0)), z))
        assertEquals("다음 일정은 오전 12시 점심이에요", nextEventSpeech(Event("점심", at(0, 0), "", LatLng(0.0, 0.0)), z))
        assertEquals("다음 일정은 오후 12시 30분 점심이에요", nextEventSpeech(Event("점심", at(12, 30), "", LatLng(0.0, 0.0)), z))
        assertEquals("다음 일정이 없어요", nextEventSpeech(null))
    }

    @Test fun 알림_제목은_사람이_읽는_말() {
        assertEquals("내비서 · 업무 운전 중", notifTitle("DRIVE_WORK"))
        assertEquals("내비서 · 업무 시간 밖 운전", notifTitle("DRIVE_REST"))
        assertEquals("내비서 · 정차 중", notifTitle("PARK"))
        assertEquals("내비서 · 실행 중", notifTitle("(판정 전)"))
        for (r in Room.values()) assertFalse(notifTitle(r.name).contains(r.name)) // 영문 방 이름이 새지 않는다
    }

    @Test fun 첫마디는_서버_대본과_같은_문장() {
        assertEquals("안녕하세요, 김민수 과장님의 AI 비서 하나입니다. 김민수 과장님이 지금 운전 중이셔서 제가 대신 받았어요. 몇 가지만 여쭤보고 내용을 기록해 정리해서 전해 드릴게요. 어느 분이세요?",
            firstWords("김민수 과장", "하나", "ai"))
        assertTrue(firstWords("", "", "ai").startsWith("안녕하세요, 이 번호로 대신 전화 받는 AI 비서입니다. 지금 운전 중이셔서"))
        assertEquals("김민수님이 지금 운전 중이라 전화를 받을 수 없습니다. 정차하시면 연락드리겠습니다.", firstWords("김민수", "", "off"))
        assertEquals("회의 중입니다.", firstWords("김민수", "", "ment", " 회의 중입니다. "))
    }

    @Test fun 재질문은_질문당_한_번만() {
        val r = Reask()
        assertTrue(r.again())   // 처음 못 알아들음 -> 다시 묻기
        assertFalse(r.again())  // 두 번째 -> 화면 2택
        assertFalse(r.again())
        r.reset()               // 새 질문
        assertTrue(r.again())
    }

    @Test fun 꺼진_권한만_모은다() {
        assertEquals(emptyList<String>(), missingPermissions(34) { true })
        assertEquals(RUNTIME_PERMS, missingPermissions(34) { false })
        assertEquals(listOf("android.car.permission.CAR_SPEED", "android.permission.RECORD_AUDIO", "android.permission.CALL_PHONE"), missingPermissions(32) { false }) // 알림 권한은 33부터
        assertEquals(listOf(PERM_NOTIF), missingPermissions(34) { it != PERM_NOTIF })
        assertEquals("마이크·알림·전화", missingPermissions(34) { it == "android.car.permission.CAR_SPEED" }.joinToString("·") { permName(it) })
    }
}
