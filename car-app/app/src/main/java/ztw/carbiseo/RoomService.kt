package ztw.carbiseo

import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Clock
import java.util.concurrent.Executors

/** 방 판정 본체. 내비가 앞에 와도 Activity와 무관하게 돈다. 화면은 companion의 room/sig를 읽는다(같은 프로세스). */
class RoomService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var machine: RoomStateMachine
    private lateinit var adapters: CarAdapters
    private val receivers = mutableListOf<BroadcastReceiver>()
    private lateinit var speaker: Speaker
    private val net = Executors.newSingleThreadExecutor() // 서버 호출은 여기서만(순서 보장, 메인 차단 방지)
    private var stateGen = 0       // 방 변경마다 +1. 재시도는 최신 방일 때만.
    private var since = 0L
    private var firstPoll = true   // 첫 폴링은 기존 항목을 채우기만 하고 읽지 않는다.

    private val poller = object : Runnable {
        override fun run() { net.execute { Server.fetchInbox(since)?.let { r -> ui.post { onInbox(r) } } }; ui.postDelayed(this, 15_000) }
    }

    private fun onInbox(fetched: List<InboxItem>) {
        val fresh = fetched.filter { it.id > since }
        val initial = firstPoll; firstPoll = false
        since = nextSince(since, fresh)
        if (fresh.isEmpty()) return
        inbox = (fresh + inbox).sortedByDescending { it.id }.take(50)
        Log.i("ROOM", "inbox ${if (initial) "initial" else "new"} ${fresh.size}")
        if (!initial) speechFor(fresh, machine.room)?.let { speaker.speak(it) }
    }

    private fun sendState(r: String, gen: Int, retry: Boolean): Unit = net.execute {
        val ok = Server.postState(r)
        Log.i("ROOM", "state $r -> ${if (ok) "ok" else "FAIL"}")
        // ponytail: 1회 재시도 후 포기(다음 방 변경이 어차피 다시 보냄)
        if (!ok && retry) ui.postDelayed({ if (gen == stateGen) sendState(r, gen, false) }, 30_000)
    }

    private val ticker = object : Runnable {
        override fun run() {
            machine.tick()
            val t = machine.signals.toString()
            if (t != sig) Log.i("ROOM", "signals $t") // 입력 신호가 바뀔 때만
            sig = t; ui.postDelayed(this, 200)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "차량비서", NotificationManager.IMPORTANCE_LOW))
        val n = notification(room)
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        machine = RoomStateMachine(Clock.systemDefaultZone()) // settings = 기본 상수. 설정 화면이 machine.settings를 덮어쓴다.
        machine.onChange = { from, to ->
            Log.i("ROOM", "$from -> $to")
            room = to.name
            sendState(to.name, ++stateGen, true)
            getSystemService(NotificationManager::class.java).notify(1, notification(room))
        }
        adapters = CarAdapters(applicationContext, machine).also { it.start() }
        speaker = Speaker(applicationContext).also { it.start() }
        ui.post(ticker)
        ui.post(poller)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) registerDebugRoute()
    }

    private fun notification(r: String) = Notification.Builder(this, CH)
        .setSmallIcon(android.R.drawable.ic_menu_directions)
        .setContentTitle("차량비서 실행 중 — 방: $r")
        .setOngoing(true).build()

    // 디버그 빌드 전용: adb shell am broadcast -a ztw.carbiseo.ROUTE --ef lat 37.4979 --ef lng 127.0276
    private fun registerDebugRoute() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val lat = i.getFloatExtra("lat", 0f).toDouble()
                val lng = i.getFloatExtra("lng", 0f).toDouble()
                Log.i("ROOM", "debug requestRoute $lat,$lng")
                adapters.navi?.requestRoute(RouteInfo(lat, lng, null, null, "debug", "", "", "", RouteOption.RECOMMENDED, "", false))
            }
        }
        val wp = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                Log.i("ROOM", "debug addWaypoint")
                adapters.navi?.addWaypoint(RequestWaypointInfo(37.2636, 127.0286, "Test rest area", WaypointIndex.FIRST, "", "", "", ""))
            }
        }
        for ((rc, action) in listOf(wp to "ztw.carbiseo.WP", r to "ztw.carbiseo.ROUTE")) {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(rc, IntentFilter(action), Context.RECEIVER_EXPORTED) else registerReceiver(rc, IntentFilter(action))
            receivers += rc
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ui.removeCallbacks(ticker); ui.removeCallbacks(poller); net.shutdown(); speaker.stop(); adapters.stop(); receivers.forEach { unregisterReceiver(it) }; super.onDestroy()
    }

    companion object {
        private const val CH = "room"
        var room by mutableStateOf("(판정 전)"); private set
        var sig by mutableStateOf(""); private set
        var inbox by mutableStateOf(listOf<InboxItem>()); private set // id 내림차순
    }
}
