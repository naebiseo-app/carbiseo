package ztw.carbiseo

// 주행 보조의 순수 함수 모음(Android를 모르므로 DriveTest가 그대로 검증한다). 위에서 아래로 대략 이런 순서다:
// 집 문턱·휴식·근처 찾기·먼저 말 걸기(offerDue) → 주차 메모·하차 카드 → [말하기] 의도(carIntent) → 일정·지각 예측(lateBy)
// → 문자·예약 문장 → 휴게소 목록(nearestRestStop) → 짧은 정차·트립 시간 차이 → 피로·졸음 확인 → 권한·주행 제한(UxLimits).
// 이 함수들을 언제 부르는지(틱·폴링)는 RoomService.kt, 신호를 모으는 곳은 CarAdapters.kt다. 먼저 읽을 곳: carIntent, lateBy, drowsyRisk.

const val HOME_SOON_MIN = 5
const val HOME_SOON_SPEECH = "오늘 일은 여기까지예요. 곧 도착합니다"

/** H1 집 문턱: 업무 시간 밖 방(DRIVE_REST) + 목적지가 집 + 남은 분 ≤ 5. */
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
 * 그래서 값이 반전돼 오는 에뮬(빈 좌석 = true)에서도 시작 즉시 동승자 확인 띠("동승자 모드로 바꿀까요?")가 뜨지 않고, 콘솔로 바꾸는 순간에만 뜬다.
 * 한계: 시동 직후 이미 앉아 있던 동승자는 못 잡는다(그 사람이 내리면 띠가 한 번 잘못 뜬다) (넘으려면 실차 값 방향을 확인해 기준선 대신 그 값을 쓴다).
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
const val REST_SPEECH = "오래 운전하셨어요, 쉬어 가실래요?"

// 음성 승인용: 끝이 질문이라 대답을 바로 받는다. 못 듣는 때(경고·통화 중)엔 위의 REST_SPEECH만.
const val REST_ASK = "오래 운전하셨어요, 가까운 휴게소를 경유지로 넣을까요?"
const val REST_SNOOZE_MS = 30 * 60_000L

/** S2: 이번 운전(PARK에서 나온 뒤) 연속 주행이 설정 분 이상(+ '아니' 대답 뒤 30분은 쉰다). startMs = null이면 정차 중. */
fun restDue(startMs: Long?, nowMs: Long, thresholdMin: Int, snoozeUntilMs: Long = 0): Boolean =
    startMs != null && nowMs - startMs >= thresholdMin * 60_000L && nowMs >= snoozeUntilMs

/** Q3 "급해요/화장실": 운전 중 이 말이 들리면 가까운 휴게소(화장실 있음). 답장 문장 속 '급해서'는 제외. */
fun urgentNeed(said: String?): Boolean {
    val n = said.orEmpty().replace(Regex("""\s"""), "")
    return "답장" !in n && ("급해" in n || "화장실" in n)
}

/** 근처 찾기 제안: 배고파·커피·기름·충전. code는 서버 /car/nearby의 카카오 category_group_code. */
enum class NearKind(val code: String, val label: String) { FOOD("FD6", "식당"), CAFE("CE7", "카페"), GAS("OL7", "주유소"), CHARGE("CS2", "충전소") }
private val NEAR_FOOD = Regex("배고|출출|허기|뭐먹지|밥먹(자|고싶|을|으러)|밥집|근처식당|식당찾|맛집|먹을데|먹을곳")
private val NEAR_CAFE = Regex("커피(마시|한잔|먹고|사)|근처카페|카페찾|카페가")
private val NEAR_GAS = Regex("기름(없|떨어|넣|부족)|주유")
private val NEAR_CHARGE = Regex("충전(소|하|해야)|배터리(없|부족|떨어)")
/** "배고파"·"커피 마시고 싶다"·"기름 떨어졌어" -> 찾아 줄 종류. 답장 문장 속 말은 제외(urgentNeed와 같은 이유). */
fun nearWish(said: String?): NearKind? {
    val n = said.orEmpty().replace(Regex("""\s"""), "")
    return if ("답장" in n) null else when { NEAR_FOOD.containsMatchIn(n) -> NearKind.FOOD; NEAR_CAFE.containsMatchIn(n) -> NearKind.CAFE; NEAR_GAS.containsMatchIn(n) -> NearKind.GAS; NEAR_CHARGE.containsMatchIn(n) -> NearKind.CHARGE; else -> null }
}
/** 검색 결과(가까운 순) 중 안내할 1곳: 목적지가 있으면 앞쪽(지금보다 목적지에 가까운 곳)만, 지나온 곳은 고르지 않는다(nearestRestStop과 같은 방식). 목적지·현재 위치를 모르면 가장 가까운 곳. */
fun pickNear(places: List<NearPlace>, loc: LatLng?, dest: LatLng?): NearPlace? =
    if (loc == null || dest == null) places.minByOrNull { it.distance }
    else places.filter { distanceM(LatLng(it.lat, it.lng), dest) < distanceM(loc, dest) }.minByOrNull { it.distance }
fun nearAskSpeech(k: NearKind) = "근처 ${k.label} 찾아드릴까요?"
private fun meterWord(meters: Int) = if (meters < 1000) "${meters}미터" else "${((meters + 50) / 100) / 10.0}킬로미터"
fun nearFoundSpeech(name: String, meters: Int) = "$name, ${meterWord(meters)}예요, 들를까요?"
const val NEAR_NONE_SPEECH = "근처에서 찾지 못했어요"
const val NEAR_NO_LOC_SPEECH = "지금 위치를 알 수 없어요"
const val NEAR_ADDED_SPEECH = "경로에 넣었어요"
const val NEAR_NAVI_WAIT_SPEECH = "내비가 아직 준비 중이에요. 잠시 뒤 다시 말씀해 주세요"

