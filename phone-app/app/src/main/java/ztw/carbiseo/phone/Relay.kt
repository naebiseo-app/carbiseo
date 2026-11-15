package ztw.carbiseo.phone

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// 폰 앱의 서버 쪽 일: 알림 거르기(toMsg)·연락처 정리(normalizeContacts)·하차 응답 읽기(parseParked) 순수 함수와, 서버 호출(Relay).
// 맡지 않는 일: 알림 수신·답장 전송(Listener.kt), 권한·화면(App.kt). 순수 함수는 RelayTest가 확인한다.
// 먼저 읽을 것: toMsg(어떤 알림이 서버로 가는가), normalizeContacts(연락처의 무엇이 가는가), 맨 아래 object Relay(서버 호출 목록).
// 서버로 나가는 것: 메시지 알림의 앱 이름·보낸이·내용, 연락처 이름·휴대폰 번호, 구글 인증 코드, 끝낸 일 id. PRIVACY.md 1·3번과 같다.
// 서버 주소(SERVER_URL)와 토큰(CAR_TOKEN)은 build.gradle.kts가 BuildConfig로 넣는다. 토큰은 시연용 공용 값이다(PRIVACY.md 5번).

/** 차로 넘길 메시지 알림 1건. key = 폰 알림 키(답장할 때 이 알림의 답장 버튼을 찾는다). */
data class Msg(val app: String, val sender: String, val text: String, val replyable: Boolean, val key: String)

// 메시지 앱 이름. 그 밖의 앱도 메시지 분류(CATEGORY_MESSAGE)·메시지 스타일이면 '메시지'로 넘긴다.
private val MESSAGE_APPS = mapOf(
    "com.kakao.talk" to "카카오톡",
    "com.google.android.apps.messaging" to "문자",
    "com.samsung.android.messaging" to "문자",
    "com.android.mms" to "문자",
)

/**
 * 메시지형 알림만 Msg로. 묶음 요약·진행 중 알림·보낸이나 내용이 빈 것은 버린다.
 * 통과한 것은 앱 이름·보낸이(30자까지)·내용(500자까지)이 서버로 간다(Relay.notify). 알림 접근을 끄면 Listener가 불리지 않아 아무것도 나가지 않는다.
 * 한계: 메시지형이면 앱을 가리지 않고 모두 넘기고, 앱별로 빼는 설정은 없다 (넘으려면 설정 탭에 앱별 끄기를 둔다).
 * category의 "msg"는 Notification.CATEGORY_MESSAGE 값이다.
 */
fun toMsg(pkg: String, key: String, category: String?, messagingStyle: Boolean, summaryOrOngoing: Boolean,
          title: CharSequence?, text: CharSequence?, replyable: Boolean): Msg? {
    if (summaryOrOngoing) return null
    if (pkg !in MESSAGE_APPS && category != "msg" && !messagingStyle) return null
    val s = title?.toString()?.trim().orEmpty(); val t = text?.toString()?.trim().orEmpty()
    if (s.isEmpty() || t.isEmpty()) return null
    return Msg(MESSAGE_APPS[pkg] ?: "메시지", s.take(30), t.take(500), replyable, key)
}

/**
 * 연락처 원본 행 → 서버로 보낼 (이름, 번호). 이름 1~40자, 번호는 01로 시작하는 10~11자리(+82/82는 0으로), 같은 이름은 먼저 온 것만, 최대 3000건.
 * 휴대폰 번호 꼴이 아닌 행(유선·형식 오류)은 서버로 보내지 않는다.
 */
fun normalizeContacts(rows: List<Pair<String?, String?>>): List<Pair<String, String>> {
    val out = LinkedHashMap<String, String>()
    for ((n, p) in rows) {
        if (out.size >= 3000) break
        val name = n?.trim().orEmpty()
        var num = p.orEmpty().filter { it in '0'..'9' }
        if (num.startsWith("82")) num = "0" + num.substring(2)
        if (name.length !in 1..40 || !num.startsWith("01") || num.length !in 10..11) continue
        out.putIfAbsent(name, num)
    }
    return out.map { it.key to it.value }
}

