package ztw.carbiseo

// 폰 알림 읽어주기(35 설계). 서버 inbox kind='notif' = 폰 메시지 알림(app·보낸이·내용·답장 가능).

private const val KO_DIGITS = "공일이삼사오육칠팔구"
// 번호 맥락만: ****1234·끝자리 6000·010-1234-5678·01012345678. 시각(15:30)·분(25분)·금액(5000원)은 걸리지 않는다.
private val NUMBER_CTX = Regex("""[*●•]+\s*\d{3,}(?:-\d+)*(?!\d)|(?:끝자리|끝번호|끝|뒷자리|번호|전화)\s*\d{3,}(?:-\d+)*(?!\d)(?!\s*[건통개원분명시초%])|(?<![\d-])0\d{1,2}-\d{3,4}-\d{4}(?!\d)|(?<![\d-])\d{3,4}-\d{4}(?!\d)|(?<!\d)0\d{9,10}(?!\d)""")

/** TTS 직전 한 곳(Speaker.speak): 번호 맥락의 3자리 이상 숫자는 '육천'이 아니라 '육 공 공 공'처럼 한 자리씩. */
fun spellDigits(text: String): String = NUMBER_CTX.replace(text) { m ->
    Regex("""\d+""").replace(m.value.replace("****", "끝자리 ").replace('-', ' ')) { d -> d.value.map { KO_DIGITS[it - '0'] }.joinToString(" ") }
}

private val DEADLINE = Regex("""(오전|오후)?\s*(\d{1,2})\s*(?::|시)\s*(\d{1,2})?\s*분?\s*(?:까지|전)""")
private val REPLY_ASK = Regex("""연락\s*(?:좀\s*)?(?:주|부탁|바라|달라)|회신|콜백|전화\s*(?:좀\s*)?(?:주|부탁|바라|달라)|답장""")

/** 통화 카드에 시간 기한이나 회신 요청이 있고 발신자 이름·번호가 있으면 먼저 보낼 문자 내용, 아니면 null. 이름이 번호 모양이면 호칭으로 못 쓴다. */
fun callbackMessage(i: InboxItem): String? {
    if (i.kind != "call" || i.fromLabel.isBlank() || i.fromNumber.isBlank() || Regex("""\d{3}""").containsMatchIn(i.fromLabel)) return null
    val t = i.summary.ifBlank { i.raw }
    val hhmm = DEADLINE.find(t)?.let { m ->
        // 오전/오후가 없으면 1~7시는 업무 시간 기준 오후(13~19시). 0·8~12시·13시 이상은 그대로.
        val h = m.groupValues[2].toInt().let { if (m.groupValues[1] == "오후" && it < 12 || m.groupValues[1].isEmpty() && it in 1..7) it + 12 else it }; val min = m.groupValues[3].ifEmpty { "0" }.toInt()
        if (h in 0..23 && min in 0..59) koClock(h, min) else null
    }
    if (hhmm == null && !REPLY_ASK.containsMatchIn(t)) return null
    return "확인하고 ${hhmm?.let { "$it 전에 " } ?: "곧 "}연락드리겠습니다"
}
/** 문자·낭독용 "오후 3시 30분"(분이 0이면 "오후 3시"). */
fun koClock(h: Int, min: Int) = "${if (h < 12) "오전" else "오후"} ${if (h % 12 == 0) 12 else h % 12}시${if (min > 0) " ${min}분" else ""}"
fun callbackAsk(i: InboxItem, msg: String) = "${i.fromLabel}님께 '$msg'라고 문자 먼저 보내 드릴까요?"
/** 할 일 카드 본문(taskDraft 형식: 번호 + 내용). */
fun callbackTask(i: InboxItem, msg: String) = "${i.fromLabel}님께 회신 문자 ${i.fromNumber} / 내용: $msg"
const val CALLBACK_DONE_SPEECH = "할 일에 문자를 준비해 뒀어요. 보내기를 누르면 바로 갑니다."

/** 설정 문자열 "엄마, 아빠" → 이름 집합. */
fun parseFavs(s: String) = s.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

/** 광고성: 앱·보낸이 이름에 '광고', 또는 본문이 '(광고)'로 시작(정보통신망법 표기). ponytail: 표기만 본다, 스팸 판정은 안 한다. */
fun isAd(i: InboxItem) = "광고" in i.app || "광고" in i.fromLabel || i.summary.trimStart().startsWith("(광고)")

