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
