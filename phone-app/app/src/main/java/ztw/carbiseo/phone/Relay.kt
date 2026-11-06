package ztw.carbiseo.phone

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 차로 넘길 메시지 알림 1건. key = 폰 알림 키(답장할 때 이 알림의 답장 버튼을 찾는다). */
data class Msg(val app: String, val sender: String, val text: String, val replyable: Boolean, val key: String)

// 메시지 앱 이름. 그 밖의 앱도 메시지 분류(CATEGORY_MESSAGE)·메시지 스타일이면 '메시지'로 넘긴다.
private val MESSAGE_APPS = mapOf(
    "com.kakao.talk" to "카카오톡",
    "com.google.android.apps.messaging" to "문자",
    "com.samsung.android.messaging" to "문자",
    "com.android.mms" to "문자",
)

/** 메시지형 알림만 Msg로. 묶음 요약·진행 중 알림·보낸이나 내용이 빈 것은 버린다. */
fun toMsg(pkg: String, key: String, category: String?, messagingStyle: Boolean, summaryOrOngoing: Boolean,
          title: CharSequence?, text: CharSequence?, replyable: Boolean): Msg? {
    if (summaryOrOngoing) return null
    if (pkg !in MESSAGE_APPS && category != "msg" && !messagingStyle) return null
    val s = title?.toString()?.trim().orEmpty(); val t = text?.toString()?.trim().orEmpty()
    if (s.isEmpty() || t.isEmpty()) return null
    return Msg(MESSAGE_APPS[pkg] ?: "메시지", s.take(30), t.take(500), replyable, key)
}

/** 연락처 원본 행 → 서버로 보낼 (이름, 번호). 이름 1~40자, 번호는 01로 시작하는 10~11자리(+82/82는 0으로), 같은 이름은 먼저 온 것만, 최대 3000건. */
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

const val GOOGLE_WEB_CLIENT_ID = "859657362107-5v6l2h94epvjabej05s7efl150jk0jej.apps.googleusercontent.com" // 공개값

/** 설정 행의 (부제, 버튼 이름). 상태를 못 읽었으면(null) 연결 안 됨으로 본다. */
fun googleLabels(s: GoogleState?): Pair<String, String> = when {
    s == null || !s.connected -> "연결 안 됨 · 연결하면 앞으로 7일 일정을 차에서 알려 줘요" to "연결"
    s.expired -> "다시 연결 필요 · 연결이 만료됐어요" to "다시 연결"
    else -> "연결됨 · 앞으로 7일 일정을 가져와요" to "연결 끊기"
}

/** 서버 호출(블로킹, 백그라운드에서만). 토큰은 차 앱과 같은 CAR_TOKEN. */
object Relay {
    private fun open(path: String, method: String) = (URL(BuildConfig.SERVER_URL + path).openConnection() as HttpURLConnection).apply {
        requestMethod = method; connectTimeout = 5000; readTimeout = 5000
        setRequestProperty("Authorization", "Bearer ${BuildConfig.CAR_TOKEN}")
    }

    fun notify(m: Msg): Boolean = runCatching {
        val c = open("/phone/notify", "POST")
        c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(JSONObject().put("app", m.app).put("sender", m.sender).put("text", m.text).put("replyable", m.replyable).put("key", m.key).toString().toByteArray()) }
        (c.responseCode in 200..299).also { c.disconnect() }
    }.getOrDefault(false)

    /** 연락처 통째로 교체. 빈 목록이면 서버가 저장분을 지운다. 많을 수 있어 읽기 제한만 15초. */
    fun contacts(list: List<Pair<String, String>>): Boolean = runCatching {
        val c = open("/phone/contacts", "POST")
        c.readTimeout = 15000
        c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
        val a = org.json.JSONArray().also { arr -> list.forEach { arr.put(JSONObject().put("name", it.first).put("number", it.second)) } }
        c.outputStream.use { it.write(JSONObject().put("contacts", a).toString().toByteArray()) }
        (c.responseCode in 200..299).also { c.disconnect() }
    }.getOrDefault(false)

    private fun google(method: String) = open("/phone/google", method).apply { readTimeout = 15000 } // 연결은 서버가 구글과 두 번 오가서 길게

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
}
