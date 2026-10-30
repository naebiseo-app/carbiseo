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
    private var okState: Pair<String, Int?>? = null // 서버가 마지막으로 받은 (방, 남은 분)
    private var since = 0L
    private var firstPoll = true   // 첫 폴링은 기존 항목을 채우기만 하고 읽지 않는다.
    private lateinit var latch: AlertLatch
    private val hold = SpeechHold()
    private var forced: Alert? = null // 디버그 브로드캐스트로 강제한 값
    private var forcedTrip: Int? = null // 디버그: 트립 주행시간(분) 강제

    /** 트립 주행시간(분): SDK 값, 없으면(에뮬은 3종 모두 ERROR_NOT_SUPPORTED_PROPERTY) 이번 운전을 앱이 잰 분. */
    private fun tripMin(): Int? = forcedTrip ?: adapters.tripMin ?: driveStart?.let { ((System.currentTimeMillis() - it) / 60_000).toInt() }
    private val homeOnce = Once()
    private val restOnce = Once()
    private val fatigueOnce = Once()
    private var quiet = false            // 경고·깜빡이·후진·큰 조향 중
    private val seat = SeatEdge()
    private var lastTell: String? = null
    private var ctxSent = ""; private var ctxAt = 0L // 차량 맥락: 바뀌면 올리되 10초에 한 번까지
    private var driveStart: Long? = null // 이번 운전 시작(PARK에서 나온 시각). 정차 중 null.

    private val poller = object : Runnable {
        override fun run() {
            net.execute {
                val r = Server.fetchInbox(since)
                ui.post {
                    offline = r == null; r?.let(::onInbox) // 15초마다 다시 시도한다
                    // 오프라인 중 상태 전송이 다 실패했으면 서버가 옛 방에 멈춰 있다 → 연결되면 지금 상태를 다시 올린다
                    val cur = machine.room?.let { it.name to sentEta }
                    if (r != null && cur != null && cur != okState) { Log.i("ROOM", "state resync $cur (server had $okState)"); sendState(cur.first, ++stateGen, false) }
                }
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
        inbox = (fresh.filter { it.kind in SHOWN_KINDS } + inbox).sortedByDescending { it.id }.take(50)
        Log.i("ROOM", "inbox ${if (initial) "initial" else "new"} ${fresh.size}")
        // 피로 모드면 음성 없이 보관만(카드는 쌓인다)
        if (!initial && !passenger && !fatigued(machine.room, tripMin())) hold.offerItems(fresh, machine.room, quiet)?.let { speaker.speak(it) }
    }

    /** 과속·주의 경고 중이면 보류했다가 경고가 풀리면 읽는다. */
    private fun say(text: String) { hold.offer(text, quiet)?.let { speaker.speak(it) } }

    /** 서버 대본 변수·카드 칩용 SDK 값. */
    private fun carCtx(): Map<String, Any?> = mapOf(
        "gear" to gearCode(machine.signals.gear), "trip_min" to tripMin(), "soc" to adapters.soc,
        "turn" to adapters.turnOn, "passenger" to (passenger || seat.occupied), "telltale" to adapters.telltale,
    )

    private fun sendState(r: String, gen: Int, retry: Boolean, eta: Int? = sentEta) {
        val ctx = carCtx(); ctxSent = ctx.toString(); ctxAt = System.currentTimeMillis()
        net.execute { postState(r, gen, retry, eta, ctx) }
    }

    private fun postState(r: String, gen: Int, retry: Boolean, eta: Int?, ctx: Map<String, Any?>) {
        val ok = Server.postState(r, eta, ctx)
        Log.i("ROOM", "state $r eta=$eta ctx=$ctx -> ${if (ok) "ok" else "FAIL"}")
        if (ok) ui.post { okState = r to eta }
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
            if (a != alert) { alert = a; Log.i("ROOM", "alert $a") }
            val q = a != Alert.NONE || busyManeuver(adapters.turnOn, machine.signals.gear, adapters.steerDeg)
            if (q != quiet) { quiet = q; Log.i("ROOM", "quiet $q"); hold.release(q, machine.room)?.let { speaker.speak(it) } }
            if (passenger) hold.clear() // 동승자 모드면 보류분도 버린다
            // 정수 분이라 != 가 곧 "1분 이상 변화". 방 판정 전이면 첫 방 변경이 같이 올린다.
            val e = adapters.etaMin
            if (e != sentEta) machine.room?.let { sentEta = e; sendState(it.name, ++stateGen, true) }
            if (restOnce.fire(restDue(driveStart, System.currentTimeMillis(), restMin))) { restOffer = true; say(REST_SPEECH) }
            if (fatigueOnce.fire(fatigued(machine.room, tripMin()))) say(FATIGUE_SPEECH) // 안전 안내라 동승자 모드에서도
            seat.update(adapters.passengerSeat)?.let { passengerOffer = it && !passenger }
            if (passenger) passengerOffer = false
            adapters.telltale.let { t -> if (t != lastTell) { lastTell = t; telltale = t; if (t != null && machine.room != Room.PARK) say(TELLTALE_SPEECH) } }
            // 바뀌면 10초에 한 번까지, 안 바뀌어도 5분마다 하트비트(서버는 10분 넘게 갱신이 없으면 방을 모른다고 본다)
            val ago = System.currentTimeMillis() - ctxAt
            if ((carCtx().toString() != ctxSent && ago >= 10_000) || ago >= HEARTBEAT_MS) machine.room?.let { sendState(it.name, ++stateGen, false) }
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
        net.execute { // 첫 SharedPreferences 로드는 디스크 읽기 → 메인 밖에서
            val p = getSharedPreferences("car_ui", MODE_PRIVATE)
            val r = p.getInt("rest_min", REST_DEFAULT_MIN).takeIf { m -> m != 1 } ?: REST_DEFAULT_MIN; val ps = p.getBoolean("passenger", false)
            ui.post { restMin = r; passenger = ps }
        }
        latch = AlertLatch(Clock.systemDefaultZone())
        machine = RoomStateMachine(Clock.systemDefaultZone()) // settings = 기본 상수. 설정 화면이 machine.settings를 덮어쓴다.
        machine.onChange = { from, to ->
            Log.i("ROOM", "$from -> $to")
            room = to.name
            if (to == Room.PARK) { homeOnce.reset(); restOnce.reset(); fatigueOnce.reset(); restOffer = false; driveStart = null; hold.clear() }
            else if (driveStart == null) driveStart = System.currentTimeMillis()
            sentEta = adapters.etaMin
            sendState(to.name, ++stateGen, true)
            if (!passenger) roomSpeech(to)?.let(::say)
            getSystemService(NotificationManager::class.java).notify(1, notification(room))
        }
        adapters = CarAdapters(applicationContext, machine).also { it.start() }
        adapters.onNaviReady = { pendingRoute?.let { pendingRoute = null; Log.i("ROOM", "navi ready -> route"); it() } }
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
        // adb shell am broadcast -a ztw.carbiseo.TRIP --ei min 125 (-1 = 해제)
        val tr = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { forcedTrip = i.getIntExtra("min", -1).takeIf { it >= 0 }; Log.i("ROOM", "debug trip force=$forcedTrip") }
        }
        for ((rc, action) in listOf(wp to "ztw.carbiseo.WP", r to "ztw.carbiseo.ROUTE", al to "ztw.carbiseo.ALERT", tr to "ztw.carbiseo.TRIP")) {
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

    /** 경로 계산에 12~15초 걸린다(에뮬 실측). 재요청은 진행 중 경로를 취소시키므로(onRouteCancelled) 확인만 하고, 20초 뒤에도 안 잡혔으면 알린다. */
    private fun route(to: LatLng, name: String) {
        val n = adapters.navi ?: return
        val send = {
            n.requestRoute(RouteInfo(to.lat, to.lng, null, null, name, "", "", "", RouteOption.RECOMMENDED, "", false))
            ui.postDelayed({
                if (machine.signals.destination?.let { distanceM(it, to) < 300 } == true) return@postDelayed
                Log.w("ROOM", "route not set: $name")
                say("내비가 아직 준비 중이에요. 잠시 뒤 다시 눌러 주세요")
            }, 20_000); Unit
        }
        val wake = packageManager.getLaunchIntentForPackage(NAVI_PKG)
        if (adapters.naviReady || wake == null) return send()
        // 부팅 후 내비 화면을 한 번도 안 열었으면 내비가 요청을 버린다 → 내비를 앞으로 띄워 깨우고, 첫 응답(준비)이 오면 보낸다.
        // 출발 탭 직후라 우리 창이 보이는 중이어서 서비스에서 띄워도 백그라운드 실행 제한에 안 걸린다.
        Log.i("ROOM", "navi not ready -> wake $NAVI_PKG")
        pendingRoute = send
        startActivity(wake.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.postDelayed({ if (pendingRoute === send) { pendingRoute = null; Log.w("ROOM", "navi wake timeout: $name"); say("내비가 아직 준비 중이에요. 잠시 뒤 다시 눌러 주세요") } }, 60_000)
    }
    private var pendingRoute: (() -> Unit)? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ui.removeCallbacks(ticker); ui.removeCallbacks(poller); net.shutdown(); speaker.stop(); adapters.stop(); receivers.forEach { unregisterReceiver(it) }; super.onDestroy()
    }

    companion object {
        private const val CH = "room"
        private const val HEARTBEAT_MS = 5 * 60_000L
        const val ACTION_REST = "ztw.carbiseo.REST"
        const val ACTION_GO = "ztw.carbiseo.GO" // F7: extras lat, lng, name
        private const val NAVI_PKG = "ai.umos.maps.android.navigation.app" // Pleos 내비 앱(26 실측)
        var restOffer by mutableStateOf(false); private set // S2: 화면에 휴게소 버튼
        var restMin by mutableIntStateOf(REST_DEFAULT_MIN) // 연속 주행 기준(분). 설정 화면이 바꾼다.
        var passenger by mutableStateOf(false) // H3: 켜면 업무 TTS(부재중 낭독)·쉼 방 선언(H1)을 끈다. 휴식 제안(S2)은 안전이라 유지.
        var room by mutableStateOf("(판정 전)"); private set
        var sig by mutableStateOf(""); private set
        var inbox by mutableStateOf(listOf<InboxItem>()); private set // id 내림차순
        var alert by mutableStateOf(Alert.NONE); private set
        var schedule by mutableStateOf(listOf<Event>()); private set // F6 데모 일정
        var offline by mutableStateOf(false); private set // #23 서버 폴링 실패
        var passengerOffer by mutableStateOf(false) // C⑤ 조수석 착석 감지 → "동승자 모드로 바꿀까요?"
        var telltale by mutableStateOf<String?>(null); private set // C⑦ 빨간 경고등
    }
}
