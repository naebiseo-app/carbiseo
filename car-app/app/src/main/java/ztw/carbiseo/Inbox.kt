package ztw.carbiseo

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 서버 /car/inbox 한 행(쓰는 칼럼만). */
data class InboxItem(
    val id: Long, val fromLabel: String, val summary: String,
    val urgent: Boolean, val createdAt: Long, val raw: String,
    val kind: String = "call", // call 통화 / hint 추천 답변·정정·빈 시간 / task 할 일 / sent 보낸 문자 / notif 폰 알림 / event·ring 웹훅·reply 답장 대기열(안 보임)
    val status: String = "", val code: String = "", // sent: status sent|failed, code = 업체 결과(overseas_ip_blocked 등)
    val car: String = "", // 서버가 붙인 "쓴 차량 데이터" 칩
    val room: String = "", // 서버가 받을 때의 차 방(PARK|DRIVE_WORK|DRIVE_REST|"")
    val fromNumber: String = "", // H15 회신용 전체 번호(화면엔 끝 4자리만). 30일 보관 후 행째 삭제
    val app: String = "", val replyable: Boolean = false, // notif: 폰 알림 앱 이름·답장 가능
    val direct: Boolean = false, // 서버 바로 연결 목록에 있는 발신자(meta.direct). 쉼·피로 중에도 읽는다
    val replaces: Long = 0L, // meta.replaces: 이 요약 카드가 대신하는 '전화 왔어요'(ringing) 카드 id. 0 = 없음
)

/**
 * H7: 업무 외(퇴근길·저녁·주말)에 받은 통화 = 다음 업무 운전으로 넘긴 건. 반환 id -> 넘긴 날(epochDay).
 * 실시간 항목은 지금 상태(offNow = 쉼 방이거나 업무 시간 밖)로, 재시작 직후 첫 폴링 항목은 받은 시각·서버 방으로 판정한다(어제·오늘 것만).
 */
fun deferNew(fresh: List<InboxItem>, initial: Boolean, offNow: Boolean, today: Long, s: RoomSettings, zone: ZoneId = ZoneId.systemDefault()): Map<Long, Long> {
    val calls = fresh.filter { it.kind == "call" }
    if (!initial) return if (offNow) calls.associate { it.id to today } else emptyMap()
    return calls.mapNotNull { i ->
        val at = Instant.ofEpochMilli(i.createdAt).atZone(zone).toLocalDateTime()
        (i.id to at.toLocalDate().toEpochDay()).takeIf { (i.room == Room.DRIVE_REST.name || offDuty(at, s)) && it.second >= today - 1 }
    }.toMap()
}

/** H7: 오늘 이전에 넘겼고 아직 안 본 통화. */
fun carryOver(items: List<InboxItem>, deferred: Map<Long, Long>, seen: Set<Long>, today: Long): List<InboxItem> =
    items.filter { i -> deferred[i.id]?.let { it < today } == true && i.id !in seen }

/** H7: 첫 업무 운전 때 한 문장. 넘긴 날이 모두 어제면 '어제', 아니면(주말 넘김 등) '지난번에'. */
fun carrySpeech(items: List<InboxItem>, deferred: Map<Long, Long>, today: Long): String? {
    if (items.isEmpty()) return null
    val y = items.all { deferred[it.id] == today - 1 }
    return "${if (y) "어제" else "지난번에"} 넘긴 전화 ${items.size}건 있어요. 차 세우면 보여 드릴게요."
}

/** "id:day,id:day" <-> map. 최근 300개만 남긴다. */
fun encodeDeferred(m: Map<Long, Long>): String = m.entries.sortedBy { it.key }.takeLast(300).joinToString(",") { "${it.key}:${it.value}" }
fun decodeDeferred(s: String?): Map<Long, Long> =
    s.orEmpty().split(",").mapNotNull { e -> e.split(":").takeIf { it.size == 2 }?.let { (a, b) -> a.toLongOrNull()?.let { k -> b.toLongOrNull()?.let { k to it } } } }.toMap()

/** 차 화면에 보이는 종류. ring은 곧 call 카드가 되고 event는 웹훅 원문이라 숨긴다. */
val SHOWN_KINDS = setOf("call", "hint", "task", "sent", "notif")

/** 확인할 건수: 아직 안 연 카드(보낸 문자 제외). */
fun todo(items: List<InboxItem>, seen: Set<Long>) = items.count { it.kind != "sent" && it.id !in seen }

/** F3: [완료]한 카드와 H2: 오늘 [내일 아침에]로 미룬 카드(snoozed id -> 미룬 날)는 목록·건수에서 뺀다. */
fun hideCards(items: List<InboxItem>, done: Set<Long>, snoozed: Map<Long, Long> = emptyMap(), today: Long = 0): List<InboxItem> =
    items.filter { it.id !in done && snoozed[it.id] != today }

