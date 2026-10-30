package ztw.carbiseo.phone

import android.app.Activity
import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
    private val last = mutableMapOf<String, String>() // key -> 마지막으로 넘긴 내용(같은 알림 재게시 중복 방지)

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
        if (last[sbn.key] == m.text) return
        last[sbn.key] = m.text
        net.execute { Log.i("PHONE", "notify ${m.app} replyable=${m.replyable} -> ${if (Relay.notify(m)) "ok" else "FAIL"}") }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) { last.remove(sbn.key) }

    /** 아직 떠 있는 그 알림의 답장 버튼에 내용을 채워 보낸다. 알림이 사라졌으면 못 보낸다. */
    private fun reply(key: String, text: String) {
        val sbn = runCatching { activeNotifications }.getOrNull()?.firstOrNull { it.key == key }
        val a = sbn?.notification?.let(::replyAction)
        if (a == null) { Log.w("PHONE", "reply: 알림 없음(지워졌거나 답장 불가)"); return }
        val i = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        RemoteInput.addResultsToIntent(a.remoteInputs, i, Bundle().apply { a.remoteInputs.forEach { putCharSequence(it.resultKey, text) } })
        runCatching { a.actionIntent.send(this, 0, i) }
            .onSuccess { Log.i("PHONE", "reply sent via ${sbn.packageName}") }.onFailure { Log.w("PHONE", "reply FAIL $it") }
    }

    override fun onDestroy() { ui.removeCallbacks(poller); net.shutdown(); super.onDestroy() }
}

/** 첫 실행: 알림 접근 허용 화면을 연다(사용자가 '내비서 연결'을 켠다). */
class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 0) // 디버그 시험 알림용
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        finish()
    }
}
