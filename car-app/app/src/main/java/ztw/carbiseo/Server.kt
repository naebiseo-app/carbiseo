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

    fun postOwner(ownerLabel: String) = post("/car/settings", JSONObject().put("owner_label", ownerLabel)) // 첫 실행: 비서 이름은 안 건드린다

    fun postCardAction(id: Long, action: String) = post("/car/inbox/$id", JSONObject().put("action", action))
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
    private fun postJson(path: String, body: JSONObject, readMs: Int = 5000): JSONObject? = runCatching {
        val c = open(path, "POST")
        c.readTimeout = readMs
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        try { JSONObject((if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()) } finally { c.disconnect() }
    }.getOrNull()

    /** 말동무: 못 알아들은 말을 AI에 묻는다. 결과 = (답, source: llm·fallback·no_key 등). 실패·답 없음이면 null. */
    fun chat(text: String, history: List<Pair<String, String>>): Pair<String, String>? =
        postJson("/car/chat", JSONObject().put("text", text).put("history", org.json.JSONArray().also { a -> history.forEach { (r, t) -> a.put(JSONObject().put("role", r).put("text", t)) } }), readMs = 8000) // 서버 쪽 AI 제한 6초 + 여유
            ?.let { j -> j.str("reply").takeIf { it.isNotBlank() }?.let { it to j.str("source") } }

    /** H1 가족 번호 저장(숫자 10~11, ""=삭제). 성공하면 가려진 표시("****1234", 삭제면 ""), 실패면 null. 번호는 서버에만 둔다. */
    fun postFamilyNumber(number: String): String? =
        postJson("/car/settings", JSONObject().put("family_number", number))?.takeIf { it.optBoolean("ok") }?.optJSONObject("settings")?.str("family_label")

    /** 바로 연결 번호 목록 통째 교체(각 숫자 10~11자리·최대 10개, []=삭제). 성공하면 가려진 표시 목록, 실패 null. 원본은 서버에만 둔다. */
    fun postDirectNumbers(numbers: List<String>): List<String>? =
        postJson("/car/settings", JSONObject().put("direct_numbers", org.json.JSONArray(numbers)))?.takeIf { it.optBoolean("ok") }
            ?.let { j -> j.optJSONObject("settings")?.optJSONArray("direct_labels")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList() }

    /** 응대 멘트 저장(1~60자, ""=기본 문구). 결과 = (서버가 정리한 값("" = 기본 문구), 오류). 오류는 "bad ment_text"(400)·"network" 등. */
    fun postMent(text: String): Pair<String?, String?> {
        val j = postJson("/car/settings", JSONObject().put("ment_text", text)) ?: return null to "network"
        return if (j.optBoolean("ok")) (j.optJSONObject("settings")?.str("ment_text") ?: "") to null else null to j.str("error")
    }

    /** H1 집 문턱: 가족에게 도착 예상 문자(서버가 번호 없으면 skipped). 결과 문자열은 로그용. */
    fun postFamilyEta(etaMin: Int): String =
        postJson("/car/family-eta", JSONObject().put("eta_min", etaMin))?.let { if (it.optBoolean("skipped")) "skipped" else if (it.optBoolean("ok")) "ok" else it.str("code").ifEmpty { it.str("error") } } ?: "FAIL"

    /** O2 확인된 메일 발송. 결과는 send와 같은 규칙("ok" 또는 코드, 네트워크 실패면 null). */
    fun mail(to: String, subject: String, body: String): String? =
        postJson("/car/mail", JSONObject().put("to", to).put("subject", subject).put("body", body).put("confirmed", true))
            ?.let { if (it.optBoolean("ok")) "ok" else it.str("code").ifEmpty { it.str("error") } }

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
                    meta?.str("app") ?: "", meta?.optBoolean("replyable") == true, meta?.optBoolean("direct") == true, meta?.optLong("replaces", 0L) ?: 0L, meta?.str("channel") ?: "", meta?.str("parking") ?: "")
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

    /** AI 예약 전화 요청. 결과 = (서버 inbox 행 id, 실패 code). 성공이면 id(kind=reserve, 결과는 이 행이 calling에서 바뀐다), 실패·네트워크 오류면 id null(code: no_number·call_failed·daily_limit, 네트워크 오류는 빈 문자열). */
    fun reserve(place: String, arrive: String, party: Int, lat: Double, lng: Double): Pair<Long?, String> {
        val j = postJson("/car/reserve", JSONObject().put("place", place).put("arrive", arrive).put("party", party).put("lat", lat).put("lng", lng))
        return (if (j?.optBoolean("ok") == true) j.optLong("id", 0L) else null) to (j?.str("code") ?: "")
    }

    /** 근처 가까운 순 최대 15곳(서버가 카카오 카테고리 검색). 못 찾았거나 실패하면 빈 목록. 좌표는 이 호출에만 쓴다. */
    fun nearby(code: String, lat: Double, lng: Double): List<NearPlace> = runCatching {
        val c = open("/car/nearby?category=$code&lat=$lat&lng=$lng", "GET")
        try {
            val a = JSONObject((if (c.responseCode in 200..299) c.inputStream else c.errorStream).bufferedReader().readText()).optJSONArray("places") ?: return@runCatching emptyList()
            List(a.length()) { a.getJSONObject(it) }.map { NearPlace(it.getString("name"), it.getDouble("lat"), it.getDouble("lng"), it.getInt("distance")) }
        } finally { c.disconnect() }
    }.getOrDefault(emptyList())

    fun fetchSchedule(): List<Event>? = runCatching {
        val c = open("/car/schedule", "GET")
        try {
            if (c.responseCode !in 200..299) return@runCatching null
            val arr = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("items")
            (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                Event(it.str("title"), it.getLong("start"), it.str("place"), LatLng(it.getDouble("lat"), it.getDouble("lng")), it.str("contact"), it.str("contact_number"))
            }
        } finally { c.disconnect() }
    }.getOrNull()
}

// JSON null을 "null" 글자가 아니라 빈 문자열로
private fun JSONObject.str(k: String) = if (isNull(k)) "" else optString(k)

data class NearPlace(val name: String, val lat: Double, val lng: Double, val distance: Int)
