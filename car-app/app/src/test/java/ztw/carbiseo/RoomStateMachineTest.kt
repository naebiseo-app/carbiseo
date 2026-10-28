package ztw.carbiseo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class FakeClock(var ms: Long) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(z: ZoneId?): Clock = this
    override fun instant(): Instant = Instant.ofEpochMilli(ms)
    override fun millis(): Long = ms
}

class RoomStateMachineTest {
    private val noon = LocalTime.of(12, 0).toSecondOfDay() * 1000L
    private val clock = FakeClock(noon)
    private val events = mutableListOf<Pair<Room?, Room>>()
    private val m = RoomStateMachine(clock).also { it.onChange = { a, b -> events += a to b } }
    private val home = m.settings.home
    private val far = LatLng(37.60, 127.10)               // 집에서 10km+
    private fun north(meters: Double) = LatLng(home.lat + meters / 111195.0, home.lng)

    private fun drive() = m.update { copy(gear = Gear.DRIVE, distractionOptimized = true) }
    private fun settle() { clock.ms += 400; m.tick() }     // 디바운스 창 통과

    @Test fun 정차_P이고_DO없으면_PARK() {
        m.update { copy(gear = Gear.PARK, distractionOptimized = false) }
        assertEquals(Room.PARK, m.room)
    }

    @Test fun 기어_없고_DO도_없으면_PARK() {
        m.update { copy() }
        assertEquals(Room.PARK, m.room)
    }

    @Test fun 주행_목적지_타지역_업무시간이면_WORK() {
        m.update { copy(destination = far) }; drive()
        assertEquals(Room.DRIVE_WORK, m.room)
    }

    @Test fun 주행_목적지_집이면_REST() {
        m.update { copy(destination = home) }; drive()
        assertEquals(Room.DRIVE_REST, m.room)
    }

    @Test fun 목적지_집_반경_경계() {
        m.update { copy(destination = north(290.0)) }; drive()
        assertEquals(Room.DRIVE_REST, m.room)
        m.update { copy(destination = north(310.0)) }; settle()
        assertEquals(Room.DRIVE_WORK, m.room)
    }

    @Test fun 업무종료_이후면_목적지_달라도_REST() {
        clock.ms = LocalTime.of(18, 30).toSecondOfDay() * 1000L
        m.update { copy(destination = far) }; drive()
        assertEquals(Room.DRIVE_REST, m.room)
    }

    @Test fun 업무종료_시각_경계_1799는_WORK_1800은_REST() {
        clock.ms = LocalTime.of(17, 59, 59).toSecondOfDay() * 1000L + 999
        m.update { copy(destination = far) }; drive()
        assertEquals(Room.DRIVE_WORK, m.room)
        clock.ms += 1; m.tick()                                   // 18:00:00.000
        clock.ms += 400; m.tick()
        assertEquals(Room.DRIVE_REST, m.room)
    }

    @Test fun 목적지없음_집2km_이내면_REST() {
        m.update { copy(location = north(1990.0)) }; drive()
        assertEquals(Room.DRIVE_REST, m.room)
    }

    @Test fun 목적지없음_집2km_밖이면_WORK() {
        m.update { copy(location = north(2010.0)) }; drive()
        assertEquals(Room.DRIVE_WORK, m.room)
    }

    @Test fun 목적지없음_위치모르면_WORK() {
        drive()
        assertEquals(Room.DRIVE_WORK, m.room)
    }

    @Test fun 기어P여도_DO면_주행() {
        m.update { copy(gear = Gear.PARK, distractionOptimized = true, destination = far) }
        assertEquals(Room.DRIVE_WORK, m.room)
    }

    @Test fun DO없어도_기어가_D면_주행() {
        m.update { copy(gear = Gear.DRIVE, distractionOptimized = false, destination = far) }
        assertEquals(Room.DRIVE_WORK, m.room)
    }

    @Test fun 충전중이면_주행기어여도_PARK() {
        m.update { copy(charging = true) }; drive()
        assertEquals(Room.PARK, m.room)
    }

    @Test fun 같은_방_반복은_이벤트_1회() {
        m.update { copy(gear = Gear.PARK) }
        m.update { copy(gear = Gear.PARK) }; m.tick()
        m.update { copy(distractionOptimized = false) }
        assertEquals(listOf<Pair<Room?, Room>>(null to Room.PARK), events)
    }

    @Test fun 디바운스_300ms_안_되돌림은_무시하고_뒤에_반영() {
        m.update { copy(gear = Gear.PARK) }; settle()
        drive()                                                    // PARK -> WORK
        clock.ms += 100
        m.update { copy(gear = Gear.PARK, distractionOptimized = false) }
        assertEquals(Room.DRIVE_WORK, m.room)                      // 되돌림 무시
        clock.ms += 250; m.tick()                                  // 전환 후 350ms
        assertEquals(Room.PARK, m.room)                            // 신호가 계속 P면 결국 반영
        assertEquals(3, events.size)
    }

    @Test fun 디바운스_깜빡임은_이벤트_안_만든다() {
        m.update { copy(gear = Gear.PARK) }; settle()
        drive()
        clock.ms += 100; m.update { copy(gear = Gear.PARK, distractionOptimized = false) }
        clock.ms += 50; drive()                                    // 다시 D: 최종 상태 = 현재 방
        clock.ms += 500; m.tick()
        assertEquals(2, events.size)                               // null->PARK, PARK->WORK 뿐
    }

    @Test fun 디바운스_300ms_지나면_즉시_전환() {
        drive(); settle()
        m.update { copy(gear = Gear.PARK, distractionOptimized = false) }
        assertEquals(Room.PARK, m.room)
    }

    @Test fun 목적지_해제는_2초_유예_후_반영() {
        m.update { copy(destination = home) }; drive()
        assertEquals(Room.DRIVE_REST, m.room)
        settle()
        m.update { copy(destination = null) }
        clock.ms += 1900; m.tick()
        assertEquals(Room.DRIVE_REST, m.room)                      // 1.9초: 아직 목적지 유지
        clock.ms += 100; m.tick()
        assertEquals(Room.DRIVE_WORK, m.room)                      // 2.0초: 해제 반영
    }

    @Test fun 경유지_추가_취소_잠깐후_복귀면_방_안_바뀜() {
        m.update { copy(destination = home) }; drive(); settle()
        val before = events.size
        m.update { copy(destination = null) }                      // onRouteCancelled + FREE_DRIVE
        clock.ms += 600; m.tick()
        m.update { copy(destination = home) }                      // ROUTE_DRIVE 복귀
        clock.ms += 3000; m.tick()
        assertEquals(before, events.size)
        assertEquals(Room.DRIVE_REST, m.room)
    }

    @Test fun 정차중_해제된_목적지는_운전_시작때_유령으로_안_남는다() {
        m.update { copy(destination = home, gear = Gear.PARK) }
        m.update { copy(destination = null) }
        clock.ms += 3000; m.tick()
        drive()
        assertEquals(Room.DRIVE_WORK, m.room)
    }

    @Test fun 첫_이벤트의_from은_null() {
        m.update { copy(gear = Gear.PARK) }
        assertNull(events.first().first)
    }
}
