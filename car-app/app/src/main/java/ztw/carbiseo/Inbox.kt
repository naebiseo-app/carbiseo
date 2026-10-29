package ztw.carbiseo

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 서버 /car/inbox 한 행(쓰는 칼럼만). */
data class InboxItem(
    val id: Long, val fromLabel: String, val summary: String,
    val urgent: Boolean, val createdAt: Long, val raw: String,
    val kind: String = "call", // "call" = 통화 1건, "event" = 같은 통화의 웹훅(요약·녹음 완료 등)
)

/** 마지막으로 본 id. 항목이 없으면 그대로. */
fun nextSince(since: Long, items: List<InboxItem>): Long = maxOf(since, items.maxOfOrNull { it.id } ?: since)

/** 새 항목 -> 읽을 한 문장. PARK(또는 방 미판정)이거나 항목이 없으면 null. */
fun speechFor(items: List<InboxItem>, room: Room?): String? {
    val newItems = items.filter { it.kind == "call" } // 웹훅 이벤트는 같은 통화라 세지 않는다
    if (newItems.isEmpty() || (room != Room.DRIVE_WORK && room != Room.DRIVE_REST)) return null
    val kind = if (newItems.any { it.urgent }) "급한 전화" else "부재중 전화"
    val count = if (newItems.size == 1) "한 건" else "${newItems.size}건"
    return "$kind $count, AI가 용건을 받아 두었습니다."
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
