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

/**
 * C⑤ 조수석 착석: 서비스 시작 때 처음 온 값을 기준선(빈 좌석)으로 잡고, 기준선과 달라지면 "탔다"로 본다.
 * 그래서 값이 반전돼 오는 에뮬(빈 좌석 = true)에서도 시작 즉시 띠가 뜨지 않고, 콘솔로 바꾸는 순간에만 뜬다.
 * ponytail: 시동 직후 이미 앉아 있던 동승자는 못 잡는다(그 사람이 내리면 띠가 한 번 잘못 뜸). 실차 값 방향이 확인되면 기준선 대신 그 값을 쓴다.
 */
class SeatEdge {
    private var base: Boolean? = null
    private var last: Boolean? = null
    val occupied get() = base != null && last != base
    /** 값이 바뀌었으면 지금 occupied, 아니면(첫 값 포함) null. */
    fun update(s: Boolean?): Boolean? {
        if (s == null || s == last) return null
        last = s
        if (base == null) { base = s; return null }
        return occupied
    }
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

/** 방이 바뀔 때 한 문장. PARK는 무음. */
fun roomSpeech(r: Room): String? = when (r) {
    Room.DRIVE_WORK -> "업무 운전 모드예요"
    Room.DRIVE_REST -> "퇴근길이에요, 급한 전화만 알려 드릴게요"
    Room.PARK -> null
}

/** C④ 조작 중(깜빡이·후진·큰 조향)엔 말 걸지 않는다. 끝나면 보류분을 읽는다. */
const val BIG_STEER_DEG = 90f
fun busyManeuver(turn: Boolean, gear: Gear, steerDeg: Float?): Boolean =
    turn || gear == Gear.REVERSE || (steerDeg != null && kotlin.math.abs(steerDeg) >= BIG_STEER_DEG)

/** C② 피로 모드: 트립 주행시간이 기준 이상이면 전화 알림은 전부 보관(음성 없음). */
const val FATIGUE_MIN = 120
const val FATIGUE_SPEECH = "두 시간째 운전 중이에요. 전화는 제가 다 받아 둘게요"
fun fatigued(room: Room?, tripMin: Int?): Boolean = room != null && room != Room.PARK && (tripMin ?: 0) >= FATIGUE_MIN

const val TELLTALE_SPEECH = "계기판에 빨간 경고등이 켜졌어요. 안전한 곳에 세운 뒤 확인해 주세요"

/** 서버 car_context용 기어 한 글자. */
fun gearCode(g: Gear): String? = when (g) { Gear.PARK -> "P"; Gear.DRIVE -> "D"; Gear.REVERSE -> "R"; Gear.NEUTRAL -> "N"; Gear.UNKNOWN -> null }
