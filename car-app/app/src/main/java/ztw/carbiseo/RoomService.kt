package ztw.carbiseo

import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.NavigationScreenRequest
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
import android.media.AudioManager
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
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.Executors

/** 방 판정 본체. 내비가 앞에 와도 Activity와 무관하게 돈다. 화면은 companion의 room/sig를 읽는다(같은 프로세스). */
class RoomService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var machine: RoomStateMachine
    private lateinit var adapters: CarAdapters
    private val receivers = mutableListOf<BroadcastReceiver>()
    private lateinit var speaker: Speaker
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val net = Executors.newSingleThreadExecutor() // 서버 호출은 여기서만(순서 보장, 메인 차단 방지)
    private var stateGen = 0       // 방 변경마다 +1. 재시도는 최신 방일 때만.
    private var sentEta: Int? = null // 마지막으로 올린 남은 분. 분이 바뀔 때만 다시 올린다.
    private var okState: Pair<String, Int?>? = null // 서버가 마지막으로 받은 (방, 남은 분)
    private var since = 0L
    private var firstPoll = true   // 첫 폴링은 기존 항목을 채우기만 하고 읽지 않는다.
    private lateinit var latch: AlertLatch
    private val hold = SpeechHold { mode }
    private var forced: Alert? = null // 디버그 브로드캐스트로 강제한 값
    private var forcedTrip: Int? = null // 디버그: 트립 주행시간(분) 강제

    /** 트립 주행시간(분): SDK 값(이번 운전 시작 때 값과의 차이, H12), 없으면(에뮬은 3종 모두 ERROR_NOT_SUPPORTED_PROPERTY) 이번 운전을 앱이 잰 분. */
    private fun tripMin(): Int? = forcedTrip ?: trip.of(adapters.tripMin) ?: driveStart?.let { ((clock.millis() - it) / 60_000).toInt() }
    private val trip = TripDelta()
    private var parkedAt: Long? = null // 이번 P 시작(시뮬 시각). null = 주행 중이거나 서비스 시작 후 아직 P 아님
    private val declared = DayRoom()
    private fun today() = LocalDate.now(clock).toEpochDay()
    private val prefs by lazy { getSharedPreferences("car_ui", MODE_PRIVATE) } // 첫 로드는 onCreate의 net 스레드에서
    private var briefDay = -1L // '어제 넘긴 건' 문장을 말한 날
    private val homeOnce = Once()
    private val restOnce = Once()
    private val fatigueOnce = Once()
    private val parkRemind = Once() // M12: 정차마다 1회
    private var greenSaid = false        // 이번 신호에 출발 알림을 했나
    private var quiet = false            // 경고·깜빡이·후진·큰 조향 중
    private val seat = SeatEdge()
    private var lastTell: String? = null
    private var ctxSent = ""; private var ctxAt = 0L // 차량 맥락: 바뀌면 올리되 10초에 한 번까지
    private var driveStart: Long? = null // 이번 운전 시작(PARK에서 나온 시각). 정차 중 null.
    // 폰 알림(35): 알릴 대기열(미루기 조건이 풀리면 묶어서), 이번 실행에 새로 온 것, 이미 알린 것
    private lateinit var voice: Voice
    private val talk = NotifTalk()
    private val notifQueue = mutableListOf<InboxItem>()
    private val notifFresh = mutableSetOf<Long>()
    private val notifSaid = mutableSetOf<Long>()
    // 음성으로 묻고 대답을 기다리는 질문(알림 묻기는 talk가 따로). 대답은 heard()가 받는다.
    private enum class Ask { NONE, LATE, REST, RESERVE, CALLBACK, SEND, INTENT, INTENT_RETRY }
    private class PendingSend(val label: String, val number: String, val body: String, val task: String) // 보낼 문자(label·number·body)와 못 보냈을 때 남길 할 일 본문
    private var pending = Ask.NONE
    private var lateEvent: Pair<Event, Int>? = null
    private val lateAsked = mutableSetOf<Long>() // 이미 물은 일정(start)
    private val reserveOnce = Once() // O4: 운전 1회당 한 번
    private val callFresh = mutableSetOf<Long>() // 이번 실행에 새로 온 통화 카드 id
    private val callbackAsked = mutableSetOf<Long>() // 문자 먼저 보낼지 이미 물은 카드(카드당 한 번)
    private var callback: Pair<InboxItem, String>? = null
    private var sending: PendingSend? = null
    private var reserve: Pair<String, String>? = null // 예약 초안을 물은 (목적지 이름, 도착 HH:MM)
    private var forcedDest: String? = null // 디버그 ROUTE의 --es name (에뮬은 내비가 목적지 이름을 안 줄 수 있어서)
    private var familyDay = -1L // 가족에게 도착 예상을 보낸 날(하루 한 번)
    private var restSnoozeUntil = 0L // 휴식 제안에 '아니'라고 한 뒤 30분(시뮬 시각)

    private val poller = object : Runnable {
        override fun run() {
            net.execute {
                val r = Server.fetchInbox(since)
                ui.post {
                    offline = r == null; r?.let { (items, minId) -> inbox = pruneGone(inbox, minId); onInbox(items) } // 5초마다 다시 시도한다
                    // 오프라인 중 상태 전송이 다 실패했으면 서버가 옛 방에 멈춰 있다 → 연결되면 지금 상태를 다시 올린다
                    val cur = machine.room?.let { it.name to sentEta }
                    if (r != null && cur != null && cur != okState) { Log.i("ROOM", "state resync $cur (server had $okState)"); sendState(cur.first, ++stateGen, false) }
                }
                Server.fetchSchedule()?.let { r -> ui.post { schedule = r } }
                if (!forwardOk || serviceNumber.isEmpty()) Server.fetchConfig()?.let { (n, fw) -> ui.post { serviceNumber = n; if (fw) markForwarded() } }
            }
            ui.postDelayed(this, 5_000) // 끊고 카드까지 대기를 줄인다(Worker 요청 하루 약 3.5만, 무료 10만 안)
        }
    }

    private fun onInbox(fetched: List<InboxItem>) {
        val fresh = fetched.filter { it.id > since }
        val initial = firstPoll; firstPoll = false
        since = nextSince(since, fresh)
        if (fresh.isEmpty()) return
        if (fresh.any { it.kind == "ring" }) markForwarded() // 실제 착신 통화가 서버에 왔다 = 착신 설정 완료
        val off = machine.room == Room.DRIVE_REST || offDuty(LocalDateTime.now(clock), machine.settings)
        deferNew(fresh, initial, off, today(), machine.settings).takeIf { it.isNotEmpty() }?.let { d ->
            deferred = deferred + d; Log.i("ROOM", "deferred +${d.keys}")
            val s = encodeDeferred(deferred); net.execute { prefs.edit().putString("deferred", s).apply() }
        }
        inbox = (fresh.filter { it.kind in SHOWN_KINDS } + inbox).sortedByDescending { it.id }.take(50)
        Log.i("ROOM", "inbox ${if (initial) "initial" else "new"} ${fresh.size}")
        // 피로 모드면 음성 없이 보관만(카드는 쌓인다). 바로 연결(급함·direct) 전화만 예외로 한 줄(조작·경고·통화 중 보류는 say가 그대로 적용)
        if (!initial && !passenger) {
            if (fatigued(machine.room, tripMin())) directSpeech(fresh, mode)?.let(::say)
            else hold.offerItems(fresh, machine.room, quiet)?.let { speaker.speak(it) }
        }
        if (!initial) callFresh += fresh.filter { it.kind == "call" }.map { it.id }
        if (!initial) fresh.filter { it.kind == "notif" }.let { n -> notifFresh += n.map { it.id }; notifQueue += notifNow(n.sortedBy { it.id }, machine.room, favs, off) } // 도착 순(서버는 최신순)
    }

    /** 알림 묶음 제안 "카톡 3건, ○○님 외 2명. 읽어드릴까요?" → 대답 듣기. */
    private fun offerNotifs(list: List<InboxItem>) {
        notifSaid += list.map { it.id }
        talk.offer(list, favs)?.let(::runTurn)
    }

    /** 묻고 대답 듣기. 지금 말해도 되는 때(경고·통화·다른 대화 중 아님)에만 쓴다. */
    private fun canAsk() = !quiet && pending == Ask.NONE && talk.step == NotifTalk.Step.NONE && !listening
    private fun askVoice(text: String, a: Ask) { pending = a; speaker.speak(text, ::listenNow) }

    /** 음성 인식 결과(또는 디버그 SAY) 한 곳 입구. 대기 중인 질문이 있으면 그 대답으로, 아니면 알림 대화로. */
    private fun heard(said: String?) {
        listening = false
        val p = pending; pending = Ask.NONE
        // Q3: 운전 중 '급해요/화장실'은 어느 대답 대기에서든 먼저 본다(상시 듣기는 없다 — 듣는 중이거나 디버그 SAY일 때만 들어온다)
        if (urgentNeed(said) && machine.room.let { it != null && it != Room.PARK }) { talk.cancel(); askStep = talk.step; return addRestStop(true) }
        when (p) {
            // 번호를 알면 초안을 읽어 주고 "이대로 보낼까요?"(SEND). 모르면 지금처럼 카드만.
            Ask.LATE -> lateEvent?.let { (ev, m) -> if (yesNo(said) == true) lateTask(ev, m).let { t -> taskDraft(t).let { (n, b) -> sendOrCard(PendingSend("", n, b, t), LATE_DONE_SPEECH, true) } } }
            Ask.RESERVE -> reserve?.let { (n, t) -> if (yesNo(said) == true) { addLocalTask(reserveTask(n, t)); say(RESERVE_DONE_SPEECH) } }
            // 질문이 이미 초안을 읽어 줬으니 '응'이면 바로 발송(한 번 더 묻지 않는다)
            Ask.CALLBACK -> callback?.let { (i, m) -> if (yesNo(said) == true) sendOrCard(PendingSend(i.fromLabel, i.fromNumber, m, callbackTask(i, m)), CALLBACK_DONE_SPEECH, false) }
            // F5: [말하기] 뒤 한 마디. 못 알아들으면 "다시 말씀해 주세요" 한 번만, 그래도 못 알아들으면 끝.
            Ask.INTENT, Ask.INTENT_RETRY -> when (carIntent(said)) {
                CarIntent.URGENT -> addRestStop(true)
                CarIntent.RECENT_CALLS -> say(recentCallsSpeech(cards()))
                CarIntent.NEXT_EVENT -> say(nextEventSpeech(nextEvent(schedule, System.currentTimeMillis())))
                CarIntent.UNKNOWN -> if (p == Ask.INTENT) askVoice(REASK_SPEECH, Ask.INTENT_RETRY)
            }
            Ask.SEND -> sending?.let { s -> if (yesNo(said) == true) doSend(s) else { addLocalTask(s.task); say(SEND_KEEP_SPEECH) } }
            Ask.REST -> when (yesNo(said)) {
                true -> addRestStop(true)
                false -> { restSnoozeUntil = clock.millis() + REST_SNOOZE_MS; restOnce.reset(); restOffer = false }
                null -> {}
            }
            Ask.NONE -> runTurn(talk.hear(said, favs, machine.room == Room.PARK))
        }
    }

    private fun addLocalTask(summary: String) {
        inbox = listOf(InboxItem(LOCAL_ID_BASE + clock.millis(), "내비서", summary, false, System.currentTimeMillis(), "", kind = "task")) + inbox
        saveLocalTasks(this) // 재시작해도 남게
    }

    /** 받는 번호를 알고 동승자 모드가 아니면 음성으로 발송(confirm이면 초안을 읽고 확인), 아니면 할 일 카드만 만들고 "정차하시면" 안내. */
    private fun sendOrCard(s: PendingSend, doneSpeech: String, confirm: Boolean) {
        val n = sendable(s.number)
        if (n == null || passenger) { addLocalTask(s.task); say(doneSpeech); return }
        val ps = PendingSend(s.label, n, s.body, s.task)
        if (confirm && canAsk()) { sending = ps; askVoice(sendConfirmSpeech(s.body), Ask.SEND) } else if (confirm) { addLocalTask(s.task); say(doneSpeech) } else doSend(ps)
    }

    /** 기존 문자 발송 경로(Server.send). 성공하면 "보냈어요", 실패하면 카드로 남긴다. */
    private fun doSend(s: PendingSend) = net.execute {
        val code = Server.send(s.label, s.number, s.body); Log.i("ROOM", "voice send -> $code")
        ui.post { if (code == "ok") say(SENT_SPEECH) else { addLocalTask(s.task); say(SEND_FAIL_SPEECH) } }
    }

    private fun runTurn(t: Turn) {
        t.send?.let { (id, text) -> net.execute { val ok = Server.reply(id, text); Log.i("ROOM", "reply $id -> ${if (ok) "ok" else "FAIL"}"); if (!ok) ui.post { speaker.speak("답장을 못 보냈어요. 잠시 뒤 다시 해 주세요.") } } }
        if (t.done.isNotEmpty()) markDone(this, t.done)
        t.say?.let { speaker.speak(it, if (t.listen) ::listenNow else null) }
        askStep = talk.step
    }

    /** F5 [말하기] 버튼(ACTION_TALK, adb로도 시작 가능): 다른 대화·듣기 중이 아니면 "말씀하세요" 뒤 한 마디 듣기. */
    private fun startTalk() {
        if (listening || pending != Ask.NONE || talk.step != NotifTalk.Step.NONE) return
        if (voice.path == "none") return speaker.speak(NO_VOICE_SPEECH)
        askVoice(TALK_PROMPT, Ask.INTENT)
    }

    /** 음성 인식이 없고(path none) 주행 중이면 대답을 못 받으니 '나중에'(카드로 보관). */
    private fun listenNow() {
        if (voice.path == "none") { if (!touchOk() || pending == Ask.CALLBACK || pending == Ask.SEND) heard("나중에"); return }
        listening = true; voice.listen()
    }

    private fun touchOk() = machine.room == Room.PARK || (adapters.speedKmh ?: 0f) < 10f

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
            workEndTime(workEndHour).let { if (machine.settings.workEnd != it) machine.settings = machine.settings.copy(workEnd = it) } // 설정 화면의 업무 끝 시각
            machine.tick()
            val t = machine.signals.toString()
            if (t != sig) Log.i("ROOM", "signals $t") // 입력 신호가 바뀔 때만
            sig = t
            val a = latch.update(adapters.speedKmh, adapters.limitKmh, adapters.cameraOverspeed, adapters.cautionZone).let { forced ?: it }
            if (a != alert) { alert = a; Log.i("ROOM", "alert $a") }
            // M9: 블루투스 통화 중이면 차 스피커 낭독이 상대에게 들린다 → 통화 끝날 때까지 보류. ponytail: AAOS HFP가 mode에 반영되는지 실차 미확인
            val inCall = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION
            val q = a != Alert.NONE || busyManeuver(adapters.turnOn, machine.signals.gear, adapters.steerDeg) || inCall
            if (q != quiet) { quiet = q; Log.i("ROOM", "quiet $q"); hold.release(q, machine.room)?.let { speaker.speak(it) } }
            if (passenger) { hold.clear(); notifQueue.clear() } // 동승자 모드면 보류분도 버린다
            // 폰 알림: 미루기 조건(경고·깜빡이·후진·큰 조향·통화·피로)이 다 풀리고 대화 중이 아니면 쌓인 것을 묶어서 한 번
            // 대답 없이 남은 묻기(P의 바)도 새 알림이 오면 닫고, 못 읽어 준 알림과 합쳐 다시 묻는다(같은 알림은 한 번만)
            if (!q && (talk.step == NotifTalk.Step.NONE || talk.waiting && !listening) && pending == Ask.NONE && notifQueue.isNotEmpty() && machine.room != null && !passenger && !fatigued(machine.room, tripMin())) {
                offerNotifs((talk.dropStale() + notifQueue).distinctBy { it.id }.sortedBy { it.id }); notifQueue.clear()
            }
            // 자비스처럼 먼저 제안: 정차 중 안 본 새 통화 카드에 기한·회신 요청이 있으면 카드당 한 번 "문자 먼저 보내 드릴까요?"(알림 묻기가 끝난 뒤)
            if (machine.room == Room.PARK && !passenger && notifQueue.isEmpty() && canAsk())
                inbox.firstOrNull { it.id in callFresh && it.id !in seen && it.id !in callbackAsked && callbackMessage(it) != null }?.let { i ->
                    callbackAsked += i.id; val m = callbackMessage(i)!!; callback = i to m; askVoice(callbackAsk(i, m), Ask.CALLBACK) }
            askTouch = touchOk(); voicePath = voice.path
            // 정수 분이라 != 가 곧 "1분 이상 변화". 방 판정 전이면 첫 방 변경이 같이 올린다.
            val e = adapters.etaMin
            if (e != sentEta) machine.room?.let { sentEta = e; sendState(it.name, ++stateGen, true) }
            // 짧은 P 동안은 driveStart가 남아 있으니 주행 중일 때만 센다(M4). 휴식·피로가 같은 순간이면 한 문장만(H10).
            val restNow = restOnce.fire(restDue(driveStart.takeIf { machine.room != Room.PARK }, clock.millis(), restMin, restSnoozeUntil))
            if (restNow) { restOffer = true; if (canAsk()) askVoice(REST_ASK, Ask.REST) else say(REST_SPEECH) }
            // 안전 안내라 동승자 모드에서도. 피로 보관이 켜지면 휴게소 버튼도 같이 띄운다(휴식 기준을 더 길게 잡았어도).
            if (fatigueOnce.fire(fatigued(machine.room, tripMin()))) { restOffer = true; if (!restNow) say(fatigueSpeech(machine.signals.gear)) }
            seat.update(adapters.passengerSeat)?.let { passengerOffer = it && !passenger }
            if (passenger) passengerOffer = false
            adapters.telltale.let { t -> if (t != lastTell) { lastTell = t; telltale = t; if (t != null && machine.room != Room.PARK) say(TELLTALE_SPEECH) } }
            // 바뀌면 10초에 한 번까지, 안 바뀌어도 5분마다 하트비트(서버는 10분 넘게 갱신이 없으면 방을 모른다고 본다)
            val ago = System.currentTimeMillis() - ctxAt
            if ((carCtx().toString() != ctxSent && ago >= 10_000) || ago >= HEARTBEAT_MS) machine.room?.let { sendState(it.name, ++stateGen, false) }
            if (parkRemind.fire(machine.room == Room.PARK && parkedAt?.let { clock.millis() - it >= PARK_REMIND_MS } == true) && !passenger) parkReminder(cards(), seen)?.let(::say)
            // F4: 업무 운전 중 도착 예상이 다음 일정 시작보다 늦으면 일정마다 한 번 묻는다
            if (machine.room == Room.DRIVE_WORK && !passenger && canAsk()) System.currentTimeMillis().let { now ->
                nextEvent(schedule, now)?.takeIf { it.start !in lateAsked && headingTo(machine.signals.destination, it) }?.let { ev ->
                    lateBy(now, e, ev.start)?.let { m -> lateAsked += ev.start; lateEvent = ev to m; askVoice(lateSpeech(m), Ask.LATE) }
                }
            }
            // O4: 식당·주차장류 목적지로 출발하면 한 번 "도착 시각쯤, 예약 문자 초안 만들어 둘까요?"(업무·쉼 방 상관없이)
            val dn = forcedDest ?: adapters.destName
            if (machine.room.let { it != null && it != Room.PARK } && !passenger && e != null && reservable(dn) && canAsk() &&
                reserveOnce.fire(true)) arriveAt(clock.millis(), e, clock.zone).let { t -> reserve = dn!! to t; askVoice(reserveSpeech(dn, t), Ask.RESERVE) }
            if (homeOnce.fire(homeSoon(machine.room, machine.signals.destination, machine.settings, e))) {
                if (!passenger) say(HOME_SOON_SPEECH)
                notifyFamily(e)
            }
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
            val r = p.getInt("rest_min", REST_DEFAULT_MIN).takeIf { m -> m != 1 } ?: REST_DEFAULT_MIN
            // H9: 동승자 모드는 저장하지 않는다(이번 운전만). M5·H7: 본 카드·넘긴 건은 재시작해도 유지.
            val sn = p.getString("seen", "").orEmpty().split(",").mapNotNull { it.toLongOrNull() }.toSet()
            val nd = p.getString("notif_done", "").orEmpty().split(",").mapNotNull { it.toLongOrNull() }.toSet()
            val we = p.getInt("work_end", 18); val lt = decodeLocalTasks(p.getString("local_tasks", ""))
            val sz = decodeDeferred(p.getString("snoozed", ""))
            val cd = p.getString("card_done", "").orEmpty().split(",").mapNotNull { it.toLongOrNull() }.toSet()
            val df = decodeDeferred(p.getString("deferred", "")); val fw = p.getBoolean("forward_ok", false)
            val fv = p.getString("fav_senders", "").orEmpty(); ui.post { favs = parseFavs(fv) }
            val fd = p.getLong("family_day", -1); ui.post { familyDay = fd }
            val sd = p.getBoolean("setup_done", false); val md = p.getString("mode", "ai") ?: "ai"; val bd = p.getLong("brief_day", -1)
            ui.post { restMin = r; workEndHour = we; inbox = (lt.filter { t -> inbox.none { it.id == t.id } } + inbox).sortedByDescending { it.id }; seen = sn + seen; notifDone = nd + notifDone; cardDone = cd + cardDone; snoozed = sz + snoozed; deferred = df + deferred; forwardOk = forwardOk || fw; setupDone = sd; mode = md; briefDay = bd }
        }
        latch = AlertLatch(Clock.systemDefaultZone())
        machine = RoomStateMachine(clock) // settings = 기본 상수. 설정 화면이 machine.settings를 덮어쓴다.
        machine.onChange = { from, to ->
            Log.i("ROOM", "$from -> $to at ${LocalDateTime.now(clock).withNano(0)}")
            room = to.name
            val now = clock.millis()
            if (to == Room.PARK) {
                parkedAt = now; restOffer = false; hold.clear(); parkRemind.reset()
                // 정차 요약: 이번 실행에 와서 아직 안 알리고 안 본 알림(쉼 방 비즐겨찾기·피로·동승자 등으로 미룬 것)
                notifQueue.clear(); talk.cancel(); listening = false; pending = Ask.NONE
                val left = inbox.filter { it.id in notifFresh && it.id !in notifSaid && it.id !in seen && !isAd(it) }.sortedBy { it.id }
                if (left.isNotEmpty() && !passenger) offerNotifs(left)
            }
            else {
                val parked = parkedAt?.let { now - it }; parkedAt = null
                if (from == Room.PARK && talk.step != NotifTalk.Step.NONE) { talk.cancel(); askStep = talk.step; listening = false } // P에서 남겨 둔 바는 출발하면 닫는다(카드는 남음)
                // M4: 2분 미만 P는 같은 운전 — 휴식·피로·집 문턱 1회 안내와 운전 시작 시각을 유지
                if (newDrive(parked)) { homeOnce.reset(); reserveOnce.reset(); restOnce.reset(); fatigueOnce.reset(); trip.reset(); driveStart = now; restSnoozeUntil = 0 }
                else Log.i("ROOM", "short park ${parked!! / 1000}s -> same drive")
                // H9: 오래 세운 뒤의 출발이면 그 전에 켠 동승자 모드는 끈다
                if (passenger && parked != null && parked >= PASSENGER_RESET_PARK_MS && passengerAt < now - parked) { togglePassenger(false); say("동승자 모드를 껐어요") }
            }
            sentEta = adapters.etaMin
            sendState(to.name, ++stateGen, true)
            if (!passenger) roomSpeech(to)?.takeIf { declared.shouldSay(today(), to) }?.let(::say)
            if (to == Room.DRIVE_WORK && !passenger && briefDay != today()) briefCarryOver()
            getSystemService(NotificationManager::class.java).notify(1, notification(room))
        }
        machine.onClockBack = { b -> driveStart = driveStart?.minus(b); parkedAt = parkedAt?.minus(b); Log.i("ROOM", "clock back ${b / 1000}s") } // 짧은 정차·trip_min이 음수가 되지 않게
        adapters = CarAdapters(applicationContext, machine).also { it.start() }
        addHomeFavorite = { runCatching { adapters.navi?.requestNavigationScreen(NavigationScreenRequest.AddHomeFavorite) }.onFailure { Log.w("ROOM", "add home FAIL $it") } }
        adapters.onNaviReady = { pendingRoute?.let { pendingRoute = null; Log.i("ROOM", "navi ready -> route"); it() } }
        speaker = Speaker(applicationContext).also { it.start() }
        voice = Voice(applicationContext, ::heard).also { it.start() }
        answer = { if (it == "답장하기") { if (!listening) listenNow() } else voice.inject(it) } // '답장하기'는 대답이 아니라 다시 듣기
        ui.post(ticker)
        ui.post(poller)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) registerDebugRoute()
    }

    /** H1: 집 문턱에서 하루 한 번 서버에 가족 문자 요청. 번호가 없으면 서버가 건너뛴다. 결과는 로그만. */
    private fun notifyFamily(etaMin: Int?) {
        val m = etaMin ?: return
        val t = today(); if (familyDay == t) return
        familyDay = t
        net.execute { prefs.edit().putLong("family_day", t).apply(); Log.i("ROOM", "family eta $m -> ${Server.postFamilyEta(m)}") }
    }

    /** H7: 오늘 첫 업무 운전에서 '어제 넘긴 전화 N건' 한 문장. 하루 1회. */
    private fun briefCarryOver() {
        val t = today()
        val c = carryOver(cards(), deferred, seen, t)
        carrySpeech(c, deferred, t)?.let { briefDay = t; net.execute { prefs.edit().putLong("brief_day", t).apply() }; say(it) }
    }

    private fun markForwarded() {
        if (forwardOk) return
        confirmForward(this); Log.i("ROOM", "forward ok")
    }

    private fun notification(r: String) = Notification.Builder(this, CH)
        .setSmallIcon(android.R.drawable.ic_menu_directions)
        .setContentTitle("내비서 실행 중 — 방: $r")
        .setOngoing(true).build()

    // 디버그 빌드 전용: adb shell am broadcast -a ztw.carbiseo.ROUTE --ef lat 37.4979 --ef lng 127.0276 [--es name "판교 한정식" = O4 예약 초안용 목적지 이름]
    private fun registerDebugRoute() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val lat = i.getFloatExtra("lat", 0f).toDouble()
                val lng = i.getFloatExtra("lng", 0f).toDouble()
                forcedDest = i.getStringExtra("name")
                Log.i("ROOM", "debug requestRoute $lat,$lng name=$forcedDest")
                adapters.navi?.requestRoute(RouteInfo(lat, lng, null, null, forcedDest ?: "debug", "", "", "", RouteOption.RECOMMENDED, "", false))
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
        // adb shell am broadcast -a ztw.carbiseo.SIGNAL --ei left 4 (초록불까지 남은 초). 5초 넘게 남은 값이 오면 다음 신호로 보고 다시 말할 수 있게.
        // ponytail: 실시간 신호 API는 강릉시만 공개(data.go.kr 15140011, 키 없음). 키 생기면 서버에서 교차로별 남은 초 → 같은 입력으로.
        val sig = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val left = i.getIntExtra("left", -1)
                if (left > 5) greenSaid = false
                val go = !greenSaid && greenSoon(left, machine.signals.gear, adapters.speedKmh, quiet || listening || talk.step != NotifTalk.Step.NONE)
                Log.i("ROOM", "debug signal left=$left -> ${if (go) "say" else "skip"}")
                if (go) { greenSaid = true; speaker.speak("곧 초록불이에요, 출발 준비하세요.") }
            }
        }
        // adb shell am broadcast -a ztw.carbiseo.LOC --ef lat 37.41 --ef lng 127.09 [--ez home true = 그 좌표를 집으로] (lat 0 = 해제, 내비 실제 위치로)
        val loc = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val p = LatLng(i.getFloatExtra("lat", 0f).toDouble(), i.getFloatExtra("lng", 0f).toDouble()).takeIf { it.lat != 0.0 }
                if (i.getBooleanExtra("home", false)) p?.let { machine.settings = machine.settings.copy(home = it) }
                else { adapters.forcedLocation = p; p?.let { machine.update { copy(location = it) } } }
                Log.i("ROOM", "debug loc force=$p home=${machine.settings.home}")
            }
        }
        // adb shell am broadcast -a ztw.carbiseo.TIME --es at 2026-11-02T08:30 (none = 실제 시각으로). 요일·시각 시뮬
        val tm = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val at = runCatching { LocalDateTime.parse(i.getStringExtra("at")) }.getOrNull()
                clock.offsetMs = at?.let { it.atZone(clock.zone).toInstant().toEpochMilli() - System.currentTimeMillis() } ?: 0
                machine.tick()
                Log.i("ROOM", "debug time -> ${LocalDateTime.now(clock).withNano(0)} ${LocalDate.now(clock).dayOfWeek} room=${machine.room}")
            }
        }
        // adb shell am broadcast -a ztw.carbiseo.SAY --es t "읽어줘" (음성 인식 결과 대신 넣기)
        val say = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { Log.i("ROOM", "debug say"); voice.inject(i.getStringExtra("t").orEmpty()) }
        }
        // adb shell am broadcast -a ztw.carbiseo.CALLCARD --es from 김부장 --es num 01000000000 --es sum "3시 30분까지 연락 주세요" (새 통화 카드 주입, 서버 호출 없음. 정차 상태에서 확인)
        val cc = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val id = LOCAL_ID_BASE + clock.millis()
                inbox = listOf(InboxItem(id, i.getStringExtra("from").orEmpty(), i.getStringExtra("sum").orEmpty(), false, System.currentTimeMillis(), "", fromNumber = i.getStringExtra("num").orEmpty())) + inbox
                callFresh += id; Log.i("ROOM", "debug callcard $id")
            }
        }
        // adb shell am broadcast -a ztw.carbiseo.HEAR (앱 files/hear.pcm을 다음 듣기 때 STT 엔진에 넣기, run-as로 복사, 실측용)
        val hear = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { runCatching { java.io.File(filesDir, "hear.pcm").readBytes() }.onSuccess { Log.i("ROOM", "debug hear ${it.size}B"); voice.armed = it } }
        }
        for ((rc, action) in listOf(cc to "ztw.carbiseo.CALLCARD", hear to "ztw.carbiseo.HEAR", say to "ztw.carbiseo.SAY", wp to "ztw.carbiseo.WP", r to "ztw.carbiseo.ROUTE", al to "ztw.carbiseo.ALERT", tr to "ztw.carbiseo.TRIP", loc to "ztw.carbiseo.LOC", tm to "ztw.carbiseo.TIME", sig to "ztw.carbiseo.SIGNAL")) {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(rc, IntentFilter(action), Context.RECEIVER_EXPORTED) else registerReceiver(rc, IntentFilter(action))
            receivers += rc
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REST) addRestStop()
        if (intent?.action == ACTION_TALK) startTalk()
        if (intent?.action == ACTION_GO) intent.run {
            val lat = getDoubleExtra("lat", 0.0); val lng = getDoubleExtra("lng", 0.0); val name = getStringExtra("name") ?: ""
            Log.i("ROOM", "go $name $lat,$lng")
            route(LatLng(lat, lng), name)
        }
        return START_STICKY
    }

    /** S2 버튼: 경로상 앞쪽 휴게소를 첫 경유지로. 목적지가 없으면 그 휴게소로 경로를 연다. */
    private fun addRestStop(speak: Boolean = false) {
        restOffer = false
        val s = machine.signals
        val n = adapters.navi ?: return
        val stop = nearestRestStop(s.location, s.destination)
        if (stop == null) { // H11: 앞쪽 휴게소를 모르면 경유지를 넣지 않고 내비 장소 검색을 연다(지나온 곳으로 유턴 방지)
            Log.i("ROOM", "rest stop none ahead -> navi search")
            runCatching { n.requestNavigationScreen(NavigationScreenRequest.SearchPlace("휴게소")) }.onFailure { Log.w("ROOM", "navi search FAIL $it") }
            return say("앞쪽 휴게소 정보가 없어서 내비에 휴게소 검색을 띄웠어요")
        }
        Log.i("ROOM", "rest stop ${stop.name} dest=${s.destination}")
        if (s.destination != null) n.addWaypoint(RequestWaypointInfo(stop.at.lat, stop.at.lng, stop.name, WaypointIndex.FIRST, "", "", "", ""))
        else route(stop.at, stop.name)
        if (speak) say(restStopSpeech(stop, s.destination != null))
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
        ui.removeCallbacks(ticker); ui.removeCallbacks(poller); net.shutdown(); speaker.stop(); voice.stop(); adapters.stop(); receivers.forEach { unregisterReceiver(it) }; super.onDestroy()
    }

    companion object {
        private const val CH = "room"
        private const val HEARTBEAT_MS = 5 * 60_000L
        const val ACTION_REST = "ztw.carbiseo.REST"
        const val ACTION_TALK = "ztw.carbiseo.TALK" // F5: [말하기] 버튼
        const val ACTION_GO = "ztw.carbiseo.GO" // F7: extras lat, lng, name
        private const val NAVI_PKG = "ai.umos.maps.android.navigation.app" // Pleos 내비 앱(26 실측)
        var restOffer by mutableStateOf(false); private set // S2: 화면에 휴게소 버튼
        var restMin by mutableIntStateOf(REST_DEFAULT_MIN) // 연속 주행 기준(분). 설정 화면이 바꾼다.
        var workEndHour by mutableIntStateOf(18) // 업무 끝 시각(시, 17~20). 설정 화면이 바꾼다. 방 판정·업무 외 판정이 이 값을 쓴다.
        fun setWorkEnd(c: Context, h: Int) { workEndHour = h; save(c) { putInt("work_end", h) } }
        var passenger by mutableStateOf(false); private set // H3: 켜면 업무 TTS(부재중 낭독)·쉼 방 선언(H1)을 끈다. 휴식 제안(S2)은 안전이라 유지.
        var passengerAt = 0L; private set
        /** H9: 이번 운전만. 저장하지 않고, 30분 넘게 세운 뒤 출발하면 자동 해제. */
        fun togglePassenger(on: Boolean) { passenger = on; passengerAt = clock.millis() }
        val clock = ShiftClock() // 프로세스 공통 시각(디버그 TIME 브로드캐스트만 offset을 바꾼다)
        fun dropLocal(c: Context, id: Long) { inbox = inbox.filter { it.id != id }; saveLocalTasks(c) }
        fun saveLocalTasks(c: Context) { val s = encodeLocalTasks(inbox); save(c) { putString("local_tasks", s) } }
        var seen by mutableStateOf(setOf<Long>()); private set // M5: 주차 화면에서 연 카드 id. prefs 저장(최근 300)
        var deferred by mutableStateOf(mapOf<Long, Long>()); private set // H7: 넘긴 통화 id -> 넘긴 날
        var forwardOk by mutableStateOf(false); private set // H13: 착신 설정 완료(사용자 체크 또는 실제 착신 1회)
        var serviceNumber by mutableStateOf(""); private set // 착신 받을 070(서버 환경값). 코드에 두지 않는다
        var setupDone by mutableStateOf(true); private set // 첫 실행 안내를 닫았는지(로드 전엔 숨김)
        var mode by mutableStateOf("ai") // 응대 모드(설정 화면이 바꾼다)
        private fun save(c: Context, f: android.content.SharedPreferences.Editor.() -> Unit) =
            Thread { c.getSharedPreferences("car_ui", MODE_PRIVATE).edit().apply(f).apply() }.start()
        fun markSeen(c: Context, id: Long) {
            if (id in seen) return
            seen = seen + id
            val s = seen.sorted().takeLast(300).joinToString(",")
            save(c) { putString("seen", s) }
        }
        var notifDone by mutableStateOf(setOf<Long>()); private set // 읽어 준 폰 알림 id: 카드·건수에서 뺀다. prefs 저장(최근 300)
        fun markDone(c: Context, ids: List<Long>) {
            notifDone = notifDone + ids
            val s = notifDone.sorted().takeLast(300).joinToString(",")
            save(c) { putString("notif_done", s) }
        }
        var cardDone by mutableStateOf(setOf<Long>()); private set // F3: [완료]한 통화·할 일 카드 id. prefs 저장(최근 300)
        fun markCardDone(c: Context, id: Long) {
            cardDone = cardDone + id
            val s = cardDone.sorted().takeLast(300).joinToString(",")
            save(c) { putString("card_done", s) }
        }
        var snoozed by mutableStateOf(mapOf<Long, Long>()); private set // H2: [내일 아침에] 카드 id -> 미룬 날(그날만 숨김). prefs 저장
        fun snooze(c: Context, id: Long) {
            val (d, z, s) = snoozeCard(deferred, snoozed, seen, id, LocalDate.now(clock).toEpochDay())
            deferred = d; snoozed = z; seen = s
            val ds = encodeDeferred(d); val zs = encodeDeferred(z); val ss = s.sorted().takeLast(300).joinToString(",")
            save(c) { putString("deferred", ds).putString("snoozed", zs).putString("seen", ss) }
        }
        /** 화면·건수·정차 알림이 보는 카드 목록: 읽어 준 알림·[완료]한 카드·오늘 미룬 카드를 뺀 것. */
        fun cards(): List<InboxItem> = hideCards(unread(inbox, notifDone), cardDone, snoozed, LocalDate.now(clock).toEpochDay())
        fun confirmForward(c: Context) { forwardOk = true; save(c) { putBoolean("forward_ok", true) } }
        fun finishSetup(c: Context) { setupDone = true; save(c) { putBoolean("setup_done", true) } }
        fun reopenSetup(c: Context) { setupDone = false; save(c) { putBoolean("setup_done", false) } }
        var room by mutableStateOf("(판정 전)"); private set
        var sig by mutableStateOf(""); private set
        var inbox by mutableStateOf(listOf<InboxItem>()); private set // id 내림차순
        var alert by mutableStateOf(Alert.NONE); private set
        var schedule by mutableStateOf(listOf<Event>()); private set // F6 데모 일정
        var offline by mutableStateOf(false); private set // #23 서버 폴링 실패
        var passengerOffer by mutableStateOf(false) // C⑤ 조수석 착석 감지 → "동승자 모드로 바꿀까요?"
        var telltale by mutableStateOf<String?>(null); private set // C⑦ 빨간 경고등
        var homeSrc by mutableStateOf("기본값(내비 즐겨찾기 없음)") // C① 집 좌표 출처(설정 화면 표시)
        var addHomeFavorite: (() -> Unit)? = null // C① 설정 화면 버튼 → 내비 '집 즐겨찾기 추가' 화면
        // 폰 알림(35): 쉼 방에도 바로 알릴 발신자(설정, 기본 빈 목록), 대답 대기 단계·듣는 중·화면 버튼 허용(P·저속)·음성 경로
        var favs by mutableStateOf(setOf<String>())
        var askStep by mutableStateOf(NotifTalk.Step.NONE); private set
        var listening by mutableStateOf(false); private set
        var askTouch by mutableStateOf(true); private set
        var voicePath by mutableStateOf("none"); private set
        var answer: ((String) -> Unit)? = null // 화면 버튼 → 음성 대답과 같은 처리
    }
}
