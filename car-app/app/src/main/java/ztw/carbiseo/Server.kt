package ztw.carbiseo

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** 서버 호출. 블로킹이라 반드시 백그라운드 스레드에서 부른다. 실패는 false/null. */
object Server {
    private fun open(path: String, method: String) = (URL(BuildConfig.SERVER_URL + path).openConnection() as HttpURLConnection).apply {
        requestMethod = method; connectTimeout = 5000; readTimeout = 5000
        setRequestProperty("Authorization", "Bearer ${BuildConfig.CAR_TOKEN}")
    }

    private fun post(path: String, body: JSONObject): Boolean = runCatching {
        val c = open(path, "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        (c.responseCode in 200..299).also { c.disconnect() }
    }.getOrDefault(false)

    fun postState(room: String, etaMin: Int?, ctx: Map<String, Any?> = emptyMap()) =
        post("/car/state", JSONObject().put("room", room).put("eta_min", etaMin ?: JSONObject.NULL).also { j -> ctx.forEach { (k, v) -> j.put(k, v ?: JSONObject.NULL) } })

    fun postSettings(ownerLabel: String, assistantName: String) =
        post("/car/settings", JSONObject().put("owner_label", ownerLabel).put("assistant_name", assistantName))

    fun postMode(mode: String) = post("/car/settings", JSONObject().put("mode", mode))

    /** 확인된 문자 발송. 결과 code(queued·sent·overseas_ip_blocked 등), 네트워크 실패면 null. */
    fun send(label: String, number: String, body: String): String? = runCatching {
        val c = open("/car/send", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(JSONObject().put("to_label", label).put("to_number", number).put("body", body).put("confirmed", true).toString().toByteArray()) }
        try {
            val s = (if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()
            JSONObject(s).let { if (it.optBoolean("ok")) "ok" else it.str("code").ifEmpty { it.str("error") } }
        } finally { c.disconnect() }
    }.getOrNull()

    /** JSON 한 번 보내고 응답 JSON(오류 본문 포함). 네트워크 실패면 null. */
    private fun postJson(path: String, body: JSONObject): JSONObject? = runCatching {
        val c = open(path, "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        try { JSONObject((if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()) } finally { c.disconnect() }
    }.getOrNull()

    /** H1 가족 번호 저장(숫자 10~11, ""=삭제). 성공하면 가려진 표시("****1234", 삭제면 ""), 실패면 null. 번호는 서버에만 둔다. */
    fun postFamilyNumber(number: String): String? =
        postJson("/car/settings", JSONObject().put("family_number", number))?.takeIf { it.optBoolean("ok") }?.optJSONObject("settings")?.str("family_label")

    /** H1 집 문턱: 가족에게 도착 예상 문자(서버가 번호 없으면 skipped). 결과 문자열은 로그용. */
    fun postFamilyEta(etaMin: Int): String =
        postJson("/car/family-eta", JSONObject().put("eta_min", etaMin))?.let { if (it.optBoolean("skipped")) "skipped" else if (it.optBoolean("ok")) "ok" else it.str("code").ifEmpty { it.str("error") } } ?: "FAIL"

    /** (새 항목, 서버 최소 id). 최소 id: 서버가 비었으면 Long.MAX_VALUE, 옛 서버(값 없음)면 null. */
    fun fetchInbox(since: Long): Pair<List<InboxItem>, Long?>? = runCatching {
        val c = open("/car/inbox?since=$since", "GET")
        try {
            if (c.responseCode !in 200..299) return@runCatching null
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val minId = if (!j.has("min_id")) null else if (j.isNull("min_id")) Long.MAX_VALUE else j.getLong("min_id")
            val arr = j.getJSONArray("items")
            (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                val meta = runCatching { JSONObject(it.str("meta")) }.getOrNull()
                InboxItem(it.getLong("id"), it.str("from_label"), it.str("summary"),
                    it.optInt("urgent") != 0, it.optLong("created_at"), it.str("raw"), it.str("kind").ifEmpty { "call" },
                    it.str("status"), meta?.str("code") ?: "", meta?.str("car") ?: "", it.str("room"), it.str("from_number"),
                    meta?.str("app") ?: "", meta?.optBoolean("replyable") == true)
            } to minId
        } finally { c.disconnect() }
    }.getOrNull()

    /** 음성으로 확인된 알림 답장 → 서버 대기열(폰 연결 앱이 가져가 보낸다). */
    fun reply(notifId: Long, text: String) = post("/car/reply", JSONObject().put("notif_id", notifId).put("text", text).put("confirmed", true))

    /** H13: 착신 서비스 번호(서버 환경값, 화면 안내용)와 실제 착신 통화를 받은 적 있는지. */
    fun fetchConfig(): Pair<String, Boolean>? = runCatching {
        val c = open("/car/config", "GET")
        try {
            if (c.responseCode !in 200..299) return@runCatching null
            JSONObject(c.inputStream.bufferedReader().readText()).let { it.str("service_number") to it.optBoolean("forwarded") }
        } finally { c.disconnect() }
    }.getOrNull()

    fun fetchSchedule(): List<Event>? = runCatching {
        val c = open("/car/schedule", "GET")
        try {
            if (c.responseCode !in 200..299) return@runCatching null
            val arr = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("items")
            (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                Event(it.str("title"), it.getLong("start"), it.str("place"), LatLng(it.getDouble("lat"), it.getDouble("lng")), it.str("contact"))
            }
        } finally { c.disconnect() }
    }.getOrNull()
}

// JSON null을 "null" 글자가 아니라 빈 문자열로
private fun JSONObject.str(k: String) = if (isNull(k)) "" else optString(k)
