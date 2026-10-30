package ztw.carbiseo

// 폰 알림 읽어주기(35 설계). 서버 inbox kind='notif' = 폰 메시지 알림(app·보낸이·내용·답장 가능).

/** 설정 문자열 "엄마, 아빠" → 이름 집합. */
fun parseFavs(s: String) = s.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

/** 광고성: 앱·보낸이 이름에 '광고', 또는 본문이 '(광고)'로 시작(정보통신망법 표기). ponytail: 표기만 본다, 스팸 판정은 안 한다. */
fun isAd(i: InboxItem) = "광고" in i.app || "광고" in i.fromLabel || i.summary.trimStart().startsWith("(광고)")

/** 지금 바로 알릴 알림. 업무 방 = 광고 외 전부, 쉼 방 = 즐겨찾기 발신자만(나머지는 정차 요약), 정차·판정 전 = 없음(카드). */
fun notifNow(items: List<InboxItem>, room: Room?, favs: Set<String>): List<InboxItem> = items.filter { it.kind == "notif" && !isAd(it) }.filter {
    when (room) { Room.DRIVE_WORK -> true; Room.DRIVE_REST -> it.fromLabel in favs; else -> false }
}

private fun appShort(a: String) = when {
    a.contains("카카오") || a.contains("카톡") || a.contains("kakao", true) -> "카톡"
    a.contains("문자") || a.contains("메시지") || a.contains("message", true) -> "문자"
    else -> a
}

/** 즐겨찾기(가족)는 '엄마', 그 밖은 '김부장님'. */
private fun who(s: String, favs: Set<String>) = if (s in favs) s else "${s}님"

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
    items.take(5).joinToString(" ") { "${who(it.fromLabel, favs)}, ${appShort(it.app)}. ${it.summary.take(200).let { t -> if (t.isEmpty() || t.last() in ".?!") t else "$t." }}" } // 문장 끝 쉼(TTS 억양)

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
        listOf("아니", "취소", "하지마", "보내지마", "됐어").any { it in n } -> Cmd.No
        "나중" in n || "이따" in n -> Cmd.Later
        "읽어" in n -> Cmd.Read
        listOf("응", "네", "예", "그래", "좋아", "보내", "오케이").any { n.startsWith(it) } -> Cmd.Yes
        else -> Cmd.Unknown
    }
}

/** 한 턴의 결과: 말할 문장, 이어서 들을지, 서버에 보낼 답장(알림 id, 내용). */
data class Turn(val say: String?, val listen: Boolean = false, val send: Pair<Long, String>? = null)

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

    fun hear(said: String?, favs: Set<String>): Turn {
        val c = voiceCmd(said)
        val st = step; step = Step.NONE
        return when {
            st == Step.CONFIRM -> if (c == Cmd.Yes) Turn("보낼게요.", send = target!!.id to draft) else Turn("답장을 취소했어요.")
            c is Cmd.Reply && st != Step.NONE -> confirm(c.text)
            st == Step.OFFER && c == Cmd.Read -> {
                val more = items.any { it.replyable }
                step = if (more) Step.AFTER_READ else Step.NONE
                Turn(readAloud(items, favs) + if (more) " 답장하시려면 '답장' 하고 말씀하세요." else "", listen = more)
            }
            st == Step.OFFER -> Turn("화면에 남겨 둘게요.")
            else -> Turn(null)
        }
    }

    private fun confirm(text: String): Turn {
        val t = items.lastOrNull { it.replyable } ?: return Turn("이 알림은 답장할 수 없어요. 화면에 남겨 둘게요.")
        target = t; draft = text; step = Step.CONFIRM
        return Turn("${t.fromLabel}님께 '$text' 보낼까요?", listen = true)
    }

    fun cancel() { step = Step.NONE }
}
