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
        val m = toMsg(sbn.packageName, sbn.key, n.category, e.getString(Notification.EXTRA_TEMPLATE) == Notification.MessagingStyle::class.java.name,
            n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0,
            e.getCharSequence(Notification.EXTRA_TITLE), e.getCharSequence(Notification.EXTRA_TEXT), replyAction(n) != null) ?: return
        if (last[sbn.key]?.text == m.text) return
        last[sbn.key] = m
        net.execute {
            val ok = Relay.notify(m)
            Log.i("PHONE", "notify ${m.app} replyable=${m.replyable} -> ${if (ok) "ok" else "FAIL"}")
            Store.add(this, if (ok) Kind.SENT else Kind.SEND_FAIL, m.app, m.sender)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) { last.remove(sbn.key) }

    /** 아직 떠 있는 그 알림의 답장 버튼에 내용을 채워 보낸다. 알림이 사라졌으면 못 보낸다. */
    private fun reply(key: String, text: String) {
        val sbn = runCatching { activeNotifications }.getOrNull()?.firstOrNull { it.key == key }
        val a = sbn?.notification?.let(::replyAction)
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

/** 로컬 기록(앱 전용 저장소, 백업 꺼짐). 서비스와 화면이 같은 프로세스에서 공유한다. */
object Store {
    fun prefs(c: Context) = c.getSharedPreferences("app", Context.MODE_PRIVATE)
    fun events(c: Context) = decode(prefs(c).getString("events", null))
    @Synchronized fun add(c: Context, k: Kind, app: String, who: String) {
        prefs(c).edit().putString("events", encode(add(events(c), Event(System.currentTimeMillis(), k, app, who)))).apply()
    }
}
