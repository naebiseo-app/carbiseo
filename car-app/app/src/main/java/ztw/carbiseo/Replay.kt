package ztw.carbiseo

/** O3 재현 통화: 서버가 내려 준 대본의 한 줄. who = ai(고지)·peer(상대)·driver(운전자, 자막만)·hint(도움: 추천 답변·사실 정정·빈 시간). t = 시작 후 ms. */
data class ReplayLine(val t: Long, val who: String, val label: String, val text: String)
data class ReplayScript(val title: String, val endMs: Long, val lines: List<ReplayLine>)

/** 서버 형식(JSON 아님): 첫 줄 "제목\t끝ms", 다음 줄부터 "t\twho\tlabel\t문장". 형식이 틀리면 null, 모르는 줄은 건너뛴다. */
fun parseReplay(raw: String): ReplayScript? {
    val rows = raw.split("\n")
    val head = rows.firstOrNull()?.split("\t")?.takeIf { it.size == 2 } ?: return null
    val end = head[1].toLongOrNull() ?: return null
    val lines = rows.drop(1).mapNotNull { r ->
        r.split("\t", limit = 4).takeIf { it.size == 4 && it[1] in REPLAY_WHO && it[3].isNotBlank() }
            ?.let { p -> p[0].toLongOrNull()?.let { ReplayLine(it, p[1], p[2], p[3]) } }
    }.sortedBy { it.t }
    return if (lines.isEmpty()) null else ReplayScript(head[0], end, lines)
}
private val REPLAY_WHO = setOf("ai", "peer", "driver", "hint")

/** 진행: 경과 시간마다 아직 안 낸 줄을 순서대로 한 번씩 내준다(틱이 밀려도 건너뛰지 않음). */
class ReplayRun(val script: ReplayScript) {
    private var next = 0
    fun due(elapsedMs: Long): List<ReplayLine> {
        val out = script.lines.drop(next).takeWhile { it.t <= elapsedMs }
        next += out.size
        return out
    }
    fun done(elapsedMs: Long) = next >= script.lines.size && elapsedMs >= script.endMs
}

/** 통화 중 화면 상태: 최근 자막 한 줄 + 정차 중에 뜬 도움 카드. */
data class ReplayView(val title: String, val caption: ReplayLine? = null, val hint: ReplayLine? = null)
data class ReplayAct(val view: ReplayView, val speech: String?)

/**
 * 한 줄이 나올 때 할 일. 상대·AI 고지는 읽고 자막, 운전자 대사는 자막만.
 * 도움(hint): 정차(또는 방 판정 전)면 화면 카드(읽지 않음), 운전 중이면 카드 없이 귓속말 한 줄 TTS. 상대가 다시 말하면 카드는 내린다.
 * 동승자 모드면 아무것도 읽지 않는다(자막·카드만). 경고·깜빡이 중 보류는 호출자가 speech를 SpeechHold(say)에 넘겨 처리한다.
 */
fun replayAct(v: ReplayView, line: ReplayLine, room: Room?, passenger: Boolean): ReplayAct {
    val parked = room == null || room == Room.PARK
    fun say(s: String) = if (passenger) null else s
    return when (line.who) {
        "hint" -> if (parked) ReplayAct(v.copy(hint = line), null) else ReplayAct(v, say(sentences(line.label, line.text)))
        "peer" -> ReplayAct(v.copy(caption = line, hint = null), say(line.text))
        "ai" -> ReplayAct(v.copy(caption = line), say(line.text))
        else -> ReplayAct(v.copy(caption = line), null)
    }
}
