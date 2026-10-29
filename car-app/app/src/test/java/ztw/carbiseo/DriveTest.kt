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
        assertEquals("B", nearestRestStop(LatLng(37.21, 127.0), null, stops)!!.name) // 목적지 없음: 그냥 최근접
        assertEquals("C", nearestRestStop(LatLng(36.9, 127.0), LatLng(36.8, 127.0), stops)!!.name) // 앞쪽에 없으면 전체 최근접
        assertEquals(null, nearestRestStop(null, null, stops))
    }
}
