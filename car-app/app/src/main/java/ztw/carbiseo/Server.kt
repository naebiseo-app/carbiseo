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

    fun postState(room: String): Boolean = runCatching {
        val c = open("/car/state", "POST")
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(JSONObject().put("room", room).toString().toByteArray()) }
        (c.responseCode in 200..299).also { c.disconnect() }
    }.getOrDefault(false)

    fun fetchInbox(since: Long): List<InboxItem>? = runCatching {
        val c = open("/car/inbox?since=$since", "GET")
        try {
            if (c.responseCode !in 200..299) return@runCatching null
            val arr = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("items")
            (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                InboxItem(it.getLong("id"), it.optString("from_label"), it.optString("summary"),
                    it.optInt("urgent") != 0, it.optLong("created_at"), it.optString("raw"), it.optString("kind", "call"))
            }
        } finally { c.disconnect() }
    }.getOrNull()
}
