package ztw.carbiseo

import java.time.Clock
import java.time.LocalTime
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class Room { DRIVE_WORK, DRIVE_REST, PARK }
enum class Gear { PARK, DRIVE, REVERSE, NEUTRAL, UNKNOWN }
data class LatLng(val lat: Double, val lng: Double)

/** 나중에 설정 화면이 통째로 덮어쓴다(machine.settings = ...). */
data class RoomSettings(
    val home: LatLng = LatLng(37.4979, 127.0276),
    val workStart: LocalTime = LocalTime.of(7, 0),
    val workEnd: LocalTime = LocalTime.of(18, 0),
    val homeDestRadiusM: Double = 300.0,   // 목적지가 "집"으로 인정되는 반경
    val homeNearRadiusM: Double = 2000.0,  // 목적지 없을 때 현재 위치가 "집 근처"인 반경
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
)

fun distanceM(a: LatLng, b: LatLng): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}

/**
 * 순수 Kotlin 방 판정기. 스레드 안전하지 않음 -> 호출은 한 스레드(메인)에서만.
 * 시간 경과(유예·디바운스)는 tick()이 다시 판정해서 반영한다(주기 호출은 호출자 몫).
 */
class RoomStateMachine(
    private val clock: Clock,
    var settings: RoomSettings = RoomSettings(),
    var onChange: (from: Room?, to: Room) -> Unit = { _, _ -> },
) {
    var room: Room? = null
        private set
    var signals = Signals()
        private set

    private var lastChangeMs = Long.MIN_VALUE / 2
    private var prevRoom: Room? = null
    private var lastDest: LatLng? = null
    private var destLostAtMs: Long? = null
    private var parkSinceMs: Long? = null

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
        val t = clock.instant().atZone(clock.zone).toLocalTime()
        val afterWork = t.isBefore(settings.workStart) || !t.isBefore(settings.workEnd) // 업무 시간 밖(새벽 포함)
        val rest = when {
            dest != null -> distanceM(dest, settings.home) <= settings.homeDestRadiusM || afterWork
            else -> afterWork || (s.location?.let { distanceM(it, settings.home) <= settings.homeNearRadiusM } ?: false)
        }
        return if (rest) Room.DRIVE_REST else Room.DRIVE_WORK
    }

    private fun evaluate() {
        val now = clock.millis()
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
