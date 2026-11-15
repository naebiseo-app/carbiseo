package ztw.carbiseo

import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// 방 판정기: 기어·주행 제한·목적지·시각 신호로 방(PARK / DRIVE_WORK / DRIVE_REST)을 정한다. Android·SDK를 모르는 순수 Kotlin이라
// RoomStateMachineTest가 시계를 바꿔 가며 검증한다. 신호를 모아 넘기는 곳은 CarAdapters.kt, 방이 바뀐 뒤의 일은 RoomService.kt다.
// 먼저 읽을 곳: target(지금 어느 방이어야 하나) → evaluate(디바운스·유예를 걸어 실제로 바꿀지). 업무 시간 판정(offDuty)도 이 파일이다.

enum class Room { DRIVE_WORK, DRIVE_REST, PARK }
enum class Gear { PARK, DRIVE, REVERSE, NEUTRAL, UNKNOWN }
data class LatLng(val lat: Double, val lng: Double)

/** 설정 화면에서 업무 끝 시각·집 위치가 바뀌면 RoomService가 copy로 통째로 덮어쓴다(machine.settings = ...). */
data class RoomSettings(
    val home: LatLng = LatLng(37.4979, 127.0276),
    val office: LatLng? = null,             // 내비 즐겨찾기 '회사'. 목적지가 여기면 시간·요일과 무관하게 업무(야근·주말 출근)
    val workStart: LocalTime = LocalTime.of(7, 0),
    val workEnd: LocalTime = LocalTime.of(18, 0),
    val homeDestRadiusM: Double = 300.0,   // 목적지가 "집"으로 인정되는 반경
    val destGraceMs: Long = 10_000,         // 목적지 해제 유예
    val debounceMs: Long = 300,            // 전환 직후 되돌림 무시 시간
    val parkHoldMs: Long = 2000,           // 주행 -> PARK는 PARK 판정이 이만큼 이어져야(P↔D 빠른 반복 흡수)
)

data class Signals(
    val gear: Gear = Gear.UNKNOWN,
    val distractionOptimized: Boolean = false,
    val charging: Boolean = false,
    val destination: LatLng? = null,
    val location: LatLng? = null,
    val rain: Boolean = false,       // 와이퍼 작동(CAR_WINDOWS). 방 판정에는 안 쓰고 지각 음성·문자의 "비가 와서"에 쓴다
)

fun distanceM(a: LatLng, b: LatLng): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}

/** 설정 화면에서 고르는 업무 끝 시각(시). 기본 18. 그 밖의 값은 18로 본다. */
val WORK_END_CHOICES = listOf(17, 18, 19, 20)
fun workEndTime(hour: Int): LocalTime = LocalTime.of(if (hour in WORK_END_CHOICES) hour else 18, 0)

/** 새벽 이 시각 전까지는 '전날 저녁'으로 본다(밤샘 귀가). */
val NIGHT_END: LocalTime = LocalTime.of(4, 0)

/** 평일에 걸린 공휴일(대체공휴일 포함). 한계: 2026-12-25~2027년 말만 담았고 임시공휴일은 없다 (넘으려면 해마다 월력요항을 보고 추가한다). */
val KR_HOLIDAYS: Set<LocalDate> = listOf("2026-12-25", "2027-01-01", "2027-02-08", "2027-02-09", "2027-03-01", "2027-05-03", "2027-05-05", "2027-05-13",
    "2027-07-19", "2027-08-16", "2027-09-14", "2027-09-15", "2027-09-16", "2027-10-04", "2027-10-11", "2027-12-27").map(LocalDate::parse).toSet()