/** 먼저 말 걸기: 끔 / 필요할 때만(운전 1회에 종류별 한 번) / 자주(같은 종류 2시간마다). */
enum class Proactive { OFF, NORMAL, OFTEN }
const val CHARGE_OFFER_SOC = 20
const val MEAL_OFFER_DRIVE_MIN = 60
private const val OFFER_GAP_MS = 2 * 60 * 60_000L
/** 점심 11:30~13:30, 저녁 17:30~19:30(양 끝 포함). 아니면 null. */
fun mealWord(t: java.time.LocalTime): String? = when {
    t >= java.time.LocalTime.of(11, 30) && t <= java.time.LocalTime.of(13, 30) -> "점심"
    t >= java.time.LocalTime.of(17, 30) && t <= java.time.LocalTime.of(19, 30) -> "저녁"
    else -> null
}
/** lastOfferMs = 이번 운전에서 이 종류를 마지막으로 제안한 시각(null = 아직 안 함). */
fun offerDue(level: Proactive, lastOfferMs: Long?, now: Long): Boolean =
    level != Proactive.OFF && (lastOfferMs == null || (level == Proactive.OFTEN && now - lastOfferMs >= OFFER_GAP_MS))
fun mealOffer(word: String) = "$word 시간이에요. 근처 식당 찾아드릴까요?"
fun chargeOffer(soc: Int) = "배터리가 ${soc}퍼센트 남았어요. 근처 충전소 찾아드릴까요?"

/**
 * 출발 때 한 번 묻기(D 기어 운전 시작): 다음 일정에 좌표가 있고 내비가 그 장소로 안 가는 중이며 이 운전에서 아직 안 물었으면
 * 질문 문장, 아니면 null. 에너지가 CHARGE_OFFER_SOC 이하면 충전소(엔진차는 주유소) 경유 문구를 붙인다.
 */
fun departOffer(next: Event?, dest: LatLng?, soc: Int?, fuelPct: Int?, isEv: Boolean, alreadyAsked: Boolean): String? {
    if (alreadyAsked || next == null || next.at.lat == 0.0 || headingTo(dest, next)) return null
    val base = "다음 일정 ${ro(next.place)} 안내할까요?"
    val low = departLow(soc, fuelPct, isEv) ?: return base
    return if (isEv) "$base 배터리가 ${low}퍼센트라 가는 길에 충전소도 넣을게요." else "$base 연료가 ${low}퍼센트라 가는 길에 주유소도 넣을게요."
}
/** 에너지가 낮으면 그 퍼센트, 아니면 null(모르는 값 포함). */
fun departLow(soc: Int?, fuelPct: Int?, isEv: Boolean): Int? = (if (isEv) soc else fuelPct)?.takeIf { it <= CHARGE_OFFER_SOC }
/** "응" 뒤 한 문장. stopName이 있으면 경유지로 넣었다고 덧붙인다. */
fun departDone(place: String, stopName: String?) = "${ro(place)} 안내할게요." + (stopName?.let { " ${eul(it)} 경유지로 넣었어요." } ?: "")
/** 받침에 맞는 조사: 마지막 글자가 한글이 아니면 받침 없음으로 본다. */
private fun jong(w: String): Int = w.lastOrNull()?.takeIf { it in '가'..'힣' }?.let { (it - '가') % 28 } ?: 0
fun ro(w: String) = w + (if (jong(w).let { it == 0 || it == 8 }) "로" else "으로") // ㄹ 받침(8)은 "로"
fun eul(w: String) = w + (if (jong(w) == 0) "를" else "을")

/** 주차 메모: "주차 지하 2층 C구역" -> "지하 2층 C구역". '주차'가 없거나 층·구역을 못 읽거나 답장 문장이면 null. */
private val PARK_SPOT = Regex("""지하\s?\d+층|\d+층|[A-Za-z가-힣]?\d*구역""")
private val PARK_QUESTION = Regex("""자리\s?있|있어\?|있나|어디""") // "주차장 3층 자리 있어?"는 메모가 아니라 문의
fun parkMemo(said: String?): String? {
    val t = said.orEmpty()
    if ("주차" !in t || "답장" in t || PARK_QUESTION.containsMatchIn(t)) return null
    return PARK_SPOT.findAll(t).joinToString(" ") { it.value }.ifEmpty { null }
}
const val PARK_MEMO_SPEECH = "주차 위치를 적어 뒀어요"

/** 하차 카드의 남은 일 한 줄. kind = task(할 일)|call(회신할 전화). sub = 시각 + 출처. */
data class ParkTask(val id: Long, val title: String, val sub: String, val kind: String)
/** total = 8건으로 자르기 전 전체 남은 일 수. hasLocation = 차가 위치를 알았나(주소만 없을 수 있다). work = 직전 주행 방이 업무 운전(DRIVE_WORK)이었나. */
data class ParkCard(val memo: String, val place: String?, val savedAt: Long, val tasks: List<ParkTask>, val total: Int = tasks.size, val hasLocation: Boolean = false, val work: Boolean = true) {
    /** 폰에서 끝낸 일을 뺀다. 목록에서 뺀 만큼 total도 줄인다(목록 밖 일은 모르니 그대로). */
    fun without(ids: Set<Long>): ParkCard = tasks.filter { it.id !in ids }.let { left -> if (left.size == tasks.size) this else copy(tasks = left, total = maxOf(left.size, total - (tasks.size - left.size))) }
}

/** 장소 줄: 주소가 있으면 주소, 위치만 있으면 그렇게, 위치도 없으면 못 받았다고. */
fun parkPlaceText(place: String?, hasLocation: Boolean): String = place ?: if (hasLocation) "주소 없이 위치만 저장했어요" else "위치를 받지 못했어요"