/** 구글 캘린더 연결 상태(서버 GET /phone/google). 구글 토큰은 서버에만 있고 폰에는 두지 않는다. */
data class GoogleState(val connected: Boolean, val expired: Boolean)

const val GOOGLE_WEB_CLIENT_ID = "859657362107-5v6l2h94epvjabej05s7efl150jk0jej.apps.googleusercontent.com" // 서버용 인증 코드를 받을 때 쓰는 OAuth 웹 클라이언트 ID. 비밀이 아닌 공개값이다

/** 설정 행의 (부제, 버튼 이름). 상태를 못 읽었으면(null) 연결 안 됨으로 본다. */
fun googleLabels(s: GoogleState?): Pair<String, String> = when {
    s == null || !s.connected -> "연결 안 됨 · 연결하면 앞으로 7일 일정을 차에서 알려 줘요" to "연결"
    s.expired -> "다시 연결 필요 · 연결이 만료됐어요" to "다시 연결"
    else -> "연결됨 · 앞으로 7일 일정을 가져와요" to "연결 끊기"
}

/** 서버 GET /phone/parked 응답 본문 -> Parked. {"parked":null}·깨진 본문·좌표 없음은 null, 잘못된 할 일 항목은 버린다. */
fun parseParked(text: String): Parked? = runCatching {
    val p = JSONObject(text).optJSONObject("parked") ?: return@runCatching null
    if (!p.has("lat") || !p.has("lng")) return@runCatching null
    val a = p.optJSONArray("tasks")
    val tasks = (0 until (a?.length() ?: 0)).mapNotNull { i ->
        val t = a!!.optJSONObject(i) ?: return@mapNotNull null
        if (!t.has("id") || t.optString("title").isBlank()) null else ParkedTask(t.getLong("id"), t.getString("title"), if (t.isNull("number")) null else t.optString("number").ifBlank { null }, if (t.isNull("sub")) null else t.optString("sub").ifBlank { null })
    }
    Parked(p.getDouble("lat"), p.getDouble("lng"), if (p.isNull("address")) "" else p.optString("address"), if (p.isNull("memo")) "" else p.optString("memo"), p.optLong("at"), tasks, maxOf(tasks.size, p.optInt("total")))
}.getOrNull()

/** 응답 본문 -> 읽기 결과. {"parked":null}만 '없음'. parked 키가 없거나 읽을 수 없으면(포털 안내문 등 200 응답 포함) 실패라 저장본을 지우지 않는다. */
fun parseParkedRead(text: String): ParkedRead = runCatching {
    val o = JSONObject(text)
    if (!o.has("parked")) ParkedRead.Failed
    else if (o.isNull("parked")) ParkedRead.None
    else parseParked(text)?.let { ParkedRead.Found(it, text) } ?: ParkedRead.Failed
}.getOrDefault(ParkedRead.Failed)

/** 저장본용 원문: 번호는 폰에도 남기지 않고, 끝낸 일(done)은 목록·건수에서 뺀다. */
fun parkedCache(raw: String, done: Long? = null): String = runCatching {
    val o = JSONObject(raw); val p = o.getJSONObject("parked"); val a = p.optJSONArray("tasks") ?: return@runCatching raw
    val keep = JSONArray()
    for (i in 0 until a.length()) {
        val t = a.optJSONObject(i) ?: continue
        if (done != null && t.optLong("id") == done) p.put("total", maxOf(0, p.optInt("total") - 1)) else { t.remove("number"); keep.put(t) }
    }
    p.put("tasks", keep); o.toString()
}.getOrDefault(raw)

/**
 * 서버 호출(블로킹, 백그라운드에서만). 토큰은 차 앱과 같은 CAR_TOKEN을 Authorization: Bearer로 보낸다.
 * 연결·읽기 제한은 5초(연락처·구글은 읽기 15초). 실패는 예외 대신 false·null·빈 목록·Failed로 돌려준다.
 */