/** 지금 바로 알릴 알림. 업무 방 = 광고 외 전부, 쉼 방 = 즐겨찾기 발신자만(나머지는 정차 요약), 판정 전 = 없음(카드).
 *  정차(P)도 음성으로 묻는다(서서 화면 보다 신호 놓치지 않게): 퇴근 시각(off)이면 쉼 규칙, 아니면 업무 규칙. */
fun notifNow(items: List<InboxItem>, room: Room?, favs: Set<String>, off: Boolean = false): List<InboxItem> = items.filter { it.kind == "notif" && !isAd(it) }.filter {
    when (room) { Room.DRIVE_WORK -> true; Room.DRIVE_REST -> it.fromLabel in favs; Room.PARK -> !off || it.fromLabel in favs; null -> false }
}

private fun appShort(a: String) = when {
    a.contains("카카오") || a.contains("카톡") || a.contains("kakao", true) -> "카톡"
    a.contains("문자") || a.contains("메시지") || a.contains("message", true) -> "문자"
    else -> a
}

/** 즐겨찾기(가족)는 '엄마', 그 밖은 '김부장님'. */
private fun who(s: String, favs: Set<String>) = if (s in favs) spoken(s) else "${spoken(s)}님"
/** 읽을 이름: 이모지·기호(❤ 등)는 TTS가 이상하게 읽으니 뺀다. */
fun spoken(s: String) = s.replace(Regex("""[^\p{L}\p{N}\s]"""), "").trim().ifEmpty { s }
/** 답장 확인 호칭: 딸 → 따님, 아들 → 아드님, 그 밖은 이름+님. */
fun honor(s: String) = spoken(s).let { n -> when { "딸" in n -> "따님"; "아들" in n -> "아드님"; else -> "${n}님" } }

/** "카톡 3건, 김부장님 외 2명. 읽어드릴까요?" 앱이 섞이면 '알림 N건'. */
fun notifSpeech(items: List<InboxItem>, favs: Set<String>): String? {
    if (items.isEmpty()) return null
    val apps = items.map { appShort(it.app) }.distinct()
    val senders = items.map { it.fromLabel }.distinct()
    val head = "${apps.singleOrNull() ?: "알림"} ${items.size}건"
    val names = who(senders.first(), favs) + if (senders.size > 1) " 외 ${senders.size - 1}명" else ""
    return "$head, $names. 읽어드릴까요?"
}

/** 낭독: 보낸이·앱·내용. 최대 5건(길면 운전에 방해). */
fun readAloud(items: List<InboxItem>, favs: Set<String>): String =
    sentences(*items.take(5).flatMap { listOf("${who(it.fromLabel, favs)}, ${appShort(it.app)}", it.summary.take(200)) }.toTypedArray())

sealed interface Cmd {
    object Read : Cmd; object Later : Cmd; object Yes : Cmd; object No : Cmd; object Unknown : Cmd
    data class Reply(val text: String) : Cmd
}

// "답장, 지금 운전 중이라고 해줘" → "지금 운전 중". '라고/고 + 해줘·보내줘·전해줘'를 떼어 낸다.
private val REPLY = Regex("""답장\s*[,，]?\s*(.*?)\s*(?:(?:이?라고|고)\s*(?:해|보내|전해)\s*(?:줘|주세요|줄래)?)?\s*[.!?]?$""")

/** 음성 한 마디 → 의도. 간단 키워드(순서 중요: 답장 > 부정 > 나중 > 읽기 > 긍정). */
fun voiceCmd(said: String?): Cmd {
    val s = said?.trim().orEmpty()
    if (s.isEmpty()) return Cmd.Unknown
    if ("답장" in s) return REPLY.find(s)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { Cmd.Reply(it) } ?: Cmd.Unknown
    val n = s.replace(Regex("""[\s.,!?]"""), "")
    return when {
        listOf("아니", "취소", "하지마", "보내지마", "됐어", "닫", "그만").any { it in n } -> Cmd.No
        "나중" in n || "이따" in n -> Cmd.Later
        "읽어" in n -> Cmd.Read
        listOf("응", "네", "예", "그래", "좋아", "보내", "오케이").any { n.startsWith(it) } -> Cmd.Yes
        else -> Cmd.Unknown
    }
}

/** 응/아니 대답만 본다. 그 밖의 말·못 들음은 null. */
fun yesNo(said: String?): Boolean? = when (voiceCmd(said)) { Cmd.Yes -> true; Cmd.No -> false; else -> null }

