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
    private fun at(h: Int, min: Int = 0, day: Int = 0) { clock.ms = day * 86_400_000L + LocalTime.of(h, min).toSecondOfDay() * 1000L } // day 0 = 1970-01-01 목요일
    private fun early() = at(6)                            // 이른 아침: 목적지가 집이면 쉼, 아니면 업무

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

    @Test fun 저녁_목적지_집이면_REST() {
        at(18, 30); m.update { copy(destination = home) }; drive()
        assertEquals(Room.DRIVE_REST, m.room)
    }

    // M16: 업무 시간 중 집 목적지(점심·서류 가지러)는 업무. 거래처가 정오에 '오늘 업무 마치셨습니다'를 듣지 않게.
    // C①: 내비 즐겨찾기 '회사'가 목적지면 저녁·주말이어도 업무(야근·주말 출근)
    @Test fun 저녁_주말_목적지_회사면_WORK() {
        m.settings = m.settings.copy(office = far)
        at(20, day = 2); m.update { copy(destination = far) }; drive() // 토요일 20시
        assertEquals(Room.DRIVE_WORK, m.room)
    }

    @Test fun 업무시간_목적지_집이어도_WORK() {
        m.update { copy(destination = home) }; drive()
        assertEquals(Room.DRIVE_WORK, m.room)
    }

    @Test fun 목적지_집_반경_경계() {
        early()
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

    // H3: 이른 출근(06:50, 목적지 회사)은 업무. 04시 전 새벽은 전날 저녁(쉼).
    @Test fun 이른_출근은_WORK_새벽4시_전은_REST() {
        at(6, 50); m.update { copy(destination = far) }; drive()
        assertEquals(Room.DRIVE_WORK, m.room)
        at(3, 59); m.tick(); settle()
        assertEquals(Room.DRIVE_REST, m.room)
        at(4, 0); m.tick(); settle()
        assertEquals(Room.DRIVE_WORK, m.room)
    }

    // H4: 토·일은 업무 아님(쉼). day 2 = 토, 3 = 일, 4 = 월
    @Test fun 주말은_REST_월요일은_WORK() {
        at(10, 0, day = 2); m.update { copy(destination = far) }; drive()
        assertEquals(Room.DRIVE_REST, m.room)
        at(10, 0, day = 3); m.tick(); settle()
        assertEquals(Room.DRIVE_REST, m.room)
        at(10, 0, day = 4); m.tick(); settle()
        assertEquals(Room.DRIVE_WORK, m.room)
    }

    @Test fun 업무시간밖_판정() {
        val s = RoomSettings()
        val d = { day: Int, h: Int -> java.time.LocalDateTime.of(2026, 11, day, h, 0) } // 2026-11-02 월
        assertEquals(false, offDuty(d(2, 9), s))
        assertEquals(true, offDuty(d(2, 18), s))
        assertEquals(true, offDuty(d(2, 6), s))
        assertEquals(true, offDuty(d(7, 10), s))   // 토
        assertEquals(true, offDuty(d(8, 10), s))   // 일
    }

    // H3: 아침 집 앞 출발(목적지 없음)은 업무. 예전엔 집 2km 안이면 시각과 무관하게 '퇴근길'이었다.
    @Test fun 아침_집앞_출발_목적지없음은_WORK() {
        at(8, 30); m.update { copy(location = north(300.0)) }; drive()
        assertEquals(Room.DRIVE_WORK, m.room)
    }

    @Test fun 저녁_목적지없음은_REST() {
        at(18, 30); m.update { copy(location = far) }; drive()
        assertEquals(Room.DRIVE_REST, m.room)
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

    @Test fun 주행에서_PARK는_2초_유지돼야_반영() {
        m.update { copy(gear = Gear.PARK) }; settle()
        drive()                                                    // PARK -> WORK
        clock.ms += 100
        m.update { copy(gear = Gear.PARK, distractionOptimized = false) }
        assertEquals(Room.DRIVE_WORK, m.room)                      // 되돌림 무시
        clock.ms += 1900; m.tick()                                 // P 1.9초
        assertEquals(Room.DRIVE_WORK, m.room)
        clock.ms += 100; m.tick()                                  // P 2초
        assertEquals(Room.PARK, m.room)                            // 신호가 계속 P면 결국 반영
        assertEquals(3, events.size)
    }

    @Test fun P_D_빠른_반복은_방_안_바뀜() {
        drive(); settle()
        repeat(5) {
            clock.ms += 800; m.update { copy(gear = Gear.PARK, distractionOptimized = false) }
            clock.ms += 800; drive()
        }
        assertEquals(1, events.size)                               // null->WORK 뿐
    }

    @Test fun 디바운스_깜빡임은_이벤트_안_만든다() {
        m.update { copy(gear = Gear.PARK) }; settle()
        drive()
        clock.ms += 100; m.update { copy(gear = Gear.PARK, distractionOptimized = false) }
        clock.ms += 50; drive()                                    // 다시 D: 최종 상태 = 현재 방
        clock.ms += 500; m.tick()
        assertEquals(2, events.size)                               // null->PARK, PARK->WORK 뿐
    }

    @Test fun PARK에서_주행은_디바운스_뒤_즉시() {
        m.update { copy(gear = Gear.PARK) }; settle()
        drive()
        assertEquals(Room.DRIVE_WORK, m.room)                      // PARK -> 주행은 바로
    }

    @Test fun 목적지_해제는_유예_후_반영() {
        early()
        m.update { copy(destination = home) }; drive()
        assertEquals(Room.DRIVE_REST, m.room)
        settle()
        m.update { copy(destination = null) }
        clock.ms += m.settings.destGraceMs - 100; m.tick()
        assertEquals(Room.DRIVE_REST, m.room)                      // 유예 직전: 아직 목적지 유지
        clock.ms += 100; m.tick()
        assertEquals(Room.DRIVE_WORK, m.room)                      // 유예 도달: 해제 반영
    }

    @Test fun 경유지_추가_취소_잠깐후_복귀면_방_안_바뀜() {
        early()
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
        early()
        m.update { copy(destination = home, gear = Gear.PARK) }
        m.update { copy(destination = null) }
        clock.ms += m.settings.destGraceMs + 1000; m.tick()
        drive()
        assertEquals(Room.DRIVE_WORK, m.room)
    }

    @Test fun 유예_시간은_설정으로_늘릴_수_있다() {
        early()
        m.settings = m.settings.copy(destGraceMs = 10_000)
        m.update { copy(destination = home) }; drive(); settle()
        m.update { copy(destination = null) }
        clock.ms += 7300; m.tick()                                 // 에뮬 실측: 경유지 추가 후 ROUTE_DRIVE 복귀까지 약 7초
        assertEquals(Room.DRIVE_REST, m.room)
    }

    @Test fun 첫_이벤트의_from은_null() {
        m.update { copy(gear = Gear.PARK) }
        assertNull(events.first().first)
    }

    @Test fun 시각이_되돌아가도_판정이_멈추지_않음() {
        var back = 0L; m.onClockBack = { back += it }
        m.update { copy(gear = Gear.PARK) }; drive(); settle()
        assertEquals(Room.DRIVE_WORK, m.room)
        m.update { copy(gear = Gear.PARK, distractionOptimized = false) } // 주차 유지 판정 중에
        clock.ms -= 3_600_000; m.tick()                                     // 한 시간 되돌림
        assertEquals(3_600_000L, back)
        clock.ms += 2500; m.tick()
        assertEquals(Room.PARK, m.room)                                     // parkHold가 음수로 막히지 않는다
        drive(); clock.ms += 100; m.update { copy(gear = Gear.PARK, distractionOptimized = false) }; clock.ms += 2500; m.tick()
        assertEquals(Room.PARK, m.room)
    }
}
