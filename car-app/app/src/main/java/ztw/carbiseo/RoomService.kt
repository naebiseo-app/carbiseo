package ztw.carbiseo

import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.Executors

/** 방 판정 본체. 내비가 앞에 와도 Activity와 무관하게 돈다. 화면은 companion의 room/sig를 읽는다(같은 프로세스). */
class RoomService : Service() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var strip: DriveStrip
    private var canOverlay = false // 폴링마다 다시 읽는다(메인 스레드에서만)
    private lateinit var machine: RoomStateMachine
    private lateinit var adapters: CarAdapters
    private val receivers = mutableListOf<BroadcastReceiver>()
    private lateinit var speaker: Speaker
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val net = Executors.newSingleThreadExecutor() // 상태·발송 등 쓰기 호출은 여기서만(순서 보장, 메인 차단 방지)
    private var wake: Wake? = null // 이름 호출 대기(스위치 켜짐·마이크 허용·모델 있음·시동 켜짐일 때만 있다). 메인 스레드에서만
    private var micFgs = false     // 포그라운드 서비스 종류에 마이크가 들어갔나
    private val wakeModelOk by lazy { Wake.hasModel(applicationContext) }
    private val chatNet = Executors.newSingleThreadExecutor() // 말동무 AI 호출(최대 8초)만. 상태 전송·문자 발송을 막지 않는다
    @Volatile private var alive = true
    private var pollN = 0 // 메인 스레드에서만
    private val pollNet = Executors.newSingleThreadExecutor() // 폴링(읽기)만. 쓰기와 줄 서지 않는다
    private var stateGen = 0       // 방 변경마다 +1. 재시도는 최신 방일 때만.
    private var sentEta: Int? = null // 마지막으로 올린 남은 분. 분이 바뀔 때만 다시 올린다.
    private var okState: Pair<String, Int?>? = null // 서버가 마지막으로 받은 (방, 남은 분)
    private var since = 0L
    private var firstPoll = true   // 첫 폴링은 기존 항목을 채우기만 하고 읽지 않는다.
    private lateinit var latch: AlertLatch
    private val hold = SpeechHold { mode }
    private var forced: Alert? = null // 디버그 브로드캐스트로 강제한 값
    private var forcedTrip: Int? = null // 디버그: 트립 주행시간(분) 강제
    private var forcedSoc: Int? = null // 디버그: 배터리 잔량(%) 강제 (먼저 말 걸기의 충전 제안만 읽는다)
    private var lastMealOffer: Long? = null; private var lastChargeOffer: Long? = null // 이번 운전에서 먼저 제안한 시각(시뮬 시각). 새 운전에서 비운다
    private var parkMemoText: String? = null // "주차 지하 2층"으로 적어 둔 메모. 새 운전에서 비운다

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
    private val alightOnce = Once() // 정차 한 번당 하차 위치 갱신 한 번(PARK 진입 때 reset)
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
    private enum class Ask { NONE, LATE, REST, RESERVE, CALLBACK, SEND, INTENT, NEAR_FIND, NEAR_GO, PARK_CALL, PARK_MEMO }
    private class PendingSend(val label: String, val number: String, val body: String, val task: String) // 보낼 문자(label·number·body)와 못 보냈을 때 남길 할 일 본문
    private var pending = Ask.NONE
    private val chatHistory = mutableListOf<Pair<String, String>>() // 말동무 대화 맥락(메모리만, 저장·로그 없음). 메인 스레드에서만
    private var lateEvent: Pair<Event, Int>? = null
    private val lateAsked = mutableSetOf<Long>() // 이미 물은 일정(start)
    private val reserveOnce = Once() // O4: 운전 1회당 한 번
    private val callFresh = mutableSetOf<Long>() // 이번 실행에 새로 온 통화 카드 id
    private val callbackAsked = mutableSetOf<Long>() // 문자 먼저 보낼지 이미 물은 카드(카드당 한 번)
    private var callback: Pair<InboxItem, String>? = null
    private var sending: PendingSend? = null
    private var reserve: Pair<String, String>? = null // 예약 인원을 물은 (목적지 이름, 도착 HH:MM)
    private val parkWait = mutableSetOf<Long>() // reserveWait 중 주차 문의인 행 id(결과 카드 제목 구분)
    private val reserveWait = mutableMapOf<Long, Pair<String, Long>>() // 전화를 건 예약 서버 행 id -> (가게 이름, 건 시각 elapsedRealtime). 결과를 한 번만 읽고 뺀다. 메인 스레드에서만
    private val arriveSaid = mutableSetOf<Long>() // 도착 전 브리핑을 이미 한 일정(start). prefs arrive_said에 저장
    private var forcedDest: String? = null // 디버그 ROUTE의 --es name (에뮬은 내비가 목적지 이름을 안 줄 수 있어서)
    private var familyDay = -1L // 가족에게 도착 예상을 보낸 날(하루 한 번)
    private var restSnoozeUntil = 0L // 휴식 제안에 '아니'라고 한 뒤 30분(시뮬 시각)
    private var replayRun: ReplayRun? = null // O3 재현 통화 진행 중(= 통화 중 상태). 메인 스레드에서만
    private var replayAt = 0L // 대본을 받은 시각(SystemClock.elapsedRealtime)

    // 단일 스케줄러: 이전 폴링이 끝난 뒤에만 다음을 예약한다(느린 서버에서 요청이 쌓여 카드가 늦게 뜨는 것 방지, L2).
    // 상태 전송·문자 발송과 줄 서지 않게 폴링만 따로 한 스레드(pollNet)에서 돈다.
    private val poller = object : Runnable {
        override fun run() {
            if (!alive) return
            canOverlay = android.provider.Settings.canDrawOverlays(this@RoomService)
            val t0 = SystemClock.elapsedRealtime()
            val slow = pollN++ % SLOW_EVERY == 0 // 일정·착신 설정은 거의 안 바뀌니 30초에 한 번(카드는 매번)
            val inPark = machine.room == Room.PARK // 하차 카드의 할 일을 폰에서 끝냈는지는 세운 동안만 본다
            val rw = reserveWait.keys.minOrNull() // 예약 전화 결과를 기다리는 중이면 그 행부터 다시 읽는다(서버가 행을 제자리에서 바꿔서 since 폴링엔 안 보인다)
            pollNet.execute {
                try {
                    rw?.let { w -> Server.fetchInbox(w - 1)?.let { (rows, _) -> ui.post { onReserveRows(rows) } } }
                    if (inPark) Server.fetchParkedDone()?.let { ids -> ui.post { onParkDone(ids) } }
                    val r = Server.fetchInbox(since)
                    ui.post {
                        offline = r == null; r?.let { (items, minId) -> inbox = pruneGone(inbox, minId); onInbox(items) } // 끝나는 대로 다시 시도한다
                        // 오프라인 중 상태 전송이 다 실패했으면 서버가 옛 방에 멈춰 있다 → 연결되면 지금 상태를 다시 올린다
                        val cur = machine.room?.let { it.name to sentEta }
                        if (r != null && cur != null && cur != okState) { Log.i("ROOM", "state resync $cur (server had $okState)"); sendState(cur.first, ++stateGen, false) }
                    }
                    if (slow) Server.fetchSchedule()?.let { r -> ui.post { schedule = r } }
                    if (slow && (!forwardOk || serviceNumber.isEmpty())) Server.fetchConfig()?.let { (n, fw) -> ui.post { serviceNumber = n; if (fw) markForwarded() } }
                } finally {
                    ui.post { if (alive) ui.postDelayed(this, maxOf(0L, POLL_MS - (SystemClock.elapsedRealtime() - t0))) } // Worker 요청 하루 약 3.5만, 무료 10만 안
                }
            }
        }
    }

    /** 폰에서 끝낸 일(wire id): 서버 행은 [완료]와 같은 cardDone 처리, 차에서 만든 카드는 dropLocal. 열린 하차 카드에서도 뺀다. */
    private fun onParkDone(wire: List<Long>) {
        val ids = wire.map(::parkLocalId).toSet()
        for (id in ids) if (id < LOCAL_ID_BASE) { if (id !in cardDone) markCardDone(this, id) } else if (inbox.any { it.id == id }) dropLocal(this, id)
        parkCard = parkCard?.let { c -> if (c.tasks.any { it.id in ids }) c.copy(tasks = c.tasks.filter { it.id !in ids }) else c }
    }

    private fun onInbox(fetched: List<InboxItem>) {
        val got = fetched.filter { it.id > since }
        val initial = firstPoll; firstPoll = false
        since = nextSince(since, got)
        if (got.isEmpty()) return
        // 서버가 '전화 왔어요'(ringing) 카드를 요약 카드로 교체: 옛 카드는 목록·새 통화·본 카드·넘긴 건에서 뺀다(새 카드는 따로 새로 들어온다)
        val rep = replacedIds(got)
        if (rep.isNotEmpty()) {
            inbox = inbox.filter { it.id !in rep }; callFresh -= rep; callbackAsked -= rep; seen = seen - rep
            if (deferred.keys.any { it in rep }) { deferred = deferred - rep; val s = encodeDeferred(deferred); net.execute { prefs.edit().putString("deferred", s).apply() } }
            Log.i("ROOM", "replaced ringing cards $rep")
        }
        val fresh = got.filter { it.id !in rep }
        // O3 재현 통화: 서버가 내린 대본(kind=replay, 화면엔 안 보임). 재시작 직후 첫 폴링의 옛 대본은 재생하지 않는다.
        if (!initial) fresh.filter { it.kind == "replay" }.maxByOrNull { it.id }?.let { r -> parseReplay(r.raw)?.let { startReplay(it) } }
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
            val plain = fresh.filter { it.replaces == 0L } // 교체 요약 카드는 같은 바로 연결 전화를 다시 알리지 않는다
            if (fatigued(machine.room, tripMin())) directSpeech(plain, mode)?.let(::say)
            else hold.offerItems(plain, machine.room, quiet)?.let { speaker.speak(it) }
            replaceSpeech(fresh, machine.room, mode)?.let(::say) // 요약만 한 번
        }
        if (!initial) callFresh += fresh.filter { it.kind == "call" }.map { it.id }
        if (!initial) fresh.filter { it.kind == "notif" }.let { n -> notifFresh += n.map { it.id }; notifQueue += notifNow(n.sortedBy { it.id }, machine.room, favs, off) } // 도착 순(서버는 최신순)
    }

    /** O3: 대본을 받으면 방(정차·운전) 상관없이 '통화 중'으로 시작. 진행 중이면 새 대본으로 바꾼다. */
    private fun startReplay(s: ReplayScript) {
        Log.i("ROOM", "replay start ${s.title} lines=${s.lines.size} room=${machine.room}")
        replayRun = ReplayRun(s); replayAt = SystemClock.elapsedRealtime(); replay = ReplayView(s.title)
    }

    /** O3 틱: 시각이 된 줄을 순서대로. 읽을 문장은 say(경고·깜빡이 중이면 보류)로, 끝나면 통화 중 상태를 푼다(통화 정리 카드는 서버가 시각에 맞춰 내려 준다). */
    private fun tickReplay() {
        val r = replayRun ?: return
        val e = SystemClock.elapsedRealtime() - replayAt
        for (l in r.due(e)) {
            val a = replayAct(replay ?: ReplayView(r.script.title), l, machine.room, passenger)
            replay = a.view; a.speech?.let(::say)
        }
        if (r.done(e)) { replayRun = null; replay = null; Log.i("ROOM", "replay end") }
    }

    /** 알림 묶음 제안 "카톡 3건, ○○님 외 2명. 읽어드릴까요?" → 대답 듣기. */
    private fun offerNotifs(list: List<InboxItem>) {
        notifSaid += list.map { it.id }
        talk.offer(list, favs)?.let(::runTurn)
    }

    /** 묻고 대답 듣기. 지금 말해도 되는 때(경고·통화·다른 대화 중 아님)에만 쓴다. */
    private var nearKind: NearKind? = null // 근처 찾기 제안 중인 종류
    private var nearHit: NearPlace? = null // 찾아서 "들를까요?" 하고 묻는 가게
    private var reservePlace: LatLng? = null // 근처 찾기 뒤 예약 흐름이면 그 가게 좌표(callReserve가 destination 대신 쓰고 비운다)
    private fun canAsk() = !quiet && replayRun == null && pending == Ask.NONE && talk.step == NotifTalk.Step.NONE && !listening
    private var lastAsk = ""
    private val reask = Reask()
    private fun askVoice(text: String, a: Ask) { reask.reset(); choice = null; lastAsk = text; pending = a; speaker.speak(text, ::listenNow) }

    /** 음성 인식 결과(또는 디버그 SAY) 한 곳 입구. 대기 중인 질문이 있으면 그 대답으로, 아니면 알림 대화로. */
    private fun heard(said: String?) {
        listening = false
        val p = pending; pending = Ask.NONE
        // Q3: 운전 중 '급해요/화장실'은 어느 대답 대기에서든 먼저 본다(상시 듣기는 없다 — 듣는 중이거나 디버그 SAY일 때만 들어온다)
        if (urgentNeed(said) && machine.room.let { it != null && it != Room.PARK }) { talk.cancel(); askStep = talk.step; return addRestStop(true, true) }
        when (p) {
            // Q1 조용한 비서: 못 알아들으면 한 번만 다시 묻고, 그다음은 화면 2택(unsure). 번호를 알면 초안을 읽어 주고 "이대로 보낼까요?"(SEND). 모르면 지금처럼 카드만.
            Ask.LATE, Ask.CALLBACK, Ask.SEND, Ask.REST, Ask.NEAR_FIND, Ask.NEAR_GO, Ask.PARK_CALL -> when (voiceCmd(said)) {
                Cmd.Yes -> answerYn(p, true)
                Cmd.No, Cmd.Later -> answerYn(p, false) // '나중에'는 이 질문들엔 '아니'
                else -> when (p) {
                    Ask.REST, Ask.NEAR_GO -> unsure(p, "들르기" to { answerYn(p, true) }, "괜찮아요" to { answerYn(p, false) }, { answerYn(p, false) })
                    Ask.PARK_CALL -> unsure(p, "물어보기" to { answerYn(p, true) }, "괜찮아요" to { answerYn(p, false) }, { answerYn(p, false) })
                    Ask.NEAR_FIND -> unsure(p, "찾기" to { answerYn(p, true) }, "괜찮아요" to { answerYn(p, false) }, { answerYn(p, false) })
                    else -> unsure(p, "보내기" to { answerYn(p, true) }, "그만두기" to { answerYn(p, false) }, { answerYn(p, false) })
                }
            }
            // 예약 인원: 인원을 읽으면 그것이 승인(바로 전화), '아니·나중에'는 아무 일도 안 한다. 두 번 못 알아들으면 전화 없이 할 일 카드로(화면 2택 없음).
            Ask.RESERVE -> reserve?.let { (n, t) ->
                val c = voiceCmd(said); val party = partyOf(said)
                when {
                    c == Cmd.No || c == Cmd.Later -> reservePlace = null
                    party != null -> callReserve(n, t, party)
                    reask.again() -> { pending = p; speaker.speak(REASK_SPEECH, ::listenNow) }
                    else -> { reservePlace = null; addLocalTask(reserveTask(n, t)); say(RESERVE_KEEP_SPEECH) }
                }
            }
            // F5: [말하기] 뒤 한 마디. 못 알아들으면 "다시 말씀해 주세요" 한 번만, 그다음은 화면 2택(주행 중엔 그냥 끝).
            // S1 피로 중엔 급해·화장실만 통과, 업무 말은 한 문장으로 거절(fatigueGate)
            Ask.INTENT -> carIntent(said).let { ci ->
                if (ci != CarIntent.UNKNOWN) runIntent(ci, said) else {
                    val unk = { unsure(p, "아까 전화" to { say(recentCallsSpeech(cards())) }, "다음 일정" to { say(nextEventSpeech(nextEvent(schedule, System.currentTimeMillis()))) }, {}, "어떤 걸 알려 드릴까요?") }
                    if (said.isNullOrBlank()) unk() else chatOr(said, unk)
                }
            }
            // 하차 카드 [고치기]: 답한 문장을 40자로 잘라 그대로 메모로. 못 들으면 한 번만 다시 묻고 그다음은 접는다
            Ask.PARK_MEMO -> when {
                !said.isNullOrBlank() -> saveParkMemo(said.trim().take(40))
                reask.again() -> { pending = p; speaker.speak(REASK_SPEECH, ::listenNow) }
            }
            Ask.NONE -> {
                // 알림 묻기(제안·보낼까요)도 같은 규칙: 못 알아들으면 한 번만 다시 묻고, 그다음은 P의 화면 버튼(읽어줘/나중에·보내/취소)·주행 중엔 카드로
                val st = talk.step
                if ((st == NotifTalk.Step.OFFER || st == NotifTalk.Step.CONFIRM) && voiceCmd(said) == Cmd.Unknown && reask.again()) speaker.speak(REASK_SPEECH, ::listenNow)
                else runTurn(talk.hear(said, favs, machine.room == Room.PARK))
            }
        }
    }

    /** [말하기]·이름 호출·Gleo가 같이 쓰는 의도 실행. 피로 중 업무는 여기서 거절한다. */
    private fun runIntent(ci: CarIntent, said: String? = null) {
        fatigueGate(ci, fatigued(machine.room, tripMin()))?.let { say(it); return }
        when (ci) {
            CarIntent.URGENT -> addRestStop(true, true)
            CarIntent.RECENT_CALLS -> say(recentCallsSpeech(cards()))
            CarIntent.NEXT_EVENT -> say(nextEventSpeech(nextEvent(schedule, System.currentTimeMillis())))
            CarIntent.RESERVE -> reserveIntent(said)
            CarIntent.PARK_MEMO -> parkMemo(said)?.let(::saveParkMemo)
            CarIntent.PARKING -> parkingIntent()
            CarIntent.NEARBY -> nearWish(said)?.let { nearKind = it; askVoice(nearAskSpeech(it), Ask.NEAR_FIND) }
            CarIntent.UNKNOWN -> {}
        }
    }

    /** 주차 메모 저장: 열려 있는 하차 카드에도 바로 반영하고 서버에 다시 올린다. */
    private fun saveParkMemo(memo: String) {
        parkMemoText = memo; parkCard = parkCard?.copy(memo = memo)
        machine.signals.location?.let { l -> postParked(l); say(PARK_MEMO_SPEECH) } ?: say(NEAR_NO_LOC_SPEECH)
    }

    /** 주차 기록을 서버에 한 번: 위치·메모·아직 안 끝난 할 일/회신 카드 최대 8건. 서버가 찾은 주소가 오면 열린 하차 카드의 장소를 갱신한다. */
    private fun postParked(l: LatLng) {
        val tasks = parkTasks(cards(), clock.zone).map { parkWireId(it.id) to it.title }
        val memo = parkMemoText.orEmpty()
        net.execute { val (ok, addr) = Server.postParked(l.lat, l.lng, memo, tasks); Log.i("ROOM", "parked -> $ok"); if (addr != null) ui.post { parkCard = parkCard?.copy(place = addr) } }
    }

    /** [말하기] "두 명 예약해 줘": 목적지가 있으면 인원이 말에 있을 때 되묻지 않고 바로 전화, 없으면 인원을 묻는다. */
    private fun reserveIntent(said: String?) {
        val dn = forcedDest ?: adapters.destName; val e = adapters.etaMin
        if (dn == null || e == null) return say(RESERVE_NO_DEST_SPEECH)
        val t = arriveAt(clock.millis(), e, clock.zone); reserve = dn to t; reservePlace = null
        partyOf(said)?.let { callReserve(dn, t, it) } ?: askVoice(reserveSpeech(dn, t), Ask.RESERVE)
    }

    /** 말동무: 못 알아들은 말을 서버 AI에 묻고 답을 읽는다. 실패·no_key면 fallback(기존 못 알아들음 경로). 대화는 메모리에만(최근 6개, PARK로 바뀌면 비움). */
    private fun chatOr(said: String, fallback: () -> Unit) {
        val hist = chatHistory.toList()
        chatNet.execute {
            val r = Server.chat(said, hist)
            ui.post {
                if (r == null || (r.second != "llm" && r.second != "fallback")) { fallback(); return@post }
                if (machine.room != Room.PARK) { // 정차(PARK)로 바뀐 뒤 늦게 온 답은 기록하지 않는다(PARK에서 기록을 비웠으니)
                    chatHistory.add("user" to said); chatHistory.add("assistant" to r.first)
                    while (chatHistory.size > CHAT_KEEP) chatHistory.removeAt(0)
                }
                say(r.first)
            }
        }
    }

    /** 예/아니오 질문의 대답 처리(음성·화면 버튼 공통). */
    private fun answerYn(p: Ask, y: Boolean) {
        when (p) {
            // 번호를 알면 초안을 읽어 주고 "이대로 보낼까요?"(SEND). 모르면 지금처럼 카드만.
            Ask.LATE -> lateEvent?.let { (ev, m) -> if (y) lateTask(ev, m).let { t -> taskDraft(t).let { (n, b) -> sendOrCard(PendingSend("", n, b, t), LATE_DONE_SPEECH, true) } } }
            // 질문이 이미 초안을 읽어 줬으니 '응'이면 바로 발송(한 번 더 묻지 않는다)
            Ask.CALLBACK -> callback?.let { (i, m) -> if (y) sendOrCard(PendingSend(i.fromLabel, i.fromNumber, m, callbackTask(i, m)), CALLBACK_DONE_SPEECH, false) }
            Ask.SEND -> sending?.let { s -> if (y) doSend(s) else { addLocalTask(s.task); say(SEND_KEEP_SPEECH) } }
            Ask.REST -> if (y) addRestStop(true) else { restSnoozeUntil = clock.millis() + REST_SNOOZE_MS; restOnce.reset(); restOffer = false }
            Ask.NEAR_FIND -> if (y) nearFind()
            Ask.NEAR_GO -> if (y) nearGo()
            Ask.PARK_CALL -> if (y) parkCall()
            else -> {}
        }
    }

    /** 근처 찾기 "응": 차 현재 좌표를 서버로 1회 보내 앞쪽 가장 가까운 1곳을 받아 "들를까요?"로 묻는다. */
    private fun nearFind() {
        val kind = nearKind ?: return
        val loc = machine.signals.location ?: return say(NEAR_NO_LOC_SPEECH)
        val dest = machine.signals.destination
        net.execute {
            val hit = pickNear(Server.nearby(kind.code, loc.lat, loc.lng), loc, dest)
            ui.post { if (hit == null) say(NEAR_NONE_SPEECH) else if (canAsk()) { nearHit = hit; askVoice(nearFoundSpeech(hit.name, hit.distance), Ask.NEAR_GO) } }
        }
    }

    /** "주차 자리 있어?": 기준 좌표(목적지, 없으면 현재 위치)의 실시간 주차 자리를 한 문장으로. 빈자리 있으면 경유지로 넣을지(NEAR_GO), 없거나 못 구하면 가는 곳에 전화로 물어볼지(PARK_CALL). */
    private fun parkingIntent() {
        val base = machine.signals.destination ?: machine.signals.location ?: return say(NEAR_NO_LOC_SPEECH)
        val callTo = parkCallTarget()
        net.execute {
            val lots = Server.fetchParking(base.lat, base.lng)
            ui.post {
                val hit = pickPark(lots); val text = parkingSpeech(lots, callTo != null)
                if (hit != null) { if (canAsk()) { nearKind = null; nearHit = NearPlace(hit.name, hit.lat, hit.lng, hit.distM); askVoice(text, Ask.NEAR_GO) } }
                else if (callTo != null) { if (canAsk()) askVoice(text, Ask.PARK_CALL) } else say(text)
            }
        }
    }

    /** 주차 문의 전화를 걸 수 있는 곳 = 지금 가는 곳(이름과 좌표를 둘 다 알 때). 번호는 서버가 찾고, 못 찾으면 no_number. */
    private fun parkCallTarget(): Pair<String, LatLng>? {
        val n = forcedDest ?: adapters.destName; val d = machine.signals.destination
        return if (n != null && d != null) n to d else null
    }

    /** 주차 문의 "응": 서버가 AI로 가게에 전화한다. 결과는 예약 전화와 같은 폴링이 한 번 읽어 준다(onReserveRows). */
    private fun parkCall() {
        val (n, d) = parkCallTarget() ?: return say(PARK_NO_INFO_SPEECH)
        net.execute {
            val (id, code) = Server.reserveParking(n, d.lat, d.lng); Log.i("ROOM", "parking call -> ${id ?: "FAIL $code"}")
            ui.post {
                if (id == null) { say(when (code) { "no_number" -> PARK_NO_INFO_SPEECH; "daily_limit" -> RESERVE_LIMIT_SPEECH; else -> PARK_CALL_FAIL_SPEECH }); return@post }
                if (id > 0) { reserveWait[id] = n to SystemClock.elapsedRealtime(); parkWait += id }
                say(reserveCallSpeech(n))
            }
        }
    }

    /** 근처 찾기 "들를게"(주차장은 nearKind가 null이라 경유지만 넣고 끝): 경유지(목적지 없으면 경로)로 넣고, 식당·카페는 이어서 예약을 묻는다. 도착 시각은 시속 30킬로(분당 500m)로 올림. */
    private fun nearGo() {
        val kind = nearKind; val h = nearHit ?: return; val n = adapters.navi ?: return say(NEAR_NAVI_WAIT_SPEECH)
        if (machine.signals.destination != null) n.addWaypoint(RequestWaypointInfo(h.lat, h.lng, h.name, WaypointIndex.FIRST, "", "", "", "")) else route(LatLng(h.lat, h.lng), h.name)
        if (kind != NearKind.FOOD && kind != NearKind.CAFE) return say(NEAR_ADDED_SPEECH)
        val t = arriveAt(clock.millis(), maxOf(1, Math.ceil(h.distance / 500.0).toInt()), clock.zone)
        reserveOnce.fire(true) // 식당이 목적지가 돼도 틱의 자동 예약 질문이 또 묻지 않게
        reserve = h.name to t; reservePlace = LatLng(h.lat, h.lng); askVoice(reserveSpeech(h.name, t), Ask.RESERVE)
    }

    /** Q1: 못 알아들었다. 첫 번째는 다시 묻고 듣기, 두 번째는 화면 2택을 띄우고 음성 대기를 끝낸다. 화면을 못 쓰는 주행 중이면 onDrive(보통 '아니'로 닫기). */
    private fun unsure(p: Ask, a: Pair<String, () -> Unit>, b: Pair<String, () -> Unit>, onDrive: () -> Unit, prompt: String = lastAsk) {
        if (reask.again()) { pending = p; speaker.speak(REASK_SPEECH, ::listenNow); return }
        if (touchOk()) choice = Choice(prompt, a.first, b.first, a.second, b.second) else onDrive()
    }

    private fun addLocalTask(summary: String) {
        inbox = listOf(InboxItem(LOCAL_ID_BASE + clock.millis(), "내비서", summary, false, System.currentTimeMillis(), "", kind = "task")) + inbox
        saveLocalTasks(this) // 재시작해도 남게
    }

    /** 받는 번호를 알고 동승자 모드가 아니면 음성으로 발송(confirm이면 초안을 읽고 확인), 아니면 할 일 카드만 만들고 "정차하시면" 안내. */
    private fun sendOrCard(s: PendingSend, doneSpeech: String, confirm: Boolean) {
        val n = sendable(s.number)
        if (n == null || passenger) { addLocalTask(s.task); say(if (!s.number.isNullOrBlank() && s.number.any(Char::isDigit) && n == null) LANDLINE_SPEECH else doneSpeech); return }
        val ps = PendingSend(s.label, n, s.body, s.task)
        if (confirm && canAsk()) { sending = ps; askVoice(sendConfirmSpeech(s.body), Ask.SEND) } else if (confirm) { addLocalTask(s.task); say(doneSpeech) } else doSend(ps)
    }

    /** O4: 서버가 AI로 가게에 예약 전화를 건다(번호 조회·휴대폰/유선 구분 모두 서버). 못 걸면 예약 문자 초안을 할 일 카드로 남긴다. 결과는 폴링이 가져와 한 번 읽는다(onReserveRows). */
    private fun callReserve(n: String, t: String, party: Int) {
        val dest = reservePlace ?: machine.signals.destination; reservePlace = null
        net.execute {
            val (id, code) = dest?.let { Server.reserve(n, t, party, it.lat, it.lng) } ?: (null to ""); Log.i("ROOM", "reserve $n party=$party -> ${id ?: "FAIL $code"}")
            ui.post {
                if (id == null) { addLocalTask(reserveTask(n, t)); say(if (code == "daily_limit") RESERVE_LIMIT_SPEECH else RESERVE_FAIL_SPEECH); return@post }
                if (id > 0) reserveWait[id] = n to SystemClock.elapsedRealtime()
                say(reserveCallSpeech(n))
            }
        }
    }

    /** 예약 전화 결과: 기다리는 행이 calling이 아닌 상태로 처음 보이면 한 번만 읽고 기다림에서 뺀다. 결과는 할 일 카드로 남기고, 10분 넘게 안 끝나면 확인 필요 카드를 남긴다. */
    private fun onReserveRows(rows: List<InboxItem>) {
        val now = SystemClock.elapsedRealtime()
        reserveWait.filterValues { now - it.second > RESERVE_WAIT_MS }.forEach { (id, w) ->
            reserveWait.remove(id); addLocalTask(reserveResultTask(w.first, "결과를 받지 못했어요. 직접 확인해 주세요", parkWait.remove(id)))
        }
        for (i in rows) if (i.kind == "reserve") reserveWait[i.id]?.let { (place, _) ->
            reserveResultSpeech(place, i)?.let { reserveWait.remove(i.id); addLocalTask(reserveResultTask(place, i.summary.ifBlank { it }, parkWait.remove(i.id))); say(it) }
        }
    }

    /** 기존 문자 발송 경로(Server.send). 성공하면 "보냈어요", 실패하면 카드로 남긴다. */
    private fun doSend(s: PendingSend) = net.execute {
        val code = Server.send(s.label, s.number, s.body); Log.i("ROOM", "voice send -> $code")
        ui.post { if (code == "ok") say(SENT_SPEECH) else { addLocalTask(s.task); say("$SEND_FAIL_SPEECH. ${sendFailReason(code.orEmpty())}") } }
    }

    private fun runTurn(t: Turn) {
        if (t.listen) reask.reset() // 새 질문이면 재질문 기회도 새로
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

    /** 하차 카드 [고치기]: "어디에 세우셨어요?" 뒤 한 마디를 주차 메모로. */
    private fun editMemo() {
        if (listening || pending != Ask.NONE || talk.step != NotifTalk.Step.NONE) return
        if (voice.path == "none") return speaker.speak(NO_VOICE_SPEECH)
        askVoice(PARK_MEMO_ASK, Ask.PARK_MEMO)
    }

    /** 음성 인식이 없고(path none) 주행 중이면 대답을 못 받으니 '나중에'(카드로 보관). */
    private fun listenNow() {
        if (voice.path == "none") { if (!touchOk() || pending == Ask.CALLBACK || pending == Ask.SEND) heard("나중에"); return }
        listening = true; voice.listen(); syncWake()
    }

    private fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun inCall() = audio.mode == AudioManager.MODE_IN_CALL || audio.mode == AudioManager.MODE_IN_COMMUNICATION || replayRun != null

    /** 포그라운드 서비스 종류에 마이크를 더한다(권한이 있을 때만). 백그라운드에서 막히면(안드로이드 14) 조용히 포기. */
    private fun promoteMic() {
        if (Build.VERSION.SDK_INT < 34 || micFgs || !micGranted()) return
        runCatching { startForeground(1, notification(room), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE); micFgs = true }
            .onFailure { Log.w("ROOM", "mic fgs FAIL ${it.javaClass.simpleName}") }
    }

    /** 이름 호출 켜기·끄기·쉬기를 한 곳에서. 말하는 중·다른 질문 대기·듣는 중·통화 중에는 쉰다(자기 음성 인식 방지). */
    private fun syncWake() {
        val w = wake
        val want = wakeOn && machine.room != null && micGranted() && wakeModelOk // 시동이 켜진 동안 대기(대표님 지시, 정차 포함)
        if (!want) { if (w != null) { w.stop(); wake = null; Log.i("ROOM", "wake off") }; return }
        val cur = w ?: Wake(applicationContext, wakeName, ::onWake).also { wake = it; it.pause(); it.start(); promoteMic(); Log.i("ROOM", "wake on") }
        if (cur.dead) return // 모델·마이크를 못 열었다: 스위치를 껐다 켤 때까지 다시 안 시도
        cur.name = wakeName
        if (speaker.busy || pending != Ask.NONE || listening || inCall()) cur.pause() else cur.resume()
    }

    /** 이름 호출 결과(Wake 스레드에서 옴 → 메인으로). "" = 이름만 불렀다, 그 외 = 이름 뒤 문장(또는 이름만 부른 뒤의 다음 문장). */
    private fun onWake(text: String) { ui.post {
        if (!alive || wake == null) return@post
        if (pending != Ask.NONE || listening || talk.step != NotifTalk.Step.NONE) return@post // 이미 다른 대화 중
        if (text.isEmpty()) { if (!quiet) speaker.speak(WAKE_ACK); return@post } // 대답 듣기는 Wake가 이어 받는다(Pleos 듣기를 또 켜지 않는다)
        reask.reset(); choice = null; pending = Ask.INTENT; heard(text)
    } }

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
            tickReplay()
            syncWake()
            strip.sync(stripVisible(screenOverride ?: room, appFront, canOverlay))
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
            etaMin = e; destName = forcedDest ?: adapters.destName // 운전 화면 '도착 예정'
            if (e != sentEta) machine.room?.let { sentEta = e; sendState(it.name, ++stateGen, true) }
            // 짧은 P 동안은 driveStart가 남아 있으니 주행 중일 때만 센다(M4). 휴식·피로가 같은 순간이면 한 문장만(H10).
            val restNow = restOnce.fire(restDue(driveStart.takeIf { machine.room != Room.PARK }, clock.millis(), restMin, restSnoozeUntil))
            if (restNow) { restOffer = true; if (canAsk()) askVoice(REST_ASK, Ask.REST) else say(REST_SPEECH) }
            // 안전 안내라 동승자 모드에서도. 피로 보관이 켜지면 휴게소 버튼도 같이 띄운다(휴식 기준을 더 길게 잡았어도).
            if (fatigueOnce.fire(fatigued(machine.room, tripMin()))) {
                restOffer = true; if (!restNow) say(fatigueSpeech(machine.signals.gear))
                if (talk.step != NotifTalk.Step.NONE) { talk.cancel(); askStep = talk.step; listening = false } // 폰 알림 대화는 피로 진입 때 조용히 닫는다(카드는 남음)
            }
            // 하차(P + 운전석 문 열림): 정차 위치를 그 시점 위치로 한 번 더 갱신. 신호·권한이 없으면 PARK 진입 때 저장한 것이 그대로 남는다.
            if (alightOnce.fire(machine.room == Room.PARK && gotOff(machine.signals.gear, adapters.forcedDoor ?: adapters.driverDoorPos))) machine.signals.location?.let { Log.i("ROOM", "alight -> park update"); postParked(it) }
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
            // 도착 10분 전: 그 일정 장소로 가는 중이면 일정마다 한 번 "곧 도착해요". 동승자·피로 모드에선 말하지 않는다. 지각 질문이 나간 일정은 건너뜀(arriveBrief)
            if (machine.room == Room.DRIVE_WORK && !passenger && !fatigued(machine.room, tripMin()) && canAsk())
                nextEvent(schedule, System.currentTimeMillis())?.let { ev -> arriveBrief(ev, machine.signals.destination, e, arriveSaid, lateAsked, clock.zone)?.let { sp ->
                    arriveSaid += ev.start; val z = arriveSaid.sorted().takeLast(50).joinToString(","); net.execute { prefs.edit().putString("arrive_said", z).apply() }; say(sp) } }
            // O4: 식당·카페류 목적지로 출발하면 한 번 "몇 분으로 예약할까요?"(업무·쉼 방 상관없이)
            val dn = forcedDest ?: adapters.destName
            if (machine.room.let { it != null && it != Room.PARK } && !passenger && e != null && reservable(dn) && canAsk() &&
                reserveOnce.fire(true)) arriveAt(clock.millis(), e, clock.zone).let { t -> reserve = dn!! to t; reservePlace = null; askVoice(reserveSpeech(dn, t), Ask.RESERVE) }
            // 먼저 말 걸기(근처 찾기 흐름 그대로): 충전이 먼저, 한 틱에 하나. 시각·운전 시간은 TIME·TRIP 디버그와 같은 값
            if (machine.room.let { it != null && it != Room.PARK } && !passenger && canAsk()) clock.millis().let { now ->
                val soc = forcedSoc ?: adapters.soc; val word = mealWord(LocalTime.now(clock))
                if (soc != null && soc <= CHARGE_OFFER_SOC && offerDue(proactive, lastChargeOffer, now)) { lastChargeOffer = now; nearKind = NearKind.CHARGE; askVoice(chargeOffer(soc), Ask.NEAR_FIND) }
                else if (word != null && (tripMin() ?: 0) >= MEAL_OFFER_DRIVE_MIN && !reservable(dn) && offerDue(proactive, lastMealOffer, now)) { lastMealOffer = now; nearKind = NearKind.FOOD; askVoice(mealOffer(word), Ask.NEAR_FIND) }
            }
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
        if (Build.VERSION.SDK_INT >= 34) {
            // 마이크 권한이 있으면 마이크 종류도 같이(이름 호출용). 시동 직후 백그라운드 시작 등으로 거부되면 특수 용도만으로 다시.
            micFgs = micGranted()
            runCatching { startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or (if (micFgs) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)) }
                .onFailure { micFgs = false; Log.w("ROOM", "fgs with mic FAIL ${it.javaClass.simpleName}"); startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) }
        } else startForeground(1, n)
        net.execute { restStops = runCatching { assets.open("reststops.tsv").bufferedReader().use { parseRestStops(it.readText()) } }.onFailure { Log.w("ROOM", "reststops FAIL $it") }.getOrDefault(emptyList()) } // 실패하면 빈 목록 = 내비 검색 폴백
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
            val pa = runCatching { Proactive.valueOf(p.getString("proactive", null) ?: "") }.getOrDefault(Proactive.NORMAL); ui.post { proactive = pa }
            val wo = p.getBoolean("wake_on", false); val wn = p.getString("wake_name", null)?.takeIf { validName(it) } ?: WAKE_DEFAULT_NAME; ui.post { wakeOn = wo; wakeName = wn }
            val ab = p.getString("arrive_said", "").orEmpty().split(",").mapNotNull { it.toLongOrNull() }
            val sd = p.getBoolean("setup_done", false); val md = p.getString("mode", "ai") ?: "ai"; val bd = p.getLong("brief_day", -1)
            ui.post { arriveSaid += ab; restMin = r; workEndHour = we; inbox = (lt.filter { t -> inbox.none { it.id == t.id } } + inbox).sortedByDescending { it.id }; seen = sn + seen; notifDone = nd + notifDone; cardDone = cd + cardDone; snoozed = sz + snoozed; deferred = df + deferred; forwardOk = forwardOk || fw; setupDone = sd; mode = md; briefDay = bd }
        }
        gleo = { ci -> ui.post { if (alive) runIntent(ci) } }
        memoEdit = { ui.post { if (alive) editMemo() } }
        latch = AlertLatch(Clock.systemDefaultZone())
        machine = RoomStateMachine(clock) // settings = 기본 상수. 설정 화면이 machine.settings를 덮어쓴다.
        machine.onChange = { from, to ->
            Log.i("ROOM", "$from -> $to at ${LocalDateTime.now(clock).withNano(0)}")
            room = to.name; choice = null
            val now = clock.millis()
            if (to == Room.PARK) {
                parkedAt = now; alightOnce.reset(); restOffer = false; hold.clear(); parkRemind.reset(); chatHistory.clear()
                // 정차 요약: 이번 실행에 와서 아직 안 알리고 안 본 알림(쉼 방 비즐겨찾기·피로·동승자 등으로 미룬 것)
                notifQueue.clear(); talk.cancel(); listening = false; pending = Ask.NONE; reservePlace = null
                machine.signals.location?.let(::postParked) // 짧은 정차에도 위치는 갱신
                val pt = parkTasks(cards(), clock.zone); val pn = adapters.arrivedName
                parkCard = if (parkCardNow(from, machine.signals.location != null || pn != null, pt)) ParkCard(parkMemoText.orEmpty(), pn, now, pt) else null
                val left = inbox.filter { it.id in notifFresh && it.id !in notifSaid && it.id !in seen && !isAd(it) }.sortedBy { it.id }
                if (left.isNotEmpty() && !passenger) offerNotifs(left)
            }
            else {
                val parked = parkedAt?.let { now - it }; parkedAt = null; parkCard = null
                replay = replay?.copy(hint = null) // 출발하면 도움 카드는 내리고(운전 중엔 귓속말만), 다음 도움부터 TTS
                if (from == Room.PARK && talk.step != NotifTalk.Step.NONE) { talk.cancel(); askStep = talk.step; listening = false } // P에서 남겨 둔 바는 출발하면 닫는다(카드는 남음)
                // M4: 2분 미만 P는 같은 운전 — 휴식·피로·집 문턱 1회 안내와 운전 시작 시각을 유지
                if (newDrive(parked)) { homeOnce.reset(); reserveOnce.reset(); restOnce.reset(); fatigueOnce.reset(); trip.reset(); driveStart = now; restSnoozeUntil = 0
                    lastMealOffer = null; lastChargeOffer = null; parkMemoText = null; net.execute { Server.clearParked() } }
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
        searchInNavi = { q -> runCatching { adapters.navi?.requestNavigationScreen(NavigationScreenRequest.SearchPlace(q)) }.onFailure { Log.w("ROOM", "navi search FAIL $it") } }
        adapters.onNaviReady = { pendingRoute?.let { pendingRoute = null; Log.i("ROOM", "navi ready -> route"); it() } }
        speaker = Speaker(applicationContext).also { it.onBusy = ::syncWake; it.start() }
        voice = Voice(applicationContext, ::heard).also { it.start() }
        answer = { if (it == "답장하기") { if (!listening) listenNow() } else voice.inject(it) } // '답장하기'는 대답이 아니라 다시 듣기
        strip = DriveStrip(this)
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

    /** H7: 오늘 첫 업무 운전에서 '어제 넘긴 전화 N건' + 오늘 남은 일정 한 문장씩. 둘 중 하나라도 말했으면 하루 1회. */
    private fun briefCarryOver() {
        val t = today()
        val c = carryOver(cards(), deferred, seen, t)
        sentences(carrySpeech(c, deferred, t), briefSpeech(schedule, System.currentTimeMillis(), clock.zone)).takeIf { it.isNotEmpty() }
            ?.let { briefDay = t; net.execute { prefs.edit().putLong("brief_day", t).apply() }; say(it) }
    }

    private fun markForwarded() {
        if (forwardOk) return
        confirmForward(this); Log.i("ROOM", "forward ok")
    }

    private fun notification(r: String) = Notification.Builder(this, CH)
        .setSmallIcon(android.R.drawable.ic_menu_directions)
        .setContentTitle(notifTitle(r))
        .setOngoing(true).build()

    // 디버그 빌드 전용: adb shell am broadcast -a ztw.carbiseo.ROUTE --ef lat 37.4979 --ef lng 127.0276 [--es name "판교 한정식" = O4 예약용 목적지 이름]
    private fun registerDebugRoute() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val lat = i.getFloatExtra("lat", 0f).toDouble()
                val lng = i.getFloatExtra("lng", 0f).toDouble()
                forcedDest = i.getStringExtra("name")
                Log.i("ROOM", "debug requestRoute $lat,$lng name=$forcedDest")
                adapters.navi?.requestRoute(RouteInfo(lat, lng, null, null, forcedDest ?: "목적지", "", "", "", RouteOption.RECOMMENDED, "", false))
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
        // adb shell am broadcast -a ztw.carbiseo.SOC --ei soc 15 (-1 = 해제, 차 실제 값으로). 먼저 말 걸기의 충전 제안 확인용
        val soc = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { forcedSoc = i.getIntExtra("soc", -1).takeIf { it >= 0 }; Log.i("ROOM", "debug soc force=$forcedSoc") }
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
                timeVer++ // 정차 화면이 '지금 시각'을 다시 읽는다(미룬 카드 복귀·퇴근 후 접기)
                machine.tick()
                Log.i("ROOM", "debug time -> ${LocalDateTime.now(clock).withNano(0)} ${LocalDate.now(clock).dayOfWeek} room=${machine.room}")
            }
        }
        // adb shell am broadcast -a ztw.carbiseo.SAY --es t "읽어줘" (음성 인식 결과 대신 넣기)
        val say = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { Log.i("ROOM", "debug say"); voice.inject(i.getStringExtra("t").orEmpty()) }
        }
        // adb shell am broadcast -a ztw.carbiseo.CALLCARD --es from 김부장 --es num 01000000000 --es sum "3시 30분까지 연락 주세요" (새 통화 카드 주입, 서버 호출 없음. 정차 상태에서 확인. --es kind task = 할 일 카드, sum에 "메일: 받는이 / 제목 / 본문"이면 메일 시트)
        val cc = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val id = LOCAL_ID_BASE + clock.millis()
                inbox = listOf(InboxItem(id, i.getStringExtra("from").orEmpty(), i.getStringExtra("sum").orEmpty(), false, System.currentTimeMillis(), "", fromNumber = i.getStringExtra("num").orEmpty(), kind = i.getStringExtra("kind") ?: "call")) + inbox
                callFresh += id; Log.i("ROOM", "debug callcard $id")
            }
        }
        // adb shell am broadcast -a ztw.carbiseo.HEAR (앱 files/hear.pcm을 다음 듣기 때 STT 엔진에 넣기, run-as로 복사, 실측용)
        val hear = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { runCatching { java.io.File(filesDir, "hear.pcm").readBytes() }.onSuccess { Log.i("ROOM", "debug hear ${it.size}B"); voice.armed = it } }
        }
        // adb shell am broadcast -a ztw.carbiseo.WAKEPCM (앱 files/wake.pcm(16kHz·16bit·mono)을 마이크 대신 이름 호출 인식기에 넣기. 이름 호출이 켜져 있고 방이 PARK가 아닐 때)
        val wakePcm = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { runCatching { java.io.File(filesDir, "wake.pcm").readBytes() }.onSuccess { Log.i("ROOM", "debug wake pcm ${it.size}B active=${wake != null}"); wake?.feedPcm(it) } }
        }
        // adb shell am broadcast -a ztw.carbiseo.DOOR --ei pos 1 (운전석 문 위치값, 0 = 닫힘, -1 = 해제) / -a ztw.carbiseo.WIPER --ez on true
        val door = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { adapters.forcedDoor = i.getIntExtra("pos", -1).takeIf { it >= 0 }; Log.i("ROOM", "debug door force=${adapters.forcedDoor}") }
        }
        val wiper = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { val on = i.getBooleanExtra("on", false); adapters.setRain(on); Log.i("ROOM", "debug wiper rain=$on") }
        }
        for ((rc, action) in listOf(door to "ztw.carbiseo.DOOR", wiper to "ztw.carbiseo.WIPER", cc to "ztw.carbiseo.CALLCARD", hear to "ztw.carbiseo.HEAR", wakePcm to "ztw.carbiseo.WAKEPCM", say to "ztw.carbiseo.SAY", wp to "ztw.carbiseo.WP", r to "ztw.carbiseo.ROUTE", al to "ztw.carbiseo.ALERT", tr to "ztw.carbiseo.TRIP", loc to "ztw.carbiseo.LOC", tm to "ztw.carbiseo.TIME", soc to "ztw.carbiseo.SOC", sig to "ztw.carbiseo.SIGNAL")) {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(rc, IntentFilter(action), Context.RECEIVER_EXPORTED) else registerReceiver(rc, IntentFilter(action))
            receivers += rc
        }
        // 디버그 빌드만: 화면만 다른 방으로 그려 보기(판정·서버 상태는 그대로). adb shell am broadcast -a ztw.carbiseo.SCREEN --es room DRIVE_WORK (extra 없이 보내면 해제)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            val scr = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { screenOverride = i.getStringExtra("room")?.takeIf { it.isNotBlank() } } }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(scr, IntentFilter("ztw.carbiseo.SCREEN"), Context.RECEIVER_EXPORTED) else registerReceiver(scr, IntentFilter("ztw.carbiseo.SCREEN"))
            receivers += scr
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REST) addRestStop()
        if (intent?.action == ACTION_TALK) startTalk()
        if (intent?.action == ACTION_SPEAK) intent.getStringExtra("t")?.takeIf { it.isNotBlank() }?.let(::say) // 화면 버튼(급한 건 듣기·첫 마디 들어보기): 경고 중이면 보류
        if (intent?.action == ACTION_GO) intent.run {
            val lat = getDoubleExtra("lat", 0.0); val lng = getDoubleExtra("lng", 0.0); val name = getStringExtra("name") ?: ""
            Log.i("ROOM", "go $name $lat,$lng")
            route(LatLng(lat, lng), name)
        }
        return START_STICKY
    }

    /** S2 버튼: 경로상 앞쪽 휴게소를 첫 경유지로. 목적지가 없으면 그 휴게소로 경로를 연다. */
    private fun addRestStop(speak: Boolean = false, toiletOnly: Boolean = false) {
        restOffer = false
        val s = machine.signals
        val n = adapters.navi ?: return
        val stop = nearestRestStop(s.location, s.destination, toiletOnly = toiletOnly)
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
        gleo = null; memoEdit = null; alive = false; ui.removeCallbacks(ticker); ui.removeCallbacks(poller); strip.sync(false); wake?.stop(); wake = null; net.shutdown(); chatNet.shutdown(); pollNet.shutdown(); speaker.stop(); voice.stop(); adapters.stop(); receivers.forEach { unregisterReceiver(it) }; super.onDestroy()
    }

    companion object {
        @Volatile private var memoEdit: (() -> Unit)? = null // 서비스가 살아 있을 때만 있다
        /** 하차 카드: 화면이 [확인]을 누르면 카드를 내린다. */
        fun ackParkCard() { parkCard = null }
        /** 하차 카드: [고치기] -> "어디에 세우셨어요?" 음성 질문. */
        fun editParkMemo() { memoEdit?.invoke() }
        var parkCard by mutableStateOf<ParkCard?>(null); private set // PARK로 바뀔 때만 만들고 다음 주행 방 진입 때 비운다. 화면(Compose)이 읽는다
        @Volatile var gleo: ((CarIntent) -> Unit)? = null // GleoReceiver가 부른다. 서비스가 살아 있을 때만 있다
        private const val CH = "room"
        private const val HEARTBEAT_MS = 5 * 60_000L
        private const val CHAT_KEEP = 6 // 말동무 기억: 최근 user/assistant 합쳐 6개
        private const val POLL_MS = 5_000L
        private const val RESERVE_WAIT_MS = 10 * 60_000L // 예약 전화 결과를 기다리는 최대 시간
        private const val SLOW_EVERY = 6 // 일정·설정 조회 주기(폴링 6번에 한 번)
        const val ACTION_REST = "ztw.carbiseo.REST"
        const val ACTION_TALK = "ztw.carbiseo.TALK" // F5: [말하기] 버튼
        const val ACTION_GO = "ztw.carbiseo.GO" // F7: extras lat, lng, name
        const val ACTION_SPEAK = "ztw.carbiseo.SPEAK" // extra t: 읽을 문장
        var etaMin by mutableStateOf<Int?>(null); private set // 목적지까지 남은 분(내비). null = 목적지 없음
        var destName by mutableStateOf<String?>(null); private set
        var ux by mutableStateOf(UxLimits()) // CarAdapters가 규제 바뀔 때마다 넣는다
        var appFront by mutableStateOf(false) // 앱 창이 앞에 있나(MainActivity가 넣는다). 내비 위 띠를 숨긴다
        var screenOverride by mutableStateOf<String?>(null); private set // 디버그 SCREEN 브로드캐스트(화면 확인용)
        private const val NAVI_PKG = "ai.umos.maps.android.navigation.app" // Pleos 내비 앱(26 실측)
        var restOffer by mutableStateOf(false); private set // S2: 화면에 휴게소 버튼
        var restMin by mutableIntStateOf(REST_DEFAULT_MIN) // 연속 주행 기준(분). 설정 화면이 바꾼다.
        var proactive by mutableStateOf(Proactive.NORMAL) // 먼저 말 걸기 수준(prefs "proactive"). 설정 화면이 바꾼다.
        var workEndHour by mutableIntStateOf(18) // 업무 끝 시각(시, 17~20). 설정 화면이 바꾼다. 방 판정·업무 외 판정이 이 값을 쓴다.
        fun setWorkEnd(c: Context, h: Int) { workEndHour = h; save(c) { putInt("work_end", h) } }
        const val WAKE_DEFAULT_NAME = "내비서"
        private const val WAKE_ACK = "네" // 이름만 불렸을 때
        var wakeOn by mutableStateOf(false); private set // 이름으로 부르기 스위치(prefs wake_on, 기본 끔). 설정 화면이 바꾼다.
        var wakeName by mutableStateOf(WAKE_DEFAULT_NAME); private set
        fun setWakeOn(c: Context, on: Boolean) { wakeOn = on; save(c) { putBoolean("wake_on", on) } }
        fun setWakeName(c: Context, n: String) { wakeName = n; save(c) { putString("wake_name", n) } }
        var passenger by mutableStateOf(false); private set // H3: 켜면 업무 TTS(부재중 낭독)·쉼 방 선언(H1)을 끈다. 휴식 제안(S2)은 안전이라 유지.
        var passengerAt = 0L; private set
        /** H9: 이번 운전만. 저장하지 않고, 30분 넘게 세운 뒤 출발하면 자동 해제. */
        fun togglePassenger(on: Boolean) { passenger = on; passengerAt = clock.millis() }
        val clock = ShiftClock() // 프로세스 공통 시각(디버그 TIME 브로드캐스트만 offset을 바꾼다)
        var timeVer by mutableIntStateOf(0); private set // TIME 브로드캐스트마다 +1: 화면이 시각을 다시 읽는 신호
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
            sync(id, "done")
        }
        /** 설계 29: 카드 버튼 결과를 서버 행에도(차 로컬 카드는 서버에 없으니 뺌). 실패해도 차 화면엔 영향 없음 */
        private fun sync(id: Long, action: String) { if (id < LOCAL_ID_BASE) Thread { Server.postCardAction(id, action) }.start() }
        var snoozed by mutableStateOf(mapOf<Long, Long>()); private set // H2: [내일 아침에] 카드 id -> 미룬 날(그날만 숨김). prefs 저장
        fun snooze(c: Context, id: Long) {
            val (d, z, s) = snoozeCard(deferred, snoozed, seen, id, LocalDate.now(clock).toEpochDay())
            deferred = d; snoozed = z; seen = s
            val ds = encodeDeferred(d); val zs = encodeDeferred(z); val ss = s.sorted().takeLast(300).joinToString(",")
            save(c) { putString("deferred", ds).putString("snoozed", zs).putString("seen", ss) }
            sync(id, "tomorrow")
        }
        /** 화면·건수·정차 알림이 보는 카드 목록: 읽어 준 알림·[완료]한 카드·오늘 미룬 카드를 뺀 것. */
        fun cards(today: Long = LocalDate.now(clock).toEpochDay()): List<InboxItem> = hideCards(unread(inbox, notifDone), cardDone, snoozed, today)
        fun confirmForward(c: Context) { forwardOk = true; save(c) { putBoolean("forward_ok", true) } }
        fun finishSetup(c: Context) { setupDone = true; save(c) { putBoolean("setup_done", true) } }
        fun reopenSetup(c: Context) { setupDone = false; save(c) { putBoolean("setup_done", false) } }
        var room by mutableStateOf("(판정 전)"); private set
        var sig by mutableStateOf(""); private set
        var inbox by mutableStateOf(listOf<InboxItem>()); private set // id 내림차순
        var alert by mutableStateOf(Alert.NONE); private set
        var schedule by mutableStateOf(listOf<Event>()); private set // F6 데모 일정
        var replay by mutableStateOf<ReplayView?>(null); private set // O3 재현 통화 중(null = 통화 아님). 화면 하단 띠가 읽는다
        var offline by mutableStateOf(false); private set // #23 서버 폴링 실패
        var passengerOffer by mutableStateOf(false) // C⑤ 조수석 착석 감지 → "동승자 모드로 바꿀까요?"
        var telltale by mutableStateOf<String?>(null); private set // C⑦ 빨간 경고등
        var homeSrc by mutableStateOf("기본값(내비 즐겨찾기 없음)") // C① 집 좌표 출처(설정 화면 표시)
        var addHomeFavorite: (() -> Unit)? = null // C① 설정 화면 버튼 → 내비 '집 즐겨찾기 추가' 화면
        var searchInNavi: ((String) -> Unit)? = null // O4: 가게 번호를 못 찾은 예약 카드 → 내비 장소 검색 화면(내비 상세에서 바로 전화)
        // 폰 알림(35): 쉼 방에도 바로 알릴 발신자(설정, 기본 빈 목록), 대답 대기 단계·듣는 중·화면 버튼 허용(P·저속)·음성 경로
        var favs by mutableStateOf(setOf<String>())
        var askStep by mutableStateOf(NotifTalk.Step.NONE); private set
        var listening by mutableStateOf(false); private set
        var askTouch by mutableStateOf(true); private set
        var voicePath by mutableStateOf("none"); private set
        var answer: ((String) -> Unit)? = null // 화면 버튼 → 음성 대답과 같은 처리
        /** Q1: 음성으로 두 번 못 알아들은 질문의 화면 2택(첫째·둘째 버튼 문구와 동작). 음성 듣기는 이미 끝났다. */
        class Choice(val prompt: String, val first: String, val second: String, val onFirst: () -> Unit, val onSecond: () -> Unit)
        var choice by mutableStateOf<Choice?>(null); private set
        fun pick(first: Boolean) { val c = choice ?: return; choice = null; if (first) c.onFirst() else c.onSecond() }
    }
}