fun dayOff(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY || d in KR_HOLIDAYS

/** 업무 시간 밖: 토·일·공휴일 또는 workStart 전·workEnd 이후. 시각만 본다 — 방 판정(target)은 목적지까지 함께 본다. */
fun offDuty(at: LocalDateTime, s: RoomSettings): Boolean =
    dayOff(at.toLocalDate()) || at.toLocalTime() < s.workStart || at.toLocalTime() >= s.workEnd

/**
 * 순수 Kotlin 방 판정기. 스레드 안전하지 않음 -> 호출은 한 스레드(메인)에서만.
 * 시간 경과(유예·디바운스)는 tick()이 다시 판정해서 반영한다(주기 호출은 호출자 몫).
 */
class RoomStateMachine(
    private val clock: Clock,
    var settings: RoomSettings = RoomSettings(),
    var onChange: (from: Room?, to: Room) -> Unit = { _, _ -> },
    /** 시각이 되돌아갔을 때 그만큼(ms). 호출자가 자기 기준 시각(운전 시작·정차 시작)도 같이 당긴다. */
    var onClockBack: (backMs: Long) -> Unit = {},
) {
    var room: Room? = null
        private set
    var signals = Signals()
        private set

    private var lastChangeMs = Long.MIN_VALUE / 2 // 처음엔 디바운스에 안 걸리게 먼 과거(MIN_VALUE 그대로면 now - 값이 넘친다)
    private var prevRoom: Room? = null
    private var lastDest: LatLng? = null
    private var destLostAtMs: Long? = null
    private var parkSinceMs: Long? = null
    private var lastNowMs: Long? = null

    fun update(f: Signals.() -> Signals) { signals = signals.f(); evaluate() }
    fun tick() = evaluate()

    /** 유예를 반영한 목적지. 해제 후 destGraceMs 동안은 직전 목적지를 유지한다. */
    private fun effectiveDestination(now: Long): LatLng? {
        val raw = signals.destination
        if (raw != null) { lastDest = raw; destLostAtMs = null; return raw }
        val prev = lastDest ?: return null
        val lostAt = destLostAtMs ?: now.also { destLostAtMs = it }
        if (now - lostAt >= settings.destGraceMs) { lastDest = null; destLostAtMs = null; return null }
        return prev
    }

    private fun target(now: Long): Room {
        val s = signals
        val dest = effectiveDestination(now) // 정차 중에도 호출해야 유예 시계가 돈다
        val driving = (s.gear != Gear.PARK && s.gear != Gear.UNKNOWN) || s.distractionOptimized
        if (s.charging || !driving) return Room.PARK
        val at = clock.instant().atZone(clock.zone).toLocalDateTime()
        val t = at.toLocalTime()
        // 쉼 = 주말·공휴일 | 저녁~새벽(workEnd~04시) | 이른 아침(04시~workStart)인데 목적지가 집. 단 목적지가 회사면 늘 업무.
        // 이른 아침 집 근처 출발·업무 시간 중 집 목적지(점심 등)는 업무(H3·M16): 거래처에 '업무 마치셨습니다'를 말하지 않게.
        val weekend = dayOff(at.toLocalDate())
        val evening = t >= settings.workEnd || t < NIGHT_END
        val toHome = dest != null && distanceM(dest, settings.home) <= settings.homeDestRadiusM
        val toOffice = dest != null && settings.office?.let { distanceM(dest, it) <= settings.homeDestRadiusM } == true
        val rest = !toOffice && (weekend || evening || (t < settings.workStart && toHome))
        return if (rest) Room.DRIVE_REST else Room.DRIVE_WORK
    }

    private fun evaluate() {
        val now = clock.millis()
        // 시각이 되돌아가면(디버그 TIME·실제 시계 조정) 기준 시각도 같은 만큼 당긴다. 안 그러면 경과가 음수라 디바운스·유예가 안 풀려 판정이 멈춘다.
        val back = (lastNowMs ?: now) - now; lastNowMs = now
        if (back > 0) { lastChangeMs -= back; parkSinceMs = parkSinceMs?.minus(back); destLostAtMs = destLostAtMs?.minus(back); onClockBack(back) }
        val t = target(now)
        val cur = room
        parkSinceMs = if (t == Room.PARK) parkSinceMs ?: now else null
        if (t == cur) return
        if (cur != null && t == Room.PARK && now - parkSinceMs!! < settings.parkHoldMs) return // tick이 나중에 재판정
        if (cur != null && t == prevRoom && now - lastChangeMs < settings.debounceMs) return // 되돌림 무시(tick이 나중에 재판정)
        prevRoom = cur; room = t; lastChangeMs = now
        onChange(cur, t)
    }
}
