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
import androidx.compose.runtime.mutableIntStateOf
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
    private var sentEta: Int? = null // 마지막으로 올린 남은 분. 분이 바뀔 때만 다시 올린다.
    private var since = 0L
    private var firstPoll = true   // 첫 폴링은 기존 항목을 채우기만 하고 읽지 않는다.
    private lateinit var latch: AlertLatch
    private val hold = SpeechHold()
    private var forced: Alert? = null // 디버그 브로드캐스트로 강제한 값
    private val homeOnce = Once()
    private val restOnce = Once()
    private var driveStart: Long? = null // 이번 운전 시작(PARK에서 나온 시각). 정차 중 null.

    private val poller = object : Runnable {
        override fun run() {
            net.execute {
                Server.fetchInbox(since)?.let { r -> ui.post { onInbox(r) } }
                Server.fetchSchedule()?.let { r -> ui.post { schedule = r } }
            }
            ui.postDelayed(this, 15_000)
        }
    }

    private fun onInbox(fetched: List<InboxItem>) {
        val fresh = fetched.filter { it.id > since }
        val initial = firstPoll; firstPoll = false
        since = nextSince(since, fresh)
        if (fresh.isEmpty()) return
        inbox = (fresh.filter { it.kind == "call" } + inbox).sortedByDescending { it.id }.take(50)
        Log.i("ROOM", "inbox ${if (initial) "initial" else "new"} ${fresh.size}")
        if (!initial && !passenger) speechFor(fresh, machine.room)?.let { hold.offer(it, alert) }?.let { speaker.speak(it) }
    }

    /** 과속·주의 경고 중이면 보류했다가 경고가 풀리면 읽는다. */
    private fun say(text: String) { hold.offer(text, alert)?.let { speaker.speak(it) } }

    private fun sendState(r: String, gen: Int, retry: Boolean, eta: Int? = sentEta): Unit = net.execute {
        val ok = Server.postState(r, eta)
        Log.i("ROOM", "state $r eta=$eta -> ${if (ok) "ok" else "FAIL"}")
        // ponytail: 1회 재시도 후 포기(다음 방 변경이 어차피 다시 보냄)
        if (!ok && retry) ui.postDelayed({ if (gen == stateGen) sendState(r, gen, false, eta) }, 30_000)
    }

    private val ticker = object : Runnable {
        override fun run() {
            machine.tick()
            val t = machine.signals.toString()
            if (t != sig) Log.i("ROOM", "signals $t") // 입력 신호가 바뀔 때만
            sig = t
            val a = latch.update(adapters.speedKmh, adapters.limitKmh, adapters.cameraOverspeed, adapters.cautionZone).let { forced ?: it }
            if (a != alert) { alert = a; Log.i("ROOM", "alert $a"); hold.release(a)?.let { speaker.speak(it) } }
            // 정수 분이라 != 가 곧 "1분 이상 변화". 방 판정 전이면 첫 방 변경이 같이 올린다.
            val e = adapters.etaMin
            if (e != sentEta) machine.room?.let { sentEta = e; sendState(it.name, ++stateGen, true) }
            if (restOnce.fire(restDue(driveStart, System.currentTimeMillis(), restMin))) { restOffer = true; say(REST_SPEECH) }
            if (homeOnce.fire(homeSoon(machine.room, machine.signals.destination, machine.settings, e)) && !passenger) say(HOME_SOON_SPEECH)
            ui.postDelayed(this, 200)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CH, "내비서", NotificationManager.IMPORTANCE_LOW))
        val n = notification(room)
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, n)
        getSharedPreferences("car_ui", MODE_PRIVATE).let { restMin = it.getInt("rest_min", REST_DEFAULT_MIN); passenger = it.getBoolean("passenger", false) }
        latch = AlertLatch(Clock.systemDefaultZone())
        machine = RoomStateMachine(Clock.systemDefaultZone()) // settings = 기본 상수. 설정 화면이 machine.settings를 덮어쓴다.
        machine.onChange = { from, to ->
            Log.i("ROOM", "$from -> $to")
            room = to.name
            if (to == Room.PARK) { homeOnce.reset(); restOnce.reset(); restOffer = false; driveStart = null }
            else if (driveStart == null) driveStart = System.currentTimeMillis()
            sentEta = adapters.etaMin
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
        .setContentTitle("내비서 실행 중 — 방: $r")
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
        // adb shell am broadcast -a ztw.carbiseo.ALERT --es v OVERSPEED|CAUTION|NONE (NONE = 강제 해제, 실제 신호로 복귀)
        val al = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                forced = runCatching { Alert.valueOf(i.getStringExtra("v") ?: "") }.getOrNull()?.takeIf { it != Alert.NONE }
                Log.i("ROOM", "debug alert force=$forced")
            }
        }
        for ((rc, action) in listOf(wp to "ztw.carbiseo.WP", r to "ztw.carbiseo.ROUTE", al to "ztw.carbiseo.ALERT")) {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(rc, IntentFilter(action), Context.RECEIVER_EXPORTED) else registerReceiver(rc, IntentFilter(action))
            receivers += rc
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REST) addRestStop()
        if (intent?.action == ACTION_GO) intent.run {
            val lat = getDoubleExtra("lat", 0.0); val lng = getDoubleExtra("lng", 0.0); val name = getStringExtra("name") ?: ""
            Log.i("ROOM", "go $name $lat,$lng")
            route(LatLng(lat, lng), name)
        }
        return START_STICKY
    }

    /** S2 버튼: 경로상 앞쪽 휴게소를 첫 경유지로. 목적지가 없으면 그 휴게소로 경로를 연다. */
    private fun addRestStop() {
        restOffer = false
        val s = machine.signals
        val stop = nearestRestStop(s.location, s.destination) ?: return say("위치를 몰라 휴게소를 못 찾았어요")
        Log.i("ROOM", "rest stop ${stop.name} dest=${s.destination}")
        val n = adapters.navi ?: return
        if (s.destination != null) n.addWaypoint(RequestWaypointInfo(stop.at.lat, stop.at.lng, stop.name, WaypointIndex.FIRST, "", "", "", ""))
        else route(stop.at, stop.name)
    }

    /** 부팅 직후 내비가 준비 전이면 요청이 조용히 버려진다(에뮬 실측 'navi not ready'). 4초 뒤 목적지가 안 잡혔으면 1회 재시도, 그래도 안 되면 알린다. */
    private fun route(to: LatLng, name: String, retry: Boolean = true) {
        val n = adapters.navi ?: return
        n.requestRoute(RouteInfo(to.lat, to.lng, null, null, name, "", "", "", RouteOption.RECOMMENDED, "", false))
        ui.postDelayed({
            if (machine.signals.destination?.let { distanceM(it, to) < 300 } == true) return@postDelayed
            Log.w("ROOM", "route not set: $name retry=$retry")
            if (retry) route(to, name, false) else say("내비가 아직 준비 중이에요. 잠시 뒤 다시 눌러 주세요")
        }, 4000)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ui.removeCallbacks(ticker); ui.removeCallbacks(poller); net.shutdown(); speaker.stop(); adapters.stop(); receivers.forEach { unregisterReceiver(it) }; super.onDestroy()
    }

    companion object {
        private const val CH = "room"
        const val ACTION_REST = "ztw.carbiseo.REST"
        const val ACTION_GO = "ztw.carbiseo.GO" // F7: extras lat, lng, name
        var restOffer by mutableStateOf(false); private set // S2: 화면에 휴게소 버튼
        var restMin by mutableIntStateOf(REST_DEFAULT_MIN) // 연속 주행 기준(분). 설정 화면이 바꾼다.
        var passenger by mutableStateOf(false) // H3: 켜면 업무 TTS(부재중 낭독)·쉼 방 선언(H1)을 끈다. 휴식 제안(S2)은 안전이라 유지.
        var room by mutableStateOf("(판정 전)"); private set
        var sig by mutableStateOf(""); private set
        var inbox by mutableStateOf(listOf<InboxItem>()); private set // id 내림차순
        var alert by mutableStateOf(Alert.NONE); private set
        var schedule by mutableStateOf(listOf<Event>()); private set // F6 데모 일정
    }
}
