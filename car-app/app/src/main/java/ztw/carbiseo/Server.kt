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

    fun postState(room: String, etaMin: Int?) = post("/car/state", JSONObject().put("room", room).put("eta_min", etaMin ?: JSONObject.NULL))

    fun postSettings(ownerLabel: String, assistantName: String) =
        post("/car/settings", JSONObject().put("owner_label", ownerLabel).put("assistant_name", assistantName))

    fun fetchInbox(since: Long): List<InboxItem>? = runCatching {
        val c = open("/car/inbox?since=$since", "GET")
        try {
            if (c.responseCode !in 200..299) return@runCatching null
            val arr = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("items")
            (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                InboxItem(it.getLong("id"), it.str("from_label"), it.str("summary"),
                    it.optInt("urgent") != 0, it.optLong("created_at"), it.str("raw"), it.str("kind").ifEmpty { "call" })
            }
        } finally { c.disconnect() }
    }.getOrNull()

    fun fetchSchedule(): List<Event>? = runCatching {
        val c = open("/car/schedule", "GET")
        try {
            if (c.responseCode !in 200..299) return@runCatching null
            val arr = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("items")
            (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                Event(it.str("title"), it.getLong("start"), it.str("place"), LatLng(it.getDouble("lat"), it.getDouble("lng")))
            }
        } finally { c.disconnect() }
    }.getOrNull()
}

// JSON null을 "null" 글자가 아니라 빈 문자열로
private fun JSONObject.str(k: String) = if (isNull(k)) "" else optString(k)
