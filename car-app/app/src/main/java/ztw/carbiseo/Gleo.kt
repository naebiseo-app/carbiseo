package ztw.carbiseo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

// Gleo(Pleos 음성 비서)가 부르는 함수 이름을 앱 음성 의도(CarIntent)로 바꾸고, 그 호출을 받는 수신기.
// 의도를 실제로 처리해 읽는 일은 RoomService(`RoomService.gleo`), 의도 종류는 Drive.kt의 CarIntent.

private const val GLEO_PREFIX = "ztw.carbiseo." // 에뮬 Gleo 4.2는 action에 패키지를 붙여 보낸다(공식 예시는 안 붙임)

/** Gleo 함수 이름(assets/schema.json) → 음성 의도. */
fun gleoIntent(action: String?): CarIntent? = when (action?.removePrefix(GLEO_PREFIX)) {
    "READ_RECENT_CALLS" -> CarIntent.RECENT_CALLS
    "READ_NEXT_SCHEDULE" -> CarIntent.NEXT_EVENT
    "ADD_REST_STOP" -> CarIntent.URGENT
    else -> null
}

/**
 * Gleo Action 수신(component=service). 답은 앱이 직접 읽고(mode silent) Gleo에는 결과만 알린다:
 * 응답 방송은 다른 앱도 받을 수 있어 통화 요약·일정 내용을 data에 넣지 않는다.
 */
class GleoReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val intent = gleoIntent(i.action) ?: return
        val fn = i.action.orEmpty().removePrefix(GLEO_PREFIX)
        Log.i("ROOM", "gleo $fn")
        val run = RoomService.gleo
        run?.invoke(intent)
        val res = JSONObject().put("functionName", fn)
            .put("status", if (run != null) "success" else "fail")
            .put("message", if (run != null) "The car assistant is answering by voice." else "The car assistant is not running.")
        if (run != null) res.put("mode", "silent")
        c.sendBroadcast(Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE").putExtra("packageName", c.packageName).putExtra("response", res.toString()))
    }
}
