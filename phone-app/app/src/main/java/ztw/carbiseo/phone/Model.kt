package ztw.carbiseo.phone

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// 화면 로직(순수 함수). 안드로이드 API를 쓰지 않아 단위 시험(ModelTest)으로 확인한다.
// 맡는 일: 앱이 한 일의 로컬 기록(Event) 저장 형식·집계, 홈 배너 문구, 첫 실행 단계, 하차 정보(Parked) 읽은 뒤 처리.
// 맡지 않는 일: 화면 그리기·권한(App.kt), 알림 전달(Listener.kt), 서버 호출과 응답 해석(Relay.kt).
// 먼저 읽을 것: banner(홈 배너 판정), step(첫 실행 순서), parkedNext(서버 읽기가 실패했을 때 저장본 처리).
// 개인정보: 기록에는 앱 이름과 보낸이만 남기고 메시지 내용은 저장하지 않는다.

/**
 * 앱이 실제로 한 일 1건. 메시지 내용은 저장하지 않는다(앱 이름·보낸이만).
 * SENT 서버로 전달 성공, SEND_FAIL 전달 실패, REPLIED 음성 답장을 알림의 답장 버튼으로 보냄, REPLY_FAIL 답장을 못 보냄.
 */
enum class Kind { SENT, SEND_FAIL, REPLIED, REPLY_FAIL }
data class Event(val at: Long, val kind: Kind, val app: String, val who: String)

// 한계: 전체 기록이 300건을 넘으면 오래된 것부터 잘린다. 하루에 300건을 넘게 보내면 '오늘' 숫자가 실제보다 적다 (넘으려면 날짜별 카운터를 따로 둔다).
const val MAX_EVENTS = 300

// 저장 형식이 탭·줄바꿈 구분이라 값 속의 탭·줄바꿈은 공백으로 바꾼다.
private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

/** 한 줄에 기록 1건: 시각, 종류, 앱 이름, 보낸이를 탭으로 잇는다. */
fun encode(es: List<Event>): String = es.joinToString("\n") { "${it.at}\t${it.kind}\t${clean(it.app)}\t${clean(it.who)}" }

/** encode의 역. 칸 수·시각·종류가 맞지 않는 깨진 줄은 버린다. */
fun decode(s: String?): List<Event> = s.orEmpty().lines().mapNotNull { l ->
    val p = l.split('\t')
    if (p.size != 4) return@mapNotNull null
    val at = p[0].toLongOrNull() ?: return@mapNotNull null
    val k = runCatching { Kind.valueOf(p[1]) }.getOrNull() ?: return@mapNotNull null
    Event(at, k, p[2], p[3])
}

/** 새 기록을 앞에 붙이고 최대 개수로 자른다(최신이 맨 앞). */
fun add(es: List<Event>, e: Event): List<Event> = (listOf(e) + es).take(MAX_EVENTS)

private fun day(t: Long, z: ZoneId) = Instant.ofEpochMilli(t).atZone(z).toLocalDate()
/** now와 같은 날(기기 시간대)의 기록만. */
fun today(es: List<Event>, now: Long, z: ZoneId): List<Event> = es.filter { day(it.at, z) == day(now, z) }
/** 오늘 서버로 전달에 성공한 알림 수(실패·답장은 세지 않는다). */
fun sentToday(es: List<Event>, now: Long, z: ZoneId) = today(es, now, z).count { it.kind == Kind.SENT }

fun hm(t: Long, z: ZoneId): String = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(t).atZone(z))
fun dayLabel(t: Long, now: Long, z: ZoneId): String = when (day(t, z)) {
    day(now, z) -> "오늘"
    day(now, z).minusDays(1) -> "어제"
    else -> DateTimeFormatter.ofPattern("M월 d일").format(day(t, z))
}

/** 타임라인 한 줄: 제목, 보조 문구. */
fun line(e: Event): Pair<String, String> = when (e.kind) {
    Kind.SENT -> "${e.who} 메시지를 차로 보냄" to e.app
    Kind.SEND_FAIL -> "${e.who} 메시지를 보내지 못함" to "${e.app} · 인터넷 연결 확인"
    Kind.REPLIED -> "음성 답장 보냄" to "${e.app} · ${e.who}에게"
    Kind.REPLY_FAIL -> "음성 답장을 보내지 못함" to "${e.app} · 알림이 이미 지워졌어요"
}

/** 홈 상단 배너의 톤. WARN 주의(알림 읽기 꺼짐·전달 실패), ACTIVE 오늘 전달함, IDLE 대기. */
enum class Tone { WARN, ACTIVE, IDLE }
data class Banner(val title: String, val sub: String, val tone: Tone)

/**
 * 홈 상단 배너. 폰은 차 연결·방을 모른다. 아는 것(권한, 서버 전달 결과)만 말한다.
 * 순서: 알림 읽기가 꺼짐 → 경고, 가장 최근 전달이 실패 → 경고, 오늘 전달한 적 있음 → 전달 중, 그 밖에는 대기.
 */
