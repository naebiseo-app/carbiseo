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
        assertEquals(null, nearestRestStop(LatLng(36.35, 127.38), LatLng(35.18, 129.08)))        // 대전 → 부산: 데모 4곳 모두 뒤
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
    }
}
