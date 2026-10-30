package ztw.carbiseo

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 서버 /car/inbox 한 행(쓰는 칼럼만). */
data class InboxItem(
    val id: Long, val fromLabel: String, val summary: String,
    val urgent: Boolean, val createdAt: Long, val raw: String,
    val kind: String = "call", // call 통화 / hint 추천 답변·정정·빈 시간 / task 할 일 / sent 보낸 문자 / event·ring 웹훅(안 보임)
    val status: String = "", val code: String = "", // sent: status sent|failed, code = 업체 결과(overseas_ip_blocked 등)
    val car: String = "", // 서버가 붙인 "쓴 차량 데이터" 칩
)

/** 차 화면에 보이는 종류. ring은 곧 call 카드가 되고 event는 웹훅 원문이라 숨긴다. */
val SHOWN_KINDS = setOf("call", "hint", "task", "sent")

/** 확인할 건수: 아직 안 연 카드(보낸 문자 제외). */
fun todo(items: List<InboxItem>, seen: Set<Long>) = items.count { it.kind != "sent" && it.id !in seen }

/** 보낸 문자 카드 상태 한 줄. 국외 IP 차단은 숨기지 않고 그대로 알린다. */
fun sentStatus(i: InboxItem) = when {
    i.status == "sent" -> "보냄"
    i.code == "overseas_ip_blocked" -> "발송 실패(서버 IP 제한)"
    else -> "발송 실패(${i.code.ifBlank { "알 수 없음" }})"
}

/** 할 일 본문 -> (받는 번호, 문자 내용). 번호는 본문에 있으면 채우고, 내용은 "내용:" 뒤(없으면 본문 전체). */
fun taskDraft(text: String): Pair<String, String> {
    val num = Regex("""01\d-?\d{3,4}-?\d{4}|0\d{1,2}-\d{3,4}-\d{4}""").find(text)?.value?.replace("-", "") ?: ""
    val body = text.split(" / ").firstNotNullOfOrNull { Regex("""내용\s*[:：]\s*(.+)""").find(it)?.groupValues?.get(1) } ?: text
    return num to body.trim()
}

/** 마지막으로 본 id. 항목이 없으면 그대로. */
fun nextSince(since: Long, items: List<InboxItem>): Long = maxOf(since, items.maxOfOrNull { it.id } ?: since)

/** 새 항목 -> 읽을 한 문장. PARK(또는 방 미판정)이거나 항목이 없으면 null. 퇴근길(DRIVE_REST)은 급한 전화만 읽는다. */
fun speechFor(items: List<InboxItem>, room: Room?): String? {
    val calls = items.filter { it.kind == "call" }.distinctBy { it.id } // 웹훅 이벤트·힌트·할 일은 세지 않는다
    val newItems = if (room == Room.DRIVE_REST) calls.filter { it.urgent } else calls
    if (newItems.isEmpty() || (room != Room.DRIVE_WORK && room != Room.DRIVE_REST)) return null
    val n = newItems.size; val u = newItems.count { it.urgent }
    val head = when {
        n == 1 -> if (u == 1) "급한 전화 한 건" else "부재중 전화 한 건"
        u == n -> "급한 전화 ${n}건"
        u > 0 -> "급한 전화 ${u}건 포함 ${n}건"
        else -> "부재중 전화 ${n}건"
    }
    return "$head, AI가 용건을 받아 두었습니다."
}

private val timeFmt = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/** 화면용 한 줄. from_label/summary가 비면 raw 앞 80자. */
fun itemLine(i: InboxItem, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = timeFmt.format(Instant.ofEpochMilli(i.createdAt).atZone(zone))
    val body = if (i.fromLabel.isBlank() && i.summary.isBlank()) i.raw.take(80)
    else "${i.fromLabel} / ${i.summary}"
    return "${if (i.urgent) "[급함] " else ""}$time $body"
}

/** S4: 오늘(zone 기준 날짜) 받은 통화 수. 웹훅 이벤트는 같은 통화라 세지 않는다. */
fun todayCalls(items: List<InboxItem>, today: java.time.LocalDate, zone: ZoneId = ZoneId.systemDefault()): Int =
    items.count { it.kind == "call" && Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() == today }

/** 주차 화면 순서: 급한 카드 먼저, 그 안에서 최신순. */
fun parkOrder(items: List<InboxItem>): List<InboxItem> = items.sortedWith(compareByDescending<InboxItem> { it.urgent }.thenByDescending { it.id })