fun banner(listenerOn: Boolean, es: List<Event>, now: Long, z: ZoneId): Banner {
    if (!listenerOn) return Banner("알림 읽기가 꺼져 있어요", "눌러서 켜면 메시지를 차로 보낼 수 있어요", Tone.WARN)
    val lastSend = es.firstOrNull { it.kind == Kind.SENT || it.kind == Kind.SEND_FAIL }
    if (lastSend?.kind == Kind.SEND_FAIL) return Banner("차로 보내지 못하고 있어요", "인터넷 연결을 확인해 주세요 · ${hm(lastSend.at, z)} 시도", Tone.WARN)
    if (lastSend != null && day(lastSend.at, z) == day(now, z))
        return Banner("차로 전달하고 있어요", "마지막 전달 ${hm(lastSend.at, z)} · 차 연결과 방은 차 화면에서 볼 수 있어요", Tone.ACTIVE)
    return Banner("차와 연결 대기", "메시지 알림이 오면 차로 보내요", Tone.IDLE)
}

/** 첫 실행 아래 큰 버튼: 아직 안 한 단계를 차례로 안내한다. */
enum class Step { PICK_CARRIER, FORWARD, CONFIRM_FORWARD, LISTENER, DONE }
fun step(carrier: String?, dialerOpened: Boolean, forwardDone: Boolean, listenerOn: Boolean): Step = when {
    carrier == null && !forwardDone -> Step.PICK_CARRIER
    !forwardDone && !dialerOpened -> Step.FORWARD
    !forwardDone -> Step.CONFIRM_FORWARD
    !listenerOn -> Step.LISTENER
    else -> Step.DONE
}
fun ctaLabel(s: Step) = when (s) {
    Step.PICK_CARRIER -> "통신사를 먼저 골라 주세요"
    Step.FORWARD -> "착신전환 켜기"
    Step.CONFIRM_FORWARD -> "착신전환 설정했어요"
    Step.LISTENER -> "알림 읽기 허용하기"
    Step.DONE -> "시작하기"
}
/** 진행 막대 2칸: 착신전환, 알림 읽기. */
fun progress(forwardDone: Boolean, listenerOn: Boolean) = listOf(forwardDone, listenerOn)

/** 통신사 고객센터(공식 웹). 착신 코드는 통신사마다 바뀔 수 있어 앱에 넣지 않는다. */
val CARRIERS = linkedMapOf(
    "SKT" to "https://www.tworld.co.kr",
    "KT" to "https://www.kt.com",
    "LG U+" to "https://www.lguplus.com",
)

/**
 * 차가 올린 하차 정보(서버 GET /phone/parked).
 * address: 서버가 주소를 못 찾으면 "".
 * number: 서버가 그때그때 붙인 발신 번호(없으면 null). 서버 kv에는 없고, 폰의 오프라인용 저장본에서도 parkedCache가 뺀다.
 * sub: 제목 아래 보조 줄(시각·출처, 없으면 null).
 * total: 자르기 전 전체 남은 일 수.
 */
data class ParkedTask(val id: Long, val title: String, val number: String?, val sub: String? = null)
data class Parked(val lat: Double, val lng: Double, val address: String, val memo: String, val at: Long, val tasks: List<ParkedTask>, val total: Int = tasks.size)

/** 서버 읽기 결과. Failed = 인터넷 없음·서버 오류·읽을 수 없는 응답(없음과 다르다). */
sealed interface ParkedRead {
    data class Found(val parked: Parked, val raw: String) : ParkedRead
    data object None : ParkedRead
    data object Failed : ParkedRead
}

/** 읽은 뒤 할 일: 화면에 둘 값, prefs에 쓸 원문(null이면 안 씀), prefs를 지울지. */
data class ParkedNext(val show: Parked?, val write: String?, val clear: Boolean)

/** 성공+있음 = 그리고 저장, 성공+없음 = 지우고 숨김, 실패 = 저장본이 있으면 그대로(지하 주차장에서도 위치를 찾게). */
fun parkedNext(r: ParkedRead, saved: String?): ParkedNext = when (r) {
    is ParkedRead.Found -> ParkedNext(r.parked, r.raw, false)
    ParkedRead.None -> ParkedNext(null, null, true)
    ParkedRead.Failed -> ParkedNext(saved?.let(::parseParked), null, false)
}

/** 틱으로 숨긴 일을 뺀 목록과 남은 전체 건수(숨긴 만큼 total도 줄인다). */
fun parkedLeft(p: Parked, hide: Set<Long>): Pair<List<ParkedTask>, Int> =
    p.tasks.filter { it.id !in hide }.let { left -> left to maxOf(left.size, p.total - (p.tasks.size - left.size)) }
