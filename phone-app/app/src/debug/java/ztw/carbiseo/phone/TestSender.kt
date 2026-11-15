package ztw.carbiseo.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

// 디버그 전용 '시험 메신저': 답장 버튼(RemoteInput) 있는 메시지 알림을 만들고, 답장이 오면 결과 알림을 띄운다.
// adb shell am broadcast -n ztw.carbiseo.phone/.TestSender --es from 김민수 --es text "회의 자료 보냈어요"
private const val CH = "test_msg"
private const val KEY = "reply"

private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java).also {
    it.createNotificationChannel(NotificationChannel(CH, "시험 메시지", NotificationManager.IMPORTANCE_DEFAULT))
}

class TestSender : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val from = i.getStringExtra("from") ?: "김민수"; val text = i.getStringExtra("text") ?: "시험 메시지"
        val pi = PendingIntent.getBroadcast(c, 1, Intent(c, TestReply::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val action = Notification.Action.Builder(null, "답장", pi).addRemoteInput(RemoteInput.Builder(KEY).setLabel("답장").build()).build()
        val style = Notification.MessagingStyle(Person.Builder().setName("나").build())
            .addMessage(text, System.currentTimeMillis(), Person.Builder().setName(from).build())
        nm(c).notify(7, Notification.Builder(c, CH).setSmallIcon(android.R.drawable.sym_action_chat)
            .setCategory(Notification.CATEGORY_MESSAGE).setStyle(style).addAction(action).build())
        Log.i("PHONE", "test message posted")
    }
}

class TestReply : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val r = RemoteInput.getResultsFromIntent(i)?.getCharSequence(KEY)
        Log.i("PHONE", "test messenger got reply (${r?.length ?: 0}자)")
        val m = nm(c); m.cancel(7)
        // 메시지형이 아닌 알림(분류·스타일 없음)이라 Listener가 다시 넘기지 않는다
        m.notify(8, Notification.Builder(c, CH).setSmallIcon(android.R.drawable.sym_action_chat).setContentTitle("시험 메신저: 답장 받음").setContentText(r).build())
    }
}
