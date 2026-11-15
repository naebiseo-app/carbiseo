package ztw.carbiseo.phone

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import java.util.concurrent.Executors

// 알림 접근(NotificationListenerService)을 맡는다: 메시지형 알림을 서버로 넘기고, 서버가 모아 둔 음성 답장을 그 알림의 답장 버튼으로 보낸다.
// 맡지 않는 일: 알림을 거르는 규칙(Relay.kt toMsg), 권한 안내·화면(App.kt).
// 먼저 읽을 것: onNotificationPosted(알림 → 서버), poller·reply(서버의 답장 → 알림), 맨 아래 Store(로컬 기록).
// 서버로 나가는 것: toMsg를 통과한 메시지형 알림의 앱 이름·보낸이·내용(500자까지)·답장 가능 여부·알림 키. 메시지가 아닌 알림은 넘기지 않는다.
// 사용자가 시스템 설정에서 알림 접근을 끄면 이 서비스가 불리지 않아 아무것도 나가지 않는다. 기기 로그에는 앱 이름·답장 가능 여부·성공 여부만 남기고 보낸이와 내용은 남기지 않는다.

/** 알림의 답장 버튼(RemoteInput이 붙은 액션). */
private fun replyAction(n: Notification): Notification.Action? = n.actions?.firstOrNull { a -> a.remoteInputs?.isNotEmpty() == true }

/**
 * '내비서 연결': 폰 메시지 알림 → 서버 /phone/notify, 15초마다 /phone/replies → 그 알림의 답장 버튼으로 전송.
 * 알림 내용은 로그에 남기지 않는다.
 */
class Listener : NotificationListenerService() {
    private val ui = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()
    private val last = mutableMapOf<String, Msg>() // key -> 마지막으로 넘긴 알림(같은 알림 재게시 중복 방지, 답장 기록용 앱·보낸이)

    // 서비스가 연결된 동안 15초마다 서버의 답장 대기열을 비운다(onListenerDisconnected에서 멈춘다). 가져간 답장은 서버에서 delivered가 된다.
    // 한계: 그 알림이 이미 지워졌으면 답장은 보내지 못하고 기록(REPLY_FAIL)만 남고 다시 오지 않는다 (넘으려면 전달이 끝난 뒤에 서버가 delivered로 바꾸게 한다).
    private val poller = object : Runnable {
        override fun run() {
            net.execute { Relay.replies().forEach { (key, text) -> ui.post { reply(key, text) } } }
            ui.postDelayed(this, 15_000)
        }
    }

    override fun onListenerConnected() { Log.i("PHONE", "listener connected"); ui.post(poller) }
    override fun onListenerDisconnected() { ui.removeCallbacks(poller) }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val n = sbn.notification
        val e = n.extras
        // 디버그 빌드만: 시험 메신저(TestSender, 이 앱 자신)의 메시지 알림을 카카오톡으로 취급(에뮬엔 카톡이 없다). release에선 BuildConfig.DEBUG=false라 꺼진다.
        // 메시지 분류만 바꾼다 — '답장 받음' 결과 알림(분류 없음)까지 바꾸면 차로 되돌아간다(11-02 실측).
        val pkg = if (BuildConfig.DEBUG && sbn.packageName == packageName && n.category == Notification.CATEGORY_MESSAGE) "com.kakao.talk" else sbn.packageName
        // toMsg를 통과한 것만 서버로 간다(앱 이름·보낸이 30자·내용 500자까지).
        val m = toMsg(pkg, sbn.key, n.category, e.getString(Notification.EXTRA_TEMPLATE) == Notification.MessagingStyle::class.java.name,
            n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0,
            e.getCharSequence(Notification.EXTRA_TITLE), e.getCharSequence(Notification.EXTRA_TEXT), replyAction(n) != null) ?: return
        if (last[sbn.key]?.text == m.text) return
        last[sbn.key] = m
        net.execute {
            val ok = Relay.notify(m)
            Log.i("PHONE", "notify ${m.app} replyable=${m.replyable} -> ${if (ok) "ok" else "FAIL"}")
            // 전달 결과만 로컬 기록에 남긴다(앱 이름·보낸이, 내용 없음).
            Store.add(this, if (ok) Kind.SENT else Kind.SEND_FAIL, m.app, m.sender)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) { last.remove(sbn.key) }

    /** 아직 떠 있는 그 알림의 답장 버튼에 내용을 채워 보낸다. 알림이 사라졌으면 못 보낸다. 결과는 기록에 REPLIED·REPLY_FAIL로 남고 답장 내용은 로그에 남기지 않는다. */
    private fun reply(key: String, text: String) {
        val sbn = runCatching { activeNotifications }.getOrNull()?.firstOrNull { it.key == key }
        val a = sbn?.notification?.let(::replyAction)
        // last는 메모리에만 있어 서비스가 다시 시작된 뒤에는 비어 있다. 그때는 기록에 기본 이름("메시지"·"상대")을 쓴다.
        val m = last[key]
        if (a == null) { Log.w("PHONE", "reply: 알림 없음(지워졌거나 답장 불가)"); Store.add(this, Kind.REPLY_FAIL, m?.app ?: "메시지", m?.sender ?: "상대"); return }
        val i = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(a.remoteInputs, i, Bundle().apply { a.remoteInputs.forEach { putCharSequence(it.resultKey, text) } })
        runCatching { a.actionIntent.send(this, 0, i) }
            .onSuccess { Log.i("PHONE", "reply sent via ${sbn.packageName}"); Store.add(this, Kind.REPLIED, m?.app ?: "메시지", m?.sender ?: "상대") }
            .onFailure { Log.w("PHONE", "reply FAIL $it"); Store.add(this, Kind.REPLY_FAIL, m?.app ?: "메시지", m?.sender ?: "상대") }
    }

    override fun onDestroy() { ui.removeCallbacks(poller); net.shutdown(); super.onDestroy() }
}

/** 로컬 기록(앱 전용 저장소, 백업 꺼짐). 서비스와 화면이 같은 프로세스에서 공유한다. 앱 이름·보낸이만 남기고 내용은 저장하지 않으며, 최신순으로 최대 MAX_EVENTS건(Model.kt)이다. */
object Store {
    fun prefs(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE)
    fun events(c: Context) = decode(prefs(c).getString("events", null))
    // 읽고-붙이고-쓰는 사이에 다른 add가 끼어 기록이 사라지지 않게 잠근다(여러 스레드가 부른다).
    @Synchronized fun add(c: Context, k: Kind, app: String, who: String) {
        prefs(c).edit().putString("events", encode(add(events(c), Event(System.currentTimeMillis(), k, app, who)))).apply()
    }
}