/** H2: [내일 아침에] -> (deferred, snoozed, seen). 넘긴 건(deferred)에 오늘 날짜로 넣어 다음 날 첫 업무 운전의 출근길 이월(carryOver)에 들어가고,
 *  본 카드였어도 이월에 들게 seen에서 뺀다. 미룬 날(snoozed)엔 목록에서 숨긴다. */
fun snoozeCard(deferred: Map<Long, Long>, snoozed: Map<Long, Long>, seen: Set<Long>, id: Long, today: Long) =
    Triple(deferred + (id to today), snoozed + (id to today), seen - id)

/** 보낸 문자 카드 상태 한 줄. 국외 IP 차단은 숨기지 않고 그대로 알린다. */
fun sentStatus(i: InboxItem) = when {
    i.status == "sent" -> "보냄"
    i.code == "overseas_ip_blocked" -> "못 보냄(서버 IP 제한)"
    else -> "못 보냄(${i.code.ifBlank { "알 수 없음" }})"
}

/** 할 일 본문 -> (받는 번호, 문자 내용). 번호는 본문에 있으면 채우고, 내용은 "내용:" 뒤(없으면 본문 전체). */
fun taskDraft(text: String): Pair<String, String> {
    val num = Regex("""01\d-?\d{3,4}-?\d{4}|0\d{1,2}-\d{3,4}-\d{4}""").find(text)?.value?.replace("-", "") ?: ""
    val body = text.split(" / ").firstNotNullOfOrNull { Regex("""내용\s*[:：]\s*(.+)""").find(it)?.groupValues?.get(1) } ?: text
    return num to body.trim()
}

/** O2: 할 일 본문의 `메일: 받는사람 / 제목 / 내용` 줄 -> (받는 사람, 제목, 내용). 없거나 칸이 모자라면 null(= 문자 초안으로 본다). */
fun mailDraft(text: String): Triple<String, String, String>? {
    val p = Regex("""메일\s*[:：]\s*(.+)""").find(text)?.groupValues?.get(1)?.split(" / ", limit = 3)?.map { it.trim() } ?: return null
    return if (p.size == 3 && p[0].isNotEmpty() && p[2].isNotEmpty()) Triple(p[0], p[1], p[2]) else null
}

/** 서버가 ringing 카드를 요약 카드로 교체한 항목들이 가리키는 옛 카드 id들. 차는 이 카드를 inbox·callFresh·seen 등에서 뺀다. */
fun replacedIds(items: List<InboxItem>): Set<Long> = items.mapNotNull { it.replaces.takeIf { r -> r > 0 } }.toSet()

/** 교체 요약 카드는 일반 새 전화 문장(speechFor·directSpeech)으로 다시 읽지 않고, 업무·쉼 운전 중에만 "○○님 전화 요약: …" 한 문장씩(최대 3). 끔 모드·정차·판정 전은 카드만. */
fun replaceSpeech(items: List<InboxItem>, room: Room?, mode: String = "ai"): String? {
    if (mode == "off" || (room != Room.DRIVE_WORK && room != Room.DRIVE_REST)) return null
    val d = items.filter { it.kind == "call" && it.replaces > 0 && it.summary.isNotBlank() }.distinctBy { it.id }.take(3)
    if (d.isEmpty()) return null
    return sentences(*d.map { "${if (it.fromLabel.isBlank()) "알 수 없는 번호" else "${it.fromLabel}님"} 전화 요약: ${firstSentence(it.summary)}" }.toTypedArray())
}

/** 서버에서 지워진 카드(관리자 inbox 비우기·30일 삭제)는 차에서도 뺀다. minId: 서버 최소 id(비었으면 Long.MAX_VALUE), null = 모름 → 그대로. */
fun pruneGone(items: List<InboxItem>, minId: Long?): List<InboxItem> = if (minId == null) items else items.filter { it.id >= minId || it.id >= LOCAL_ID_BASE }

/** 차 안에서 만든 할 일 카드(서버에 없음)의 id 시작값. 서버 id와 겹치지 않고 서버 정리(pruneGone)에 지워지지 않는다. */
const val LOCAL_ID_BASE = Long.MAX_VALUE / 2

/** F4·O4·회신 초안 할 일 카드(차 안에서 만든 것)를 prefs에 넣고 빼는 형식: "id:createdAt:본문(URL 인코딩),…". 최근 30개. */
fun encodeLocalTasks(items: List<InboxItem>): String = items.filter { it.kind == "task" && it.id >= LOCAL_ID_BASE }.sortedBy { it.id }.takeLast(30)
    .joinToString(",") { "${it.id}:${it.createdAt}:${java.net.URLEncoder.encode(it.summary, "UTF-8")}" }
