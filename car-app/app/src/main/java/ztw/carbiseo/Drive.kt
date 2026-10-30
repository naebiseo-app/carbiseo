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
// H10: 음성으로 '누르라'고 지시하지 않는다. 버튼은 화면에 그대로 둔다.
const val REST_SPEECH = "오래 운전하셨어요. 쉬어 가실래요? 전화는 제가 받아 둘게요."

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

const val REST_STOP_MAX_M = 40_000.0

/**
 * H11: 앞쪽(지금보다 목적지에 가까운 곳)이고 40km 안인 휴게소 중 가장 가까운 것. 지나온 곳은 절대 고르지 않는다(유턴 경로 방지).
 * 목적지가 없으면 진행 방향을 몰라 null. null이면 호출자가 내비 장소 검색 화면을 연다.
 */
fun nearestRestStop(loc: LatLng?, dest: LatLng?, stops: List<RestStop> = REST_STOPS): RestStop? {
    if (loc == null || dest == null) return null
    return stops.filter { distanceM(it.at, dest) < distanceM(loc, dest) && distanceM(loc, it.at) <= REST_STOP_MAX_M }.minByOrNull { distanceM(loc, it.at) }
}

/** M4: 이보다 짧은 P(신호대기·드라이브스루)는 같은 운전으로 본다 — 방 선언·휴식/피로 타이머를 리셋하지 않는다. */
const val SHORT_PARK_MS = 2 * 60_000L
/** H9: 이만큼 넘게 세워 두면 다음 출발은 '다른 운전' — 그 전에 켠 동승자 모드는 자동 해제. */
const val PASSENGER_RESET_PARK_MS = 30 * 60_000L

/** 직전 P 길이(ms, null = 서비스 시작 후 첫 운전)로 새 운전인지. */
fun newDrive(parkedMs: Long?): Boolean = parkedMs == null || parkedMs >= SHORT_PARK_MS

/** M4: 방 선언은 하루에 방 성격이 바뀔 때만(같은 날 같은 방이면 다시 말하지 않는다). */
class DayRoom {
    private var last: Pair<Long, Room>? = null
    fun shouldSay(day: Long, r: Room): Boolean = (day to r != last).also { if (it) last = day to r }
}

/**
 * H12: SDK 트립 주행시간은 운전 시작 때 값을 기준으로 차이만 쓴다. 누적값이 와도 시동 직후 '두 시간째'가 나오지 않는다.
 * 값이 줄면(차가 트립을 초기화) 기준을 다시 잡고, 24시간을 넘는 차이는 버린다.
 */
class TripDelta {
    private var base: Int? = null
    fun reset() { base = null }
    fun of(raw: Int?): Int? {
        raw ?: return null
        val b = base?.takeIf { raw >= it } ?: raw.also { base = it }
        return (raw - b).takeIf { it <= 24 * 60 }
    }
}

/** 시스템 시각 + offsetMs. offset은 디버그 브로드캐스트만 바꾼다(요일·시각 시뮬). release에선 늘 0. */
class ShiftClock(@Volatile var offsetMs: Long = 0, private val base: java.time.Clock = java.time.Clock.systemDefaultZone()) : java.time.Clock() {
    override fun getZone(): java.time.ZoneId = base.zone
    override fun withZone(z: java.time.ZoneId?): java.time.Clock = this
    override fun instant(): java.time.Instant = base.instant().plusMillis(offsetMs)
    override fun millis(): Long = base.millis() + offsetMs
}

/** H13: 화면 상단 상태 — 거짓으로 '받는 중'이라고 하지 않는다. */
fun readiness(offline: Boolean, forwardOk: Boolean, mode: String): String = when {
    offline -> "서버 연결 끊김 · 알림이 늦어져요"
    !forwardOk -> "AI 응대 준비 안 됨(착신 설정 필요)"
    mode == "ment" -> "안내 멘트로 응대 중"
    mode == "off" -> "응대 꺼짐"
    else -> "AI가 대신 받는 중"
}

/** F6 데모 일정 1건(서버 /car/schedule). start = epoch ms. */
data class Event(val title: String, val start: Long, val place: String, val at: LatLng)

/** F7: 아직 시작 안 한 일정 중 가장 이른 것. */
fun nextEvent(events: List<Event>, nowMs: Long): Event? = events.filter { it.start > nowMs }.minByOrNull { it.start }

/** 방이 바뀔 때 한 문장. PARK는 무음. */
fun roomSpeech(r: Room): String? = when (r) {
    Room.DRIVE_WORK -> "업무 운전 모드예요"
    Room.DRIVE_REST -> "업무 시간 밖이에요, 급한 전화만 알려 드릴게요" // 저녁·주말·출근 전 귀가 공통
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
