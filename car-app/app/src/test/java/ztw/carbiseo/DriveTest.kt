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
}