fun decodeLocalTasks(s: String?): List<InboxItem> = s.orEmpty().split(",").mapNotNull { e ->
    e.split(":").takeIf { it.size == 3 }?.let { (a, b, c) -> a.toLongOrNull()?.let { id -> b.toLongOrNull()?.let { at ->
        InboxItem(id, "내비서", java.net.URLDecoder.decode(c, "UTF-8"), false, at, "", kind = "task") } } }
}

/** 마지막으로 본 id. 항목이 없으면 그대로. */
fun nextSince(since: Long, items: List<InboxItem>): Long = maxOf(since, items.maxOfOrNull { it.id } ?: since)

/** 새 항목 -> 읽을 한 문장. PARK(또는 방 미판정)이거나 항목이 없으면 null. 퇴근길(DRIVE_REST)은 급한 전화만 읽는다.
 *  M7: 응대 모드 '끔'은 무음(카드만), '멘트'는 AI가 받았다고 말하지 않는다. */
fun speechFor(items: List<InboxItem>, room: Room?, mode: String = "ai"): String? {
    if (mode == "off") return null
    val calls = items.filter { it.kind == "call" }.distinctBy { it.id } // 웹훅 이벤트·힌트·할 일은 세지 않는다
    val newItems = if (room == Room.DRIVE_REST) calls.filter { it.urgent || it.direct } else calls
    if (newItems.isEmpty() || (room != Room.DRIVE_WORK && room != Room.DRIVE_REST)) return null
    val n = newItems.size; val u = newItems.count { it.urgent || it.direct }
    val head = when {
        n == 1 -> if (u == 1) "급한 전화 한 건" else "부재중 전화 한 건"
        u == n -> "급한 전화 ${n}건"
        u > 0 -> "급한 전화 ${u}건 포함 ${n}건"
        else -> "부재중 전화 ${n}건"
    }
    return "$head, ${if (mode == "ment") "안내 멘트로 응대했어요." else "AI가 용건을 받아 두었습니다."}"
}

/** 바로 연결(급함·direct) 전화 한 줄씩(최대 3). 방·피로 필터를 통과시키려고 따로 둔다 — 호출자가 안전 미루기(조작·경고·통화 중)는 그대로 거친다. 끔 모드는 무음. */
fun directSpeech(items: List<InboxItem>, mode: String = "ai"): String? {
    if (mode == "off") return null
    val d = items.filter { it.kind == "call" && (it.urgent || it.direct) }.distinctBy { it.id }.take(3)
    if (d.isEmpty()) return null
    return sentences(*d.map { "바로 연결: ${it.fromLabel.ifBlank { "알 수 없는 번호" }}님 전화${if (it.summary.isNotBlank()) ", 용건 ${it.summary}" else ""}" }.toTypedArray())
}

/** M12: 세운 지 30초 뒤, 안 본 급한 통화가 남았으면 정차마다 한 문장. */
const val PARK_REMIND_MS = 30_000L
fun parkReminder(items: List<InboxItem>, seen: Set<Long>): String? =
    items.count { it.kind == "call" && it.urgent && it.id !in seen }.takeIf { it > 0 }?.let { "급한 전화 ${it}건, 아직 안 보셨어요." }

private val timeFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/** 화면용 한 줄. from_label/summary가 비면 raw 앞 80자. */
fun itemLine(i: InboxItem, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = timeFmt.format(Instant.ofEpochMilli(i.createdAt).atZone(zone))
    val body = if (i.fromLabel.isBlank() && i.summary.isBlank()) i.raw.take(80)
    else "${i.fromLabel} / ${i.summary}"
    return "${if (i.urgent) "[급함] " else ""}$time $body"
}

/** S4: 오늘(zone 기준 날짜) 받은 통화 수. 웹훅 이벤트는 같은 통화라 세지 않는다.
 *  offsetMs: 앱 시계(디버그 TIME)가 실제 시각에서 민 만큼. 카드 시각은 서버 실제 시각이라 같이 밀어야 시연 시각의 '오늘'과 맞는다(0이면 그대로). */
fun todayCalls(items: List<InboxItem>, today: java.time.LocalDate, zone: ZoneId = ZoneId.systemDefault(), offsetMs: Long = 0): Int =
    items.count { it.kind == "call" && Instant.ofEpochMilli(it.createdAt + offsetMs).atZone(zone).toLocalDate() == today }

/** 주차 화면 순서: 급한 카드 먼저, 그 안에서 최신순. */
fun parkOrder(items: List<InboxItem>): List<InboxItem> = items.sortedWith(compareByDescending<InboxItem> { it.urgent }.thenByDescending { it.id })