object Relay {
    private fun open(path: String, method: String) = (URL(BuildConfig.SERVER_URL + path).openConnection() as HttpURLConnection).apply {
        requestMethod = method; connectTimeout = 5000; readTimeout = 5000
        setRequestProperty("Authorization", "Bearer ${BuildConfig.CAR_TOKEN}")
    }

    /** 메시지 알림 1건을 서버로. 앱 이름·보낸이·내용·답장 가능 여부·알림 키를 보낸다. 2xx면 true. */
    fun notify(m: Msg): Boolean = runCatching {
        val c = open("/phone/notify", "POST")
        c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(JSONObject().put("app", m.app).put("sender", m.sender).put("text", m.text).put("replyable", m.replyable).put("key", m.key).toString().toByteArray()) }
        (c.responseCode in 200..299).also { c.disconnect() }
    }.getOrDefault(false)

    /** 연락처(이름·휴대폰 번호) 통째로 교체. 빈 목록이면 서버가 저장분을 지운다. 많을 수 있어 읽기 제한만 15초. */
    fun contacts(list: List<Pair<String, String>>): Boolean = runCatching {
        val c = open("/phone/contacts", "POST")
        c.readTimeout = 15000
        c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
        val a = org.json.JSONArray().also { arr -> list.forEach { arr.put(JSONObject().put("name", it.first).put("number", it.second)) } }
        c.outputStream.use { it.write(JSONObject().put("contacts", a).toString().toByteArray()) }
        (c.responseCode in 200..299).also { c.disconnect() }
    }.getOrDefault(false)

    private fun google(method: String) = open("/phone/google", method).apply { readTimeout = 15000 } // 연결은 서버가 구글과 두 번 오가서 길게

    /** 서버가 가진 구글 연결 상태(연결 여부·만료 여부). 못 읽으면 null. */
    fun googleState(): GoogleState? = runCatching {
        val c = google("GET")
        try {
            if (c.responseCode !in 200..299) return@runCatching null
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            GoogleState(o.optBoolean("connected"), o.optBoolean("expired"))
        } finally { c.disconnect() }
    }.getOrNull()

    /** 구글이 폰에 준 인증 코드를 서버로. 서버가 토큰으로 바꾼다. */
    fun googleConnect(code: String): Boolean = runCatching {
        val c = google("POST")
        c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(JSONObject().put("code", code).toString().toByteArray()) }
        (c.responseCode in 200..299).also { c.disconnect() }
    }.getOrDefault(false)

    /** 연결 끊기. 서버가 구글에 토큰 폐기를 요청하고 데모 일정으로 되돌린다. */
    fun googleDisconnect(): Boolean = runCatching {
        val c = google("DELETE")
        (c.responseCode in 200..299).also { c.disconnect() }
    }.getOrDefault(false)

    /** 차에서 만든 답장들 (알림 key, 내용). 서버는 준 것을 delivered로 바꾼다. */
    fun replies(): List<Pair<String, String>> = runCatching {
        val c = open("/phone/replies", "GET")
        try {
            if (c.responseCode !in 200..299) return@runCatching emptyList()
            val a = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("items")
            (0 until a.length()).map { a.getJSONObject(it) }.map { it.optString("key") to it.optString("text") }
        } finally { c.disconnect() }
    }.getOrDefault(emptyList())

    /** 차가 올린 하차 정보. 없음(None)과 실패(Failed)를 구분한다. */
    fun parked(): ParkedRead = runCatching {
        val c = open("/phone/parked", "GET")
        try { if (c.responseCode !in 200..299) ParkedRead.Failed else parseParkedRead(c.inputStream.bufferedReader().readText()) } finally { c.disconnect() }
    }.getOrDefault(ParkedRead.Failed)

    /** 하차 카드의 할 일을 폰에서 끝냄(서버가 차에 전달하고, 서버 행이면 완료 처리). */
    fun parkedDone(id: Long): Boolean = runCatching {
        val c = open("/phone/parked/done", "POST")
        c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(JSONObject().put("id", id).toString().toByteArray()) }
        (c.responseCode in 200..299).also { c.disconnect() }
    }.getOrDefault(false)
}
