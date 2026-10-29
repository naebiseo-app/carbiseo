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

const val REST_DEFAULT_MIN = 120
const val REST_SPEECH = "오래 운전하셨어요. 쉬어 가실래요? 화면의 휴게소 버튼을 누르세요."

/** S2: 이번 운전(PARK에서 나온 뒤) 연속 주행이 설정 분 이상. startMs = null이면 정차 중. */
fun restDue(startMs: Long?, nowMs: Long, thresholdMin: Int): Boolean = startMs != null && nowMs - startMs >= thresholdMin * 60_000L

data class RestStop(val name: String, val at: LatLng)

// NaviHelper 3.0.5에 장소 검색 API가 없어(javap: 경로·경유지·북마크·최근 목적지뿐) 데모 좌표를 앱에 둔다.
// ponytail: 경부선 휴게소 몇 곳, 근사 좌표. 실서비스는 공공데이터 휴게소·졸음쉼터 목록으로 교체.
val REST_STOPS = listOf(
    RestStop("서울만남의광장휴게소", LatLng(37.4598, 127.0416)),
    RestStop("죽전휴게소", LatLng(37.3290, 127.1040)),
    RestStop("기흥휴게소", LatLng(37.2363, 127.0938)),
    RestStop("안성휴게소", LatLng(37.0098, 127.2091)),
)

/** 경로상 앞쪽 = 지금보다 목적지에 가까운 곳. 그중 지금 위치에서 가장 가까운 것, 없으면 전체에서 가장 가까운 것. */
fun nearestRestStop(loc: LatLng?, dest: LatLng?, stops: List<RestStop> = REST_STOPS): RestStop? {
    loc ?: return null
    val ahead = if (dest == null) stops else stops.filter { distanceM(it.at, dest) < distanceM(loc, dest) }
    return ahead.ifEmpty { stops }.minByOrNull { distanceM(loc, it.at) }
}

/** F6 데모 일정 1건(서버 /car/schedule). start = epoch ms. */
data class Event(val title: String, val start: Long, val place: String, val at: LatLng)

/** F7: 아직 시작 안 한 일정 중 가장 이른 것. */
fun nextEvent(events: List<Event>, nowMs: Long): Event? = events.filter { it.start > nowMs }.minByOrNull { it.start }