/** 한 턴의 결과: 말할 문장, 이어서 들을지, 서버에 보낼 답장(알림 id, 내용), 다 읽어 준 알림 id(카드에서 뺀다). */
data class Turn(val say: String?, val listen: Boolean = false, val send: Pair<Long, String>? = null, val done: List<Long> = emptyList())

/** 화면 카드·건수: 읽어 준(답장은 읽은 뒤라 포함) 폰 알림은 뺀다. '나중에'는 "화면에 남겨 둘게요"라 남는다. */
fun unread(items: List<InboxItem>, done: Set<Long>) = items.filter { it.kind != "notif" || it.id !in done }

/** 알림 대화: 제안 → (읽어줘) 낭독 → (답장 …) 되읽기 → (응) 대기열. 그 밖의 대답은 카드로 남긴다. */
class NotifTalk {
    enum class Step { NONE, OFFER, AFTER_READ, CONFIRM }
    var step = Step.NONE; private set
    private var items = listOf<InboxItem>()
    private var target: InboxItem? = null
    private var draft = ""

    fun offer(list: List<InboxItem>, favs: Set<String>): Turn? {
        val s = notifSpeech(list, favs) ?: return null
        items = list; step = Step.OFFER
        return Turn(s, listen = true)
    }

    /** parked: P에서는 못 들었거나 모르는 말이면 묻는 바를 그대로 둔다(누르거나 출발할 때까지). 주행 중엔 카드로. */
    fun hear(said: String?, favs: Set<String>, parked: Boolean = false): Turn {
        val c = voiceCmd(said)
        if (parked && c == Cmd.Unknown && (step == Step.OFFER || step == Step.AFTER_READ)) return Turn(null)
        val st = step; step = Step.NONE
        return when {
            st == Step.CONFIRM -> if (c == Cmd.Yes) Turn("보낼게요.", send = target!!.id to draft) else Turn("답장을 취소했어요.")
            c is Cmd.Reply && st != Step.NONE -> confirm(c.text)
            st == Step.OFFER && c == Cmd.Read -> {
                val more = items.any { it.replyable }
                step = if (more) Step.AFTER_READ else Step.NONE
                Turn(sentences(readAloud(items, favs), if (more) "답장하시려면 '답장' 하고 말씀하세요" else null), listen = more, done = items.take(5).map { it.id })
            }
            st == Step.OFFER -> Turn("화면에 남겨 둘게요.")
            else -> Turn(null)
        }
    }

    private fun confirm(text: String): Turn {
        val t = items.lastOrNull { it.replyable } ?: return Turn("이 알림은 답장할 수 없어요. 화면에 남겨 둘게요.")
        target = t; draft = text; step = Step.CONFIRM
        return Turn("${honor(t.fromLabel)}께 '$text' 보낼까요?", listen = true)
    }

    fun cancel() { step = Step.NONE }

    /** 묻기만 해 두고 대답이 없는 상태(P에서 바를 그대로 둔 경우). 이대로면 새 알림을 음성으로 못 묻는다. */
    val waiting get() = step == Step.OFFER || step == Step.AFTER_READ

    /** 무응답 묻기를 닫고(NONE), 아직 안 읽어 준 알림(묻기 단계만)을 돌려준다 — 새 알림과 합쳐 다시 묻는 용도. */
    fun dropStale(): List<InboxItem> = (if (step == Step.OFFER) items else emptyList()).also { step = Step.NONE }
}

/** 알림 바 (문구, 버튼). 낭독 뒤 P(touch)에서 듣기가 끝나면 '답장하기'(다시 듣기)·'닫기'로 무엇을 기다리는지 보인다. 주행 중 문구는 그대로. */
fun askBar(step: NotifTalk.Step, listening: Boolean, touch: Boolean): Pair<String, List<String>> {
    if (step == NotifTalk.Step.AFTER_READ && touch) return (if (listening) "듣고 있어요 · ‘답장, ○○라고 해 줘’ / ‘닫기’"
        else "답장하시려면 ‘답장하기’를 누르고 말씀하세요") to listOf("답장하기", "닫기")
    val opts = when (step) { NotifTalk.Step.OFFER -> listOf("읽어줘", "나중에"); NotifTalk.Step.CONFIRM -> listOf("보내", "취소"); else -> listOf("나중에") }
    return (if (listening) "듣고 있어요 · ${opts.joinToString(" / ") { "‘$it’" }}${if (step == NotifTalk.Step.AFTER_READ) " / ‘답장, ○○라고 해 줘’" else ""}"
        else "폰 알림 · 대답을 기다려요") to opts
}
