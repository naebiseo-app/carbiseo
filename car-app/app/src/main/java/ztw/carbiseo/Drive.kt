package ztw.carbiseo

const val HOME_SOON_MIN = 5
const val HOME_SOON_SPEECH = "오늘 일은 여기까지예요. 곧 도착합니다"

/** H1 집 문턱: 쉼 방 + 목적지가 집 + 남은 분 ≤ 5. */
fun homeSoon(room: Room?, dest: LatLng?, s: RoomSettings, etaMin: Int?): Boolean =
    room == Room.DRIVE_REST && dest != null && distanceM(dest, s.home) <= s.homeDestRadiusM && etaMin != null && etaMin <= HOME_SOON_MIN

/** 운전 1회당 한 번만 true. PARK가 되면 reset. */
class Once {
    private var fired = false
    fun fire(cond: Boolean): Boolean = (cond && !fired).also { if (it) fired = true }
    fun reset() { fired = false }
}