private fun parkClock(ms: Long, zone: java.time.ZoneId): String = java.time.Instant.ofEpochMilli(ms).atZone(zone).let { "${if (it.hour < 12) "오전" else "오후"} ${if (it.hour % 12 == 0) 12 else it.hour % 12}:${"%02d".format(it.minute)}" }

private fun parkable(cards: List<InboxItem>) = cards.filter { it.kind == "task" || it.kind == "call" }

/** 하차 카드의 남은 일 전체 건수(8건으로 자르기 전). */
fun parkTotal(cards: List<InboxItem>): Int = parkable(cards).size

/** 하차 카드의 남은 일: 할 일·회신할 전화만, 주차 화면 순서(parkOrder)로 최대 8건. */
fun parkTasks(cards: List<InboxItem>, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<ParkTask> =
    parkOrder(parkable(cards)).take(8).map { i ->
        val t = parkClock(i.createdAt, zone)
        if (i.kind == "call") ParkTask(i.id, "${if (i.fromLabel.isBlank()) "모르는 번호에" else "${i.fromLabel}님께"} 전화하기", "$t ${if (i.urgent) "급한 전화" else "부재중"}", "call")
        else ParkTask(i.id, cardText(i).substringBefore(" — ").substringBefore(" · ").ifBlank { "할 일" }, "$t ${if (i.id >= LOCAL_ID_BASE) "내비서가 남김" else "통화에서 할 일"}", "task")
    }

/** 서버·폰에 보내는 할 일 id: 차 안에서 만든 카드(LOCAL_ID_BASE 이상)는 JSON 정밀도를 넘으니 음수로 줄인다. 서버는 양수 id만 inbox 행으로 본다. */
fun parkWireId(id: Long): Long = if (id >= LOCAL_ID_BASE) LOCAL_ID_BASE - id else id
fun parkLocalId(wire: Long): Long = if (wire < 0) LOCAL_ID_BASE - wire else wire

/** 하차 카드를 새로 띄울 때: 주행 방에서 PARK로 바뀐 때만(앱 시작 직후 첫 PARK는 from이 null), 위치도 남은 일도 없으면 안 띄운다. */
fun parkCardNow(from: Room?, hasPlace: Boolean, tasks: List<ParkTask>): Boolean = from != null && from != Room.PARK && (hasPlace || tasks.isNotEmpty())

const val PARK_MEMO_ASK = "어디에 세우셨어요?"

/** "주차 자리 있어?": 주차 메모(층·구역이 든 말)가 아닌 주차 문의. 답장 문장 속 말은 제외. */
private val PARK_ASK = Regex("주차(자리|장|할곳|할데|할만|공간|돼|되나|되니|되는|가능|할수)")
fun parkingWish(said: String?): Boolean {
    val n = said.orEmpty().replace(Regex("""\s"""), "")
    return "답장" !in n && PARK_ASK.containsMatchIn(n)
}
/** 실시간 주차 결과(가까운 순) 중 빈자리가 있는 가장 가까운 1곳. */
fun pickPark(lots: List<ParkLot>): ParkLot? = lots.filter { it.available > 0 }.minByOrNull { it.distM }
const val PARK_CALL_ASK = "가게에 주차 자리 물어볼까요?"
const val PARK_NO_INFO_SPEECH = "주차 정보를 찾지 못했어요"
const val PARK_CALL_FAIL_SPEECH = "전화를 걸지 못했어요"
/** 한 문장. 빈자리 있으면 가장 가까운 곳 + 경유지 제안(NEAR_GO), 전부 찼으면 그 말 + 전화 제안, 결과가 없으면 전화 제안(가게를 알 때) 또는 정보 없음. */
fun parkingSpeech(lots: List<ParkLot>, canCall: Boolean): String {
    pickPark(lots)?.let { return "${it.name}에 ${it.available}자리 남았고 ${meterWord(it.distM)}예요, 경유지로 넣을까요?" }
    val call = if (canCall) PARK_CALL_ASK else null
    return if (lots.isNotEmpty()) listOfNotNull("근처 주차장이 다 찼어요", call).joinToString(", ") else call ?: PARK_NO_INFO_SPEECH
}

/**
 * F5 차 음성 입구: [말하기]로 한 마디 듣고 키워드로만 가른다(LLM 없음).
 * 우선순위(carIntent의 when 순서): 급해·화장실(Q3) > 아까 전화 > 일정 > 예약(인원은 partyOf로 따로 읽는다) > 주차 메모 > 주차 자리 문의 > 근처 찾기.
 * "일정"이 "예약"보다 먼저라, 한 문장에 둘 다 있으면 일정으로 읽는다.
 */
enum class CarIntent { RECENT_CALLS, URGENT, NEXT_EVENT, RESERVE, PARK_MEMO, PARKING, NEARBY, UNKNOWN }
private val RECENT_CALL_WORDS = Regex("아까전화|전화뭐|누구전화|최근전화|최근통화|부재중|전화온거|전화왔")
fun carIntent(said: String?): CarIntent {
    val n = said.orEmpty().replace(Regex("""\s"""), "")
    return when { urgentNeed(said) -> CarIntent.URGENT; RECENT_CALL_WORDS.containsMatchIn(n) -> CarIntent.RECENT_CALLS; "일정" in n -> CarIntent.NEXT_EVENT; "예약" in n -> CarIntent.RESERVE; parkMemo(said) != null -> CarIntent.PARK_MEMO; parkingWish(said) -> CarIntent.PARKING; nearWish(said) != null -> CarIntent.NEARBY; else -> CarIntent.UNKNOWN }
}
/** Q1 조용한 비서: 한 질문에서 못 알아들었을 때 다시 묻는 건 1번뿐. again()이 true면 한 번 더 묻고, false면 화면 2택으로 넘긴다. 새 질문마다 reset. */
class Reask {
    private var n = 0
    fun reset() { n = 0 }
    fun again(): Boolean = n++ == 0
}
const val TALK_PROMPT = "말씀하세요"
const val REASK_SPEECH = "다시 말씀해 주세요"
const val NO_VOICE_SPEECH = "지금은 음성 인식을 쓸 수 없어요"

/** 요약의 첫 문장(최대 80자). */
fun firstSentence(s: String): String = s.trim().split(Regex("""(?<=[.!?。])\s+|\n""")).first().take(80)

/** "아까 전화 뭐 왔어?": 최근 통화 카드 최대 3건을 이름 + 요약 한 문장씩. 이름이 없으면 '모르는 번호'. */
fun recentCallsSpeech(items: List<InboxItem>): String {
    val calls = items.filter { it.kind == "call" }.sortedByDescending { it.id }.take(3)
    if (calls.isEmpty()) return "최근 전화는 없어요"
    return sentences("최근 전화 ${calls.size}건이에요", *calls.map { c ->
        "${if (c.fromLabel.isBlank()) "모르는 번호" else "${c.fromLabel}님"}, ${c.summary.ifBlank { "용건을 정리하고 있어요" }.let(::firstSentence)}"
    }.toTypedArray())
}

/** '오후 2시 30분' 식 읽기용 시각. */
fun koTime(ms: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
    val t = java.time.Instant.ofEpochMilli(ms).atZone(zone)
    return "${if (t.hour < 12) "오전" else "오후"} ${if (t.hour % 12 == 0) 12 else t.hour % 12}시${if (t.minute > 0) " ${t.minute}분" else ""}"
}

/** "다음 일정이 뭐야?": 다음 일정 한 문장. */
fun nextEventSpeech(e: Event?, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
    if (e == null) "다음 일정이 없어요" else "다음 일정은 ${koTime(e.start, zone)} ${e.title}${if (e.place.isNotBlank()) ", ${e.place}" else ""}이에요"

/** 출근 브리핑 일정 한 문장: 오늘(zone 기준) 아직 안 지난 일정이 있으면 건수와 첫 일정, 없으면 null. */
fun briefSpeech(events: List<Event>, nowMs: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String? {
    val day = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val left = events.filter { it.start > nowMs && java.time.Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() == day }.sortedBy { it.start }
    val first = left.firstOrNull() ?: return null
    return "오늘 일정 ${left.size}건이에요. 첫 일정은 ${koTime(first.start, zone)} ${first.title}이에요."
}

const val ARRIVE_BRIEF_MIN = 10
/** 도착 전 브리핑: 그 일정 장소로 가는 중이고 남은 시간이 10분 이하이며, 이 일정에 아직 안 말했고 지각 질문도 안 나갔으면 한 문장, 아니면 null. said·lateAsked는 일정 start 집합. */
fun arriveBrief(e: Event, dest: LatLng?, etaMin: Int?, said: Set<Long>, lateAsked: Set<Long>, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String? =
    if (etaMin != null && etaMin <= ARRIVE_BRIEF_MIN && headingTo(dest, e) && e.start !in said && e.start !in lateAsked) "곧 도착해요. ${koTime(e.start, zone)} ${e.title}이에요." else null

/** 휴게소를 넣은 뒤 한 문장. 목적지가 없으면 그 휴게소가 곧 목적지. */
fun restStopSpeech(stop: RestStop, waypoint: Boolean): String {
    val n = if (stop.kind == "졸음쉼터" && "졸음쉼터" !in stop.name) "${stop.name} 졸음쉼터" else stop.name
    return if (waypoint) "가장 가까운 ${n}를 경유지로 넣었어요" else "가장 가까운 ${n}로 길 안내를 시작해요"
}

data class RestStop(val name: String, val at: LatLng, val toilet: Boolean = true, val kind: String = "휴게소")

/** assets/reststops.tsv(탭 구분: 종류·이름·위도·경도·화장실 1|0) 파싱. #주석·빈 줄·좌표 없는/깨진 행은 버린다. */
fun parseRestStops(text: String): List<RestStop> = text.lines().mapNotNull { l ->
    if (l.isBlank() || l.startsWith("#")) return@mapNotNull null
    val f = l.trimEnd('\r').split('\t')
    val la = f.getOrNull(2)?.toDoubleOrNull(); val lo = f.getOrNull(3)?.toDoubleOrNull()
    if (la == null || lo == null || f[1].isBlank()) null else RestStop(f[1], LatLng(la, lo), f.getOrNull(4) == "1", f[0])
}

// 공공데이터 졸음쉼터·휴게소 목록은 서비스가 IO에서 1회 읽어 넣는다. 실패하면 빈 목록 = 늘 '내비 휴게소 검색' 폴백.
// (NaviHelper 3.0.5에는 장소 검색 API가 없어 목록을 앱에 둔다.)
@Volatile var restStops: List<RestStop> = emptyList()

const val REST_STOP_MAX_M = 40_000.0

/**
 * H11: 앞쪽(지금보다 목적지에 가까운 곳)이고 40km 안인 휴게소 중 가장 가까운 것. 지나온 곳은 절대 고르지 않는다(유턴 경로 방지).
 * toiletOnly(Q3 급해요)면 화장실 있는 곳만. 목적지가 없으면 진행 방향을 몰라 null. null이면 호출자가 내비 장소 검색 화면을 연다.
 */
fun nearestRestStop(loc: LatLng?, dest: LatLng?, stops: List<RestStop> = restStops, toiletOnly: Boolean = false): RestStop? {
    if (loc == null || dest == null) return null
    return stops.filter { (it.toilet || !toiletOnly) &&  distanceM(it.at, dest) < distanceM(loc, dest) && distanceM(loc, it.at) <= REST_STOP_MAX_M }.minByOrNull { distanceM(loc, it.at) }
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

/** 응대 모드 '끔'은 선택지에서 뺐다: 저장값이 off면 화면엔 한 마디 안내(ment)로 보이고 다음 저장 때 ment가 된다. */
fun modeShown(mode: String) = if (mode == "off") "ment" else mode

/** 첫 실행 미리 듣기: 상대가 듣게 될 첫 마디. 서버 script.ts의 greeting(운전 중)·mentScript(도착 시각 없음)와 같은 문장. 호칭이 없으면 주어 없이. */
fun firstWords(owner: String, assistant: String, mode: String, ment: String = ""): String {
    val o = owner.trim(); val a = assistant.trim(); val subj = if (o.isNotEmpty()) "${o}님이 " else ""
    if (modeShown(mode) == "ment") return ment.trim().ifEmpty { "${subj}지금 운전 중이라 전화를 받을 수 없습니다. 정차하시면 연락드리겠습니다." }
    val ai = if (a.isNotEmpty()) "AI 비서 ${a}입니다" else "AI 비서입니다"
    val who = if (o.isNotEmpty()) "${o}님 $ai" else "이 번호로 대신 전화 받는 $ai"
    return "안녕하세요, $who. ${subj}운전 중이셔서 제가 듣고 전달해 드릴게요. 해당 내용은 녹음됩니다. 어떤 일로 전화 주셨나요?"
}

/** H13: 화면 상단 상태 — 거짓으로 '받는 중'이라고 하지 않는다. */
fun readiness(offline: Boolean, forwardOk: Boolean, mode: String): String = when {
    offline -> "서버 연결 끊김 · 알림이 늦어져요"
    !forwardOk -> "AI 응대 준비 안 됨(착신 설정 필요)"
    mode == "ment" -> "안내 멘트로 응대 중"
    mode == "off" -> "응대 꺼짐"
    else -> "AI가 대신 받는 중"
}

/** F6 일정 1건(서버 /car/schedule: 데모 일정, 구글 캘린더를 연결했으면 그 일정). start = epoch ms. */
data class Event(val title: String, val start: Long, val place: String, val at: LatLng, val contact: String = "", val contactNumber: String = "")

/** F7: 아직 시작 안 한 일정 중 가장 이른 것. */
fun nextEvent(events: List<Event>, nowMs: Long): Event? = events.filter { it.start > nowMs }.minByOrNull { it.start }

const val LATE_GRACE_MS = 2 * 60_000L
const val LATE_DONE_SPEECH = "정차하시면 보내기 버튼을 눌러 주세요"
fun lateSpeech(min: Int, rain: Boolean = false) = "${if (rain) "비가 와서 " else ""}다음 일정에 약 ${min}분 늦을 것 같아요. 늦는다고 문자 보낼까요?"
const val TRAFFIC_SLACK_MS = 10 * 60_000L
const val TRAFFIC_OFFER_SPEECH = "앞이 많이 막혀요. 다음 일정 상대에게 늦을 수 있다고 알릴까요?"
/**
 * 정체 먼저 말 걸기: 경로가 크게 막히고(HEAVY), 다음 일정에 상대 연락처가 있고, 그 일정 지각 질문을 아직 안 했고, 먼저 말 걸기 차례일 때.
 * 도착 예상(arrivalMs)이 일정보다 TRAFFIC_SLACK_MS(10분) 넘게 일찍이면 늦을 일이 없으니 묻지 않는다(모르면 묻는다). 방·동승자 조건은 부르는 쪽이 본다.
 */
fun trafficOfferDue(level: Proactive, worst: String?, next: Event?, lateAsked: Set<Long>, lastOfferMs: Long?, now: Long, arrivalMs: Long? = null): Boolean =
    worst == "HEAVY" && next != null && next.start !in lateAsked && (arrivalMs == null || arrivalMs + TRAFFIC_SLACK_MS >= next.start) && next.contactNumber.ifBlank { next.contact }.isNotBlank() && offerDue(level, lastOfferMs, now)

/** F4: 지금 + 남은 분 > 일정 시작 + 2분이면 늦을 분(반올림), 아니면 null. */
fun lateBy(nowMs: Long, etaMin: Int?, eventStartMs: Long): Int? {
    val over = nowMs + (etaMin ?: return null) * 60_000L - eventStartMs
    return if (over > LATE_GRACE_MS) Math.round(over / 60_000.0).toInt() else null
}

const val LATE_DEST_RADIUS_M = 1000.0
const val TRIP_CHECK_MS = 5 * 60_000L
const val TRIP_LOOKAHEAD_MS = 2 * 3_600_000L
/** 일정 장소로 안 가는 중이어도 "지금 바로 가도 늦는지"를 내비에 물을 차례인가. 2시간 안의 일정만, 5분에 한 번. 좌표 없는 일정은 묻지 않는다. */
fun tripCheckDue(next: Event?, dest: LatLng?, lateAsked: Set<Long>, lastCheckMs: Long?, now: Long): Boolean =
    next != null && next.at.lat != 0.0 && next.start !in lateAsked && !headingTo(dest, next) &&
        next.start - now <= TRIP_LOOKAHEAD_MS && (lastCheckMs == null || now - lastCheckMs >= TRIP_CHECK_MS)
/** F4 오탐 줄이기: 지금 가는 목적지가 그 일정 장소(1km 안)일 때만 지각을 묻는다. 목적지를 모르면 묻지 않는다. */
fun headingTo(dest: LatLng?, e: Event): Boolean = dest != null && distanceM(dest, e.at) <= LATE_DEST_RADIUS_M

/**
 * F4: 지연 문자 초안 = 할 일 카드 본문(taskDraft 형식). 연락처가 일정에 있으면 번호로 들어간다.
 * min = null(정체 먼저 말 걸기: 지각 분을 모를 때)이면 분 수 없이. rain이면 앞에 이유 한 구절.
 */
fun lateTask(e: Event, min: Int?, rain: Boolean = false) = "${e.title} 일정 지연 알림${e.contactNumber.ifBlank { e.contact }.let { if (it.isNotBlank()) " $it" else "" }} / 내용: 운전 중${if (rain) "인데 비가 와서" else "이라"} ${if (min != null) "약 ${min}분 " else ""}늦을 것 같습니다. 죄송합니다."

/** 문자 음성 발송: 휴대폰 번호(01x로 시작 10~11자리)일 때만 숫자만 돌려준다(보내기 시트와 같은 기준). 아니면 null = 카드만. 유선은 문자를 못 받는다. */
fun sendable(number: String?): String? = number.orEmpty().filter(Char::isDigit).takeIf { Regex("""01\d{8,9}""").matches(it) }

const val LANDLINE_SPEECH = "유선 번호는 문자를 받을 수 없어요"

/** 서버 에러 코드 -> 사람이 읽을 한국어 한 문장(영어 코드를 그대로 읽지 않는다). */
fun sendFailReason(code: String) = if (code == "bad_to_number") "번호를 다시 확인해 주세요" else "잠시 뒤 다시 시도해 주세요"

/** 초안을 읽어 주고 확인받는 문장. */
fun sendConfirmSpeech(body: String) = "'$body' 이대로 보낼까요?"
const val SENT_SPEECH = "보냈어요"
const val SEND_FAIL_SPEECH = "문자를 못 보냈어요. 할 일에 남겨 뒀어요"
const val SEND_KEEP_SPEECH = "할 일에 남겨 뒀어요. 정차하시면 보내기 버튼을 눌러 주세요"

/** O4 예약 대행: 목적지 이름이 식당·카페류인가. 이름을 모르면 false. */
private val RESERVE_NAME = Regex("식당|맛집|카페|레스토랑|한정식|횟집|고기")
fun reservable(name: String?): Boolean = name != null && RESERVE_NAME.containsMatchIn(name)

/** 지금 + 남은 분의 도착 시각 HH:MM. 가게에 말하기 자연스럽게 5분 단위로 올린다. */
fun arriveAt(nowMs: Long, etaMin: Int, zone: java.time.ZoneId): String =
    java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochMilli((nowMs + etaMin * 60_000L + 299_999L) / 300_000L * 300_000L).atZone(zone))

fun reserveSpeech(name: String, hhmm: String) = "${name}에 ${hhmm}쯤 도착해요. 몇 분으로 예약할까요?"
const val RESERVE_NO_DEST_SPEECH = "목적지를 먼저 잡아 주세요"
const val RESERVE_KEEP_SPEECH = "인원을 못 알아들었어요. 할 일에 남겨 뒀어요"
const val RESERVE_FAIL_SPEECH = "전화를 걸지 못했어요. 할 일에 남겨 뒀어요"
const val RESERVE_LIMIT_SPEECH = "오늘 예약 전화는 다 썼어요, 직접 전화해 주세요."
fun reserveCallSpeech(name: String) = "${name}에 전화해 볼게요"

/**
 * 서버가 알려 준 예약 전화 결과(inbox kind=reserve 행) 한 문장. calling이면 null(아직 통화 중).
 * ok면 요약 + 주차 가능 여부(meta.parking), 그 밖(need_manual 등)은 요약(거절·무응답 사유), 없으면 직접 확인.
 */
fun reserveResultSpeech(place: String, i: InboxItem): String? = when (i.status) {
    "", "calling" -> null
    "ok" -> sentences(i.summary.ifBlank { "${place} 예약을 마쳤어요" }, when { "주차" in i.summary -> null; i.parking == "가능" -> "주차 가능해요"; i.parking == "불가" -> "주차는 안 돼요"; else -> null })
    else -> i.summary.ifBlank { "${place} 예약은 직접 확인이 필요해요" }
}

private val PARTY_UNITS = listOf("한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉")
private val PARTY_COUNTED = Regex("""(\d{1,2})(?:명|분|인|사람)|(스물|열)?(한|두|세|네|다섯|여섯|일곱|여덟|아홉)?(?:명|분|인|사람)""")
private val PARTY_BARE = Regex("""(\d{1,2}|열|다섯|여섯|일곱|여덟|아홉)(?:이요|이에요|이서|이|요)?""")
private fun koNum(tens: String, unit: String) = (when (tens) { "열" -> 10; "스물" -> 20; else -> 0 }) + PARTY_UNITS.indexOf(unit) + 1 // unit이 비면 indexOf = -1 → +0
/**
 * 대답·말에서 예약 인원(1~20)을 읽는다: "두 명"·"2명"·"열두 분"·"혼자".
 * '네'·'일곱 시'를 인원으로 오해하지 않게 수사는 명·분·인·사람이 붙을 때만 읽고,
 * 말 전체가 숫자나 다섯 이상 수사 하나뿐이면("다섯이요") 그것도 읽는다. 못 읽으면 null.
 */
fun partyOf(said: String?): Int? {
    val n = said.orEmpty().replace(Regex("""[\s.,!?]"""), "")
    if ("혼자" in n) return 1
    val counted = PARTY_COUNTED.findAll(n).firstNotNullOfOrNull { m -> (m.groupValues[1].toIntOrNull() ?: koNum(m.groupValues[2], m.groupValues[3])).takeIf { it in 1..20 } }
    return counted ?: PARTY_BARE.matchEntire(n)?.groupValues?.get(1)?.let { w -> w.toIntOrNull() ?: if (w == "열") 10 else PARTY_UNITS.indexOf(w) + 1 }?.takeIf { it in 1..20 }
}

/** 하차: 기어 P + 운전석 문 위치값이 0보다 큼(열림). 값을 모르면 false. 한계: 열림 > 0은 문서 미확인(에뮬 실측으로 조정) (넘으려면 실차 값으로 확인). */
fun gotOff(gear: Gear, driverDoorPos: Int?): Boolean = gear == Gear.PARK && (driverDoorPos ?: 0) > 0
/** 앞유리 와이퍼가 켜져 있으면 비로 본다(WiperState 이름 "ON"). 한계: "ON" 외의 값(간헐 등)은 비로 보지 않는다 (넘으려면 실차 와이퍼 값을 확인한다). */
fun rainByWiper(wiperState: String?): Boolean = wiperState == "ON"
// 정체 단계를 덜 심한 순으로 나열한 것(뒤로 갈수록 심하다)
private val TRAFFIC_ORDER = listOf("FAST", "MODERATE", "SLOW", "HEAVY")
/** 내비 정체 단계(TrafficCongestionType 이름) 중 가장 심한 것. 모르면 null. */
fun worstTraffic(types: List<String>): String? = types.filter { it in TRAFFIC_ORDER }.maxByOrNull { TRAFFIC_ORDER.indexOf(it) }
/** 예약 전화 결과 카드. 할 일로 남겨 정차 때 다시 볼 수 있게 한다. */
fun reserveResultTask(name: String, result: String, parking: Boolean = false) = "$name ${if (parking) "주차 문의" else "예약"} / 내용: $result"
/** O4: 전화를 못 걸었거나 인원을 못 읽었을 때 남기는 예약 문자 초안 = 할 일 카드 본문(taskDraft 형식, 번호는 정차 후 입력). */
fun reserveTask(name: String, hhmm: String) = "$name 예약 / 내용: 안녕하세요, ${hhmm}쯤 도착 예정인데 자리(주차) 가능할까요?"
/** 유선 가게 전화(카드의 `가게전화: 번호`). 없으면 null. */
fun shopPhone(text: String): String? = Regex("""가게전화\s*[:：]\s*([0-9-]+)""").find(text)?.groupValues?.get(1)
/** 예약 카드("가게 예약 / ...")의 가게 이름. 아니면 null. */
fun reserveShop(text: String): String? = Regex("""^(.+?) 예약 /""").find(text)?.groupValues?.get(1)

/** 설정 "바로 연결 번호": 쉼표·공백으로 나눠 하이픈을 뗀 숫자 10~11자리, 최대 10개. 하나라도 틀리면 null. */
fun parseDirect(text: String): List<String>? =
    text.split(Regex("""[,，\s]+""")).map { it.replace("-", "") }.filter { it.isNotEmpty() }.takeIf { l -> l.size <= 10 && l.all { Regex("""\d{10,11}""").matches(it) } }

const val MENT_BAD = "60자 이하, 글자·숫자·. , ? !만 쓸 수 있어요"

/** L1: 포그라운드 알림 제목. Room 이름(DRIVE_WORK 등)을 사람이 읽는 말로. */
fun notifTitle(room: String) = when (room) {
    "DRIVE_WORK" -> "내비서 · 업무 운전 중"; "DRIVE_REST" -> "내비서 · 업무 시간 밖 운전"; "PARK" -> "내비서 · 정차 중"; else -> "내비서 · 실행 중"
}

/** 방이 바뀔 때 한 문장. PARK는 무음. */
fun roomSpeech(r: Room): String? = when (r) {
    Room.DRIVE_WORK -> "업무 운전 모드예요"
    Room.DRIVE_REST -> "업무 시간 밖이에요, 급한 전화만 알려 드릴게요" // 저녁·주말·출근 전 귀가 공통
    Room.PARK -> null
}

const val BIG_STEER_DEG = 90f // 이 각도(도) 이상이면 큰 조향
/** C④ 조작 중(깜빡이·후진·큰 조향)엔 말 걸지 않는다. 끝나면 보류분을 읽는다. */
fun busyManeuver(turn: Boolean, gear: Gear, steerDeg: Float?): Boolean =
    turn || gear == Gear.REVERSE || (steerDeg != null && kotlin.math.abs(steerDeg) >= BIG_STEER_DEG)

/** 신호 대기 출발 알림: 초록불까지 5초 이하·D·정지(<1km/h)·경고/통화/알림 대화 중 아님일 때만. 이미 출발했으면 침묵. */
fun greenSoon(left: Int, gear: Gear, speedKmh: Float?, busy: Boolean): Boolean =
    left in 0..5 && gear == Gear.DRIVE && (speedKmh ?: 0f) < 1f && !busy

/** C② 피로 모드: 트립 주행시간이 기준 이상이면 휴게소 안내만 더한다(전화·업무 말은 막지 않는다 — 말을 계속 하는 게 졸음 방지). */
const val FATIGUE_MIN = 120
// H10: D에선 시스템이 앱 화면을 가려 버튼이 안 보인다 → 주행 중엔 누르라고 하지 않는다. 버튼 안내는 P에서만.
fun fatigueSpeech(gear: Gear): String =
    if (gear == Gear.PARK) "두 시간째 운전 중이에요, 쉬어 가시려면 휴게소 버튼을 누르세요." else "두 시간째 운전 중이에요, 다음 휴게소에서 쉬어 가세요."
fun fatigued(room: Room?, tripMin: Int?): Boolean = room != null && room != Room.PARK && (tripMin ?: 0) >= FATIGUE_MIN

/*
 * 졸음 확인(카메라 없음): 운전 시간·시각으로 위험을 추정하고, 위험이면 15분마다 "졸리진 않으세요?"를 물어 대답이 늦거나 없는 횟수로 본다.
 * 위험 = 120분 이상, 또는 60분 이상이면서 02:00~05:59·14:00~15:59. 판정만 하는 순수 함수(주행 중인지는 호출부가 본다).
 */
const val DROWSY_CHECK_MS = 15 * 60_000L
const val DROWSY_RETRY_MS = 5 * 60_000L // 연속 2번 '느림/무응답'(ALERT)이 된 뒤에는 5분마다 다시
const val DROWSY_SLOW_MS = 6_000L // 듣기 시작~인식 결과까지(말하는 시간·인식 지연 약 2초 포함) 이보다 늦으면 '느림'
fun drowsyRisk(tripMin: Int?, hour: Int): Boolean = tripMin != null && (tripMin >= FATIGUE_MIN || tripMin >= 60 && (hour in 2..5 || hour in 14..15))
const val DROWSY_OPEN_SPEECH = "운전이 길어졌어요. 졸리면 말 걸어 주세요, 이야기 상대 해 드릴게요."
const val DROWSY_ASK = "졸리진 않으세요? 한 마디만 대답해 주세요."
const val DROWSY_OK_SPEECH = "좋아요, 계속 말 걸어 드릴게요."
const val DROWSY_ALERT_SPEECH = "반응이 늦어요. 가까운 졸음쉼터에서 쉬어 가세요."

enum class Drowsy { FINE, WATCH, ALERT }
/** 확인 시각·'느림/무응답' 연속 횟수. 시각은 elapsedRealtime 같은 단조 시계(ms). 메인 스레드에서만. */
class DrowsyCheck {
    private var last: Long? = null
    private var strikes = 0
    private var gap = DROWSY_CHECK_MS
    /** 디버그: true면 위험·간격과 상관없이 다음 틱에 바로 확인(asked가 끈다). */
    var force = false
    fun due(now: Long) = last?.let { now - it >= gap } == true
    /** 말동무 여는 말을 했거나 질문을 한 시각. */
    fun mark(now: Long) { last = now; force = false }
    /** delayMs = 질문 뒤 대답까지 걸린 시간, null = 무응답. 정상이면 카운터 0·간격 15분 복귀, 연속 2번 '느림/무응답'이면 ALERT·간격 5분. */
    fun result(delayMs: Long?): Drowsy {
        if (delayMs != null && delayMs <= DROWSY_SLOW_MS) { strikes = 0; gap = DROWSY_CHECK_MS; return Drowsy.FINE }
        strikes++
        if (strikes < 2) return Drowsy.WATCH
        gap = DROWSY_RETRY_MS; return Drowsy.ALERT
    }
    fun reset() { last = null; strikes = 0; gap = DROWSY_CHECK_MS; force = false }
}

const val TELLTALE_SPEECH = "계기판에 빨간 경고등이 켜졌어요. 안전한 곳에 세운 뒤 확인해 주세요"

/** 설정 "지금 위치를 집으로": prefs에는 "위도,경도" 한 줄로 저장. 모양이 틀리면 null. */
fun homeText(l: LatLng) = "${l.lat},${l.lng}"
fun parseHome(t: String?): LatLng? = t?.split(",")?.takeIf { it.size == 2 }?.let { (a, b) -> a.toDoubleOrNull()?.let { la -> b.toDoubleOrNull()?.let { lo -> LatLng(la, lo) } } }

/** C① 내비 즐겨찾기 '집'이 없을 때: 최근 목적지 중 이름이 '집'·'우리집'·'home'인 곳(꽃집·옷집 같은 가게는 제외). */
private val HOME_NAME = Regex("""^\s*((우리\s*)?집|(my\s*)?home)\s*$""", RegexOption.IGNORE_CASE)
fun homeFromRecent(recents: List<Pair<String?, LatLng>>): LatLng? = recents.firstOrNull { HOME_NAME.matches(it.first.orEmpty()) }?.second

/** L6 런타임 권한(매니페스트의 위험 권한). CAR_SPEED는 AAOS 런타임 권한: 거부되면 속도만 null. RECORD_AUDIO 거부면 알림 대답은 화면 버튼. POST_NOTIFICATIONS는 33+. */
const val PERM_NOTIF = "android.permission.POST_NOTIFICATIONS"
val RUNTIME_PERMS = listOf("android.car.permission.CAR_SPEED", "android.permission.RECORD_AUDIO", PERM_NOTIF)
fun missingPermissions(sdk: Int, granted: (String) -> Boolean): List<String> = RUNTIME_PERMS.filter { (it != PERM_NOTIF || sdk >= 33) && !granted(it) }
fun permName(p: String) = when (p) { "android.permission.RECORD_AUDIO" -> "마이크"; PERM_NOTIF -> "알림"; else -> "차 속도" }

/** 서버 car_context용 기어 한 글자. */
fun gearCode(g: Gear): String? = when (g) { Gear.PARK -> "P"; Gear.DRIVE -> "D"; Gear.REVERSE -> "R"; Gear.NEUTRAL -> "N"; Gear.UNKNOWN -> null }

/** 플랫폼이 내려주는 주행 규제(CarUxRestrictions). 영상·설정·키보드는 PARK 방에만 있어 방 판정으로 막히고, 주행 화면에 남는 글자만 여기서 깎는다. */
data class UxLimits(val active: Int = 0, val maxStr: Int = 0) {
    /** 통화 자막: NO_TEXT_MESSAGE(0x80)·NO_VOICE_TRANSCRIPTION(0x100)이 켜지면 숨긴다. */
    val caption get() = active and 0x180 == 0
    /** LIMIT_STRING_LENGTH(0x4)가 켜지면 플랫폼 길이로 자른다. */
    fun clip(t: String) = if (active and 0x4 != 0 && maxStr in 1 until t.length) t.take(maxStr - 1) + "…" else t
}
