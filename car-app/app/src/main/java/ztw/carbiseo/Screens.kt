package ztw.carbiseo

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// 시안 B 색 토큰
private val ParkBg = Color(0xFFF3EEE4); private val ParkInk = Color(0xFF2B2620); private val ParkBody = Color(0xFF5E554A)
private val ParkAcc = Color(0xFFC2683A); private val ParkNote = Color(0xFFFFFDF8)
private val SheetBg = Color(0xFF2B2620); private val SheetInk = Color(0xFFF3EEE4)
private val WorkBg1 = Color(0xFF04121F); private val WorkBg2 = Color(0xFF0B3A5C); private val WorkInk = Color(0xFFE6F4FF); private val WorkAcc = Color(0xFF5CE1E6)
private val RestBg1 = Color(0xFF24183A); private val RestBg2 = Color(0xFF7A3E5C); private val RestBg3 = Color(0xFFE8875A)
private val RestInk = Color(0xFFFFF3E8); private val RestAcc = Color(0xFFFFD38A)
private val Tall = Modifier.heightIn(min = 64.dp)
private val hhmm = DateTimeFormatter.ofPattern("HH:mm")


private fun roomName(r: String) = when (r) { "PARK" -> "5분 사무실"; "DRIVE_WORK" -> "업무 운전"; "DRIVE_REST" -> "업무 시간 밖"; else -> "판정 전" }
private fun accent(r: String) = when (r) { "PARK" -> ParkAcc; "DRIVE_REST" -> RestAcc; else -> WorkAcc }
private fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_SHORT).show()
private fun label(i: InboxItem) = when (i.kind) {
    "task" -> "할 일"
    "sent" -> "보낸 문자 · ${i.fromLabel}"
    "notif" -> "${i.app} · ${i.fromLabel}"
    else -> i.fromLabel.ifBlank { "모르는 번호" }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    // 첫 로드는 디스크 읽기라 IO에서. 로드 전엔 배경 없음·설정 안 열림(수십 ms).
    val prefs = produceState<SharedPreferences?>(null) { value = withContext(Dispatchers.IO) { ctx.getSharedPreferences("car_ui", Context.MODE_PRIVATE).also { it.all } } }.value
    var ver by remember { mutableIntStateOf(0) } // 배경 바뀌면 +1 -> 다시 디코드
    var settings by rememberSaveable { mutableStateOf(false) }
    val room = RoomService.room
    LaunchedEffect(room) { if (room != "PARK") settings = false } // 운전 시작하면 설정 닫음
    if (settings && room == "PARK" && prefs != null) { Settings(prefs, { ver++ }) { settings = false }; return }

    // 방 전환: 우리 창 안에서만 대각선 와이프 300ms 1회
    var prev by remember { mutableStateOf(room) }
    val p = remember { Animatable(1f) }
    LaunchedEffect(room) { if (room != prev) { p.snapTo(0f); p.animateTo(1f, tween(300)); prev = room } }
    val bg = { r: String -> prefs?.getString(r, null)?.let(::File) } // 없는 파일은 loadBg(IO)가 null로 처리
    Column(Modifier.fillMaxSize()) {
    AlertBanner(RoomService.alert)
    Box(Modifier.fillMaxSize()) {
        if (p.value < 1f) RoomScreen(prev, room, bg(prev), ver) {}
        val a = (1 - p.value) * 1.4f
        Box(Modifier.fillMaxSize().clip(GenericShape { s, _ ->
            moveTo(a * s.width, 0f); lineTo(s.width, 0f); lineTo(s.width, s.height); lineTo((a - 0.4f) * s.width, s.height); close()
        })) { RoomScreen(room, room, bg(room), ver) { settings = true } }
        if (p.value < 1f) Text(roomName(room), Modifier.align(Alignment.BottomEnd).padding(24.dp),
            color = accent(room), fontSize = 44.sp, fontWeight = FontWeight.Black)
        AlertFrame(RoomService.alert)
        AskBar(Modifier.align(Alignment.BottomCenter))
    }
    }
}

/** 폰 알림 대답 대기 중: '듣는 중' 한 줄 + 큰 버튼(P·저속에서만, 주행 중엔 음성만). */
@Composable
private fun AskBar(m: Modifier) {
    val step = RoomService.askStep
    if (step == NotifTalk.Step.NONE) return
    val (label, opts) = askBar(step, RoomService.listening, RoomService.askTouch)
    Column(m.fillMaxWidth().background(SheetBg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, color = SheetInk, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        if (RoomService.askTouch) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (o in opts) Button({ RoomService.answer?.invoke(o) }, Modifier.weight(1f).heightIn(min = 96.dp), colors = ButtonDefaults.buttonColors(containerColor = ParkAcc)) {
                Text(o, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
            }
        }
    }
}

/** 차 상태 경고: 상단 한 줄(내용을 밀어 내림) + 방 화면 테두리. 나타날 때 300ms 페이드 1회, 이후 고정(깜빡임 없음). */
private fun alertColor(a: Alert) = if (a == Alert.OVERSPEED) Color(0xFFE53935) else Color(0xFFFF8F00)

@Composable
private fun fadeIn(): Float { val f = remember { Animatable(0f) }; LaunchedEffect(Unit) { f.animateTo(1f, tween(300)) }; return f.value }

@Composable
private fun AlertBanner(a: Alert) {
    if (a == Alert.NONE) return
    Text(if (a == Alert.OVERSPEED) "속도를 줄여 주세요 · 전화는 비서가 받는 중" else "주의 구간 · 전화는 비서가 받는 중",
        Modifier.fillMaxWidth().alpha(fadeIn()).background(alertColor(a)).padding(horizontal = 24.dp, vertical = 10.dp),
        color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
}

@Composable
private fun AlertFrame(a: Alert) {
    if (a == Alert.NONE) return
    Box(Modifier.fillMaxSize().alpha(fadeIn()).border(12.dp, alertColor(a)))
}

/** r = 그릴 방, live = 지금 실제 방(배경 재생·스크림 판단은 항상 실제 방 기준). */
@Composable
private fun RoomScreen(r: String, live: String, bgFile: File?, ver: Int, onSettings: () -> Unit) {
    val base = when (r) {
        "PARK" -> SolidColor(ParkBg)
        "DRIVE_REST" -> Brush.linearGradient(listOf(RestBg1, RestBg2, RestBg3))
        else -> Brush.radialGradient(listOf(WorkBg2, WorkBg1))
    }
    Box(Modifier.fillMaxSize().background(base)) {
        val onImage = bgFile != null && BgImage(bgFile, ver, bgPolicy(live))
        when (r) { "PARK" -> Park(onImage, onSettings); "DRIVE_REST" -> Rest(); else -> Work(r) }
    }
}

/** 사용자 배경 + 스크림. 디코드되면 true. */
@Composable
private fun BgImage(f: File, ver: Int, pol: BgPolicy): Boolean {
    val dm = LocalContext.current.resources.displayMetrics
    val d by produceState<Drawable?>(null, f, ver) { value = withContext(Dispatchers.IO) { loadBg(f, dm.widthPixels, dm.heightPixels) } }
    val dr = d ?: return false
    AndroidView({ ImageView(it).apply { scaleType = ImageView.ScaleType.CENTER_CROP } }, Modifier.fillMaxSize()) { v ->
        v.setImageDrawable(dr)
        (dr as? AnimatedImageDrawable)?.let { if (pol.animate) it.start() else it.stop() }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = pol.scrimAlpha)))
    return true
}

@Composable
private fun Park(onImage: Boolean, onSettings: () -> Unit) {
    val items = parkOrder(unread(RoomService.inbox, RoomService.notifDone))
    val list = rememberLazyListState()
    val newest = RoomService.inbox.maxOfOrNull { it.id }
    LaunchedEffect(newest) { if (newest != null) list.animateScrollToItem(0) } // 새 카드가 위에 숨지 않게
    var sel by rememberSaveable { mutableStateOf<Long?>(null) }
    val ink = if (onImage) Color.White else ParkInk
    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(roomName("PARK"), Modifier.weight(1f), color = if (onImage) Color.White else ParkAcc, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            PassengerToggle(ink)
            TextButton(onSettings, Tall) { Text("설정", color = ink, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
        val ctx = LocalContext.current
        val unseen = todo(items, RoomService.seen)
        Text(if (unseen == 0) "세워 둔 동안, 볼 건 없어요" else "확인할 게 ${unseen}건 있어요",
            color = ink, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black)
        val calls = todayCalls(items, java.time.LocalDate.now())
        if (calls > 0) Text("오늘 AI가 대신 받은 전화 ${calls}통 = 운전 중 통화 ${calls}번 줄임",
            Modifier.padding(top = 6.dp), color = if (onImage) Color.White else ParkBody, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (RoomService.offline) Text("서버 연결 끊김 — 다시 연결하는 중. 그동안 전화는 통화 서비스가 기본 안내로 받아요",
            Modifier.padding(top = 6.dp).fillMaxWidth().background(Color(0xFFC62828), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        RoomService.telltale?.let { Text("계기판 빨간 경고등($it) — 출발 전에 확인해 주세요",
            Modifier.padding(top = 6.dp).fillMaxWidth().background(Color(0xFFC62828), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        if (!RoomService.offline && !RoomService.forwardOk && RoomService.setupDone) Text("AI 응대 준비 안 됨(착신 설정 필요) — 설정에서 '처음 설정'을 다시 볼 수 있어요",
            Modifier.padding(top = 6.dp).fillMaxWidth().background(ParkInk, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        PassengerOffer(ParkAcc, Color.White, Modifier.padding(top = 8.dp))
        if (!RoomService.setupDone) { FirstRun(); return@Column }
        nextEvent(RoomService.schedule, System.currentTimeMillis())?.let { NextEvent(it) }
        // H7: 어제(업무 외 시간에) 넘긴 통화를 맨 위에 묶는다. 열면 본 것으로 저장(M5).
        val today = java.time.LocalDate.now(RoomService.clock).toEpochDay()
        val carry = carryOver(items, RoomService.deferred, RoomService.seen, today)
        val rest = items.filter { it !in carry }
        // M6: 저녁·주말 P(집 차고 등)는 급한 것만 펼치고 업무 카드는 접는다. 1탭으로 펼침
        var showAll by rememberSaveable { mutableStateOf(false) }
        val shown = if (offDuty(java.time.LocalDateTime.now(RoomService.clock), RoomSettings()) && !showAll) rest.filter { it.urgent } else rest
        val tap = { i: InboxItem -> sel = if (sel == i.id) null else i.id; RoomService.markSeen(ctx, i.id) }
        LazyColumn(Modifier.weight(1f).padding(top = 16.dp), list, verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (carry.isNotEmpty()) item(key = "carry") {
                val y = carry.all { RoomService.deferred[it.id] == today - 1 }
                Text("${if (y) "어제" else "지난번"} 넘긴 건 ${carry.size}", color = ink, fontSize = 22.sp, fontWeight = FontWeight.Black)
            }
            items(carry, key = { it.id }) { i -> Note(i, i.id == sel) { tap(i) } }
            if (carry.isNotEmpty() && shown.isNotEmpty()) item(key = "new") { Text("그 밖의 것", color = ink, fontSize = 22.sp, fontWeight = FontWeight.Black) }
            items(shown, key = { it.id }) { i -> Note(i, i.id == sel) { tap(i) } }
            if (shown.size < rest.size) item(key = "more") {
                TextButton({ showAll = true }, Tall) { Text("업무 ${rest.size - shown.size}건은 다음 평일 아침에 봐도 돼요 · 지금 보기", color = ink, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            }
        }
        items.firstOrNull { it.id == sel && it.kind in setOf("call", "task") }?.let { Sheet(it) { sel = null } }
    }
}

@Composable
private fun Note(i: InboxItem, selected: Boolean, onTap: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(Tall.fillMaxWidth().clip(shape).background(ParkNote).border(3.dp, if (selected) ParkAcc else ParkNote, shape)
        .clickable(onClick = onTap).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label(i), Modifier.weight(1f), color = ParkInk, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text(hhmm.format(Instant.ofEpochMilli(i.createdAt).atZone(ZoneId.systemDefault())), color = ParkBody, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (i.kind == "hint") Text("AI 제안", Modifier.padding(start = 8.dp).background(ParkInk, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 2.dp),
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (i.urgent) Text("급함", Modifier.padding(start = 8.dp).background(ParkAcc, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 2.dp),
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Text(i.summary.ifBlank { "AI가 통화 내용을 정리하고 있어요" }, color = ParkBody, fontSize = 18.sp, lineHeight = 26.sp)
        if (i.kind == "sent") Text(sentStatus(i), color = if (i.status == "sent") ParkBody else Color(0xFFC62828), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (i.car.isNotBlank()) Text("쓴 차량 데이터 · ${i.car}", Modifier.padding(top = 6.dp).background(ParkBg, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp),
            color = ParkBody, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        // 카드는 주차 화면에만 있다 = 버튼도 P에서만
        if (i.kind == "task") Button(onTap, Tall.padding(top = 8.dp), colors = ButtonDefaults.buttonColors(containerColor = ParkAcc)) {
            Text("문자 보내기", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        // H15: 서버가 보관한 실제 번호로 회신(화면엔 끝 4자리만). 문자는 카드를 눌러 확인 시트에서.
        if (i.kind == "call" && i.fromNumber.isNotBlank()) {
            val ctx = LocalContext.current
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:${i.fromNumber}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { toast(ctx, "전화를 걸 수 있는 연결된 폰이 없어요") }
                }, Tall, colors = ButtonDefaults.buttonColors(containerColor = ParkAcc)) { Text("전화 걸기", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                OutlinedButton(onTap, Tall) { Text("문자", color = ParkInk, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** F7: 다음 일정 카드 + 출발(내비 경로 요청). */
@Composable
private fun NextEvent(e: Event) {
    val ctx = LocalContext.current
    Row(Tall.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(14.dp)).background(ParkNote).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("다음 일정 · ${hhmm.format(Instant.ofEpochMilli(e.start).atZone(ZoneId.systemDefault()))}", color = ParkBody, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(e.title, color = ParkInk, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text(e.place, color = ParkBody, fontSize = 18.sp)
        }
        Button({
            ctx.startService(Intent(ctx, RoomService::class.java).setAction(RoomService.ACTION_GO)
                .putExtra("lat", e.at.lat).putExtra("lng", e.at.lng).putExtra("name", e.place))
            toast(ctx, "${e.place}(으)로 길 안내를 시작해요")
        }, Tall, colors = ButtonDefaults.buttonColors(containerColor = ParkAcc)) {
            Text("출발", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** C⑤ 조수석 착석 감지 시 확인 버튼(자동으로 바꾸지 않는다). */
@Composable
private fun PassengerOffer(bg: Color, ink: Color, m: Modifier = Modifier) {
    if (!RoomService.passengerOffer) return
    Row(m.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button({ RoomService.togglePassenger(true); RoomService.passengerOffer = false },
            Modifier.weight(1f).heightIn(min = 96.dp), colors = ButtonDefaults.buttonColors(containerColor = bg)) {
            Text("동승자가 탔어요 · 동승자 모드로 바꿀까요?", color = ink, fontSize = 24.sp, fontWeight = FontWeight.Black)
        }
        OutlinedButton({ RoomService.passengerOffer = false }, Modifier.heightIn(min = 96.dp)) { Text("아니요", color = bg, fontSize = 22.sp) }
    }
}

/** H3 동승자 모드 토글: 정차 화면·설정에 같은 것. H9: 이번 운전만(저장 안 함). */
@Composable
private fun PassengerToggle(ink: Color) {
    val on = RoomService.passenger
    TextButton({ RoomService.togglePassenger(!on) }, Tall) {
        Text(if (on) "동승자 켜짐(이번 운전만)" else "동승자 꺼짐", color = if (on) ParkAcc else ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

/** H13 첫 실행 안내 1장: ①응대 모드 ②착신 설정 ③방해 금지 + 반복 전화 허용. P에서만 보인다. */
@Composable
private fun FirstRun() {
    val ctx = LocalContext.current
    val step: @Composable (String, String) -> Unit = { n, t -> Text("$n $t", color = ParkInk, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold) }
    val body: @Composable (String) -> Unit = { t -> Text(t, color = ParkBody, fontSize = 18.sp, lineHeight = 26.sp) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp).verticalScroll(rememberScrollState()).clip(RoundedCornerShape(14.dp)).background(ParkNote).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("처음 한 번만 · 설정 3가지", color = ParkAcc, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        step("①", "전화 응대 방식")
        ModeButtons()
        step("②", "폰에서 착신 전환 설정")
        body("폰 설정 > 착신 전환 > '통화 중일 때'·'응답 없을 때' → ${RoomService.serviceNumber.ifBlank { "(서비스 번호 불러오는 중)" }}")
        if (RoomService.forwardOk) body("착신 설정 확인됨")
        else OutlinedButton({ RoomService.confirmForward(ctx) }, Tall) { Text("착신 설정 했어요", color = ParkInk, fontSize = 20.sp) }
        body("실제 착신 전화가 한 번 들어오면 자동으로 확인돼요.")
        step("③", "운전 중 방해 금지 자동 켜기 + 가족·반복 전화 허용")
        body("폰 설정 > 방해 금지(운전 모드) > 차 블루투스 연결 시 자동 켜기, '즐겨찾기 허용'에 가족 번호, '반복 전화 허용' 켜기. 가족과 바로 다시 건 급한 분은 폰이 직접 울리고, 나머지는 AI가 받아요.")
        body("벨은 폰에서 먼저 울려요. 앱이 막을 수는 없어서, 통신사 착신 '응답 없을 때' 대기 시간을 가장 짧게 해 두세요.")
        Button({ RoomService.finishSetup(ctx) }, Tall, colors = ButtonDefaults.buttonColors(containerColor = ParkAcc)) {
            Text("다 했어요", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** 응대 모드 3택: 서버 저장이 성공해야 바뀐다. */
@Composable
private fun ModeButtons() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((m, t) in listOf("ai" to "AI 응대", "ment" to "안내 멘트", "off" to "끔")) {
            val on = RoomService.mode == m
            Button({
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { Server.postMode(m).also { if (it) ctx.getSharedPreferences("car_ui", Context.MODE_PRIVATE).edit().putString("mode", m).apply() } }
                    if (ok) RoomService.mode = m
                    toast(ctx, if (ok) "$t(으)로 바꿨어요" else "저장 실패 — 다시 시도해 주세요")
                }
            }, Tall, colors = ButtonDefaults.buttonColors(containerColor = if (on) ParkAcc else ParkNote)) {
                Text(t, color = if (on) Color.White else ParkInk, fontSize = 18.sp)
            }
        }
    }
}

/** 보내기 전 확인 1단계: 받는 번호·본문을 보여 주고 누르면 서버가 발송(/car/send). */
@Composable
private fun Sheet(i: InboxItem, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // H15: 통화 카드는 서버가 보관한 실제 번호로(화면엔 끝 4자리만). '끝번호 0000님' 같은 호칭은 쓰지 않는다.
    val draft = remember(i.id) { if (i.kind == "task") taskDraft(i.summary) else i.fromNumber to "운전 중이라 전화를 못 받았습니다. 확인하고 다시 연락드리겠습니다." }
    val auto = i.kind == "call" && i.fromNumber.isNotBlank()
    var number by remember(i.id) { mutableStateOf(draft.first) }
    var text by remember(i.id) { mutableStateOf(draft.second) }
    var edit by remember(i.id) { mutableStateOf(false) }
    var busy by remember(i.id) { mutableStateOf(false) }
    val digits = number.filter { it.isDigit() }
    Column(Modifier.fillMaxWidth().background(SheetBg, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${if (i.kind == "task") "할 일" else "${label(i)}님께"} 문자 · 보내기 전 확인", color = SheetInk.copy(alpha = 0.8f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        if (auto) Text("받는 번호 · 끝번호 ${digits.takeLast(4)}(자동)", color = SheetInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        else OutlinedTextField(number, { number = it.take(13) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("받는 번호", fontSize = 16.sp) },
            textStyle = TextStyle(color = SheetInk, fontSize = 20.sp))
        if (edit) OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), textStyle = TextStyle(color = SheetInk, fontSize = 20.sp))
        else Text("“$text”", color = SheetInk, fontSize = 20.sp, lineHeight = 28.sp)
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton({ edit = !edit }, Tall) { Text(if (edit) "다 고침" else "고치기", color = SheetInk, fontSize = 20.sp) }
            Button({
                busy = true
                scope.launch {
                    val code = withContext(Dispatchers.IO) { Server.send(if (auto) i.fromLabel else "", digits, text.trim()) }
                    busy = false
                    toast(ctx, when (code) { "ok" -> "보냈어요"; null -> "서버 연결 실패 — 다시 시도해 주세요"; "overseas_ip_blocked" -> "못 보냄(서버 IP 제한)"; else -> "못 보냄($code)" })
                    if (code == "ok") { RoomService.dropLocal(i.id); onDone() }
                }
            }, Tall, enabled = !busy && digits.length in 9..11 && text.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = ParkAcc)) {
                Text("보내기", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** S2: 연속 주행 기준을 넘으면 주행 화면에 큰 버튼 1개. 움직임 없음, 한 번 터치. */
@Composable
private fun RestButton(bg: Color, ink: Color, m: Modifier = Modifier) {
    if (!RoomService.restOffer) return
    val ctx = LocalContext.current
    Button({ ctx.startService(Intent(ctx, RoomService::class.java).setAction(RoomService.ACTION_REST)) },
        m.fillMaxWidth().heightIn(min = 96.dp), colors = ButtonDefaults.buttonColors(containerColor = bg)) {
        Text("가까운 휴게소 들르기", color = ink, fontSize = 30.sp, fontWeight = FontWeight.Black)
    }
}

/** 업무 운전(판정 전도 이 화면): 조작 요소는 휴게소 버튼(S2)뿐. */
@Composable
private fun Work(r: String) {
    val n = RoomService.inbox.count { it.urgent && it.id !in RoomService.seen }
    Box(Modifier.fillMaxSize().padding(24.dp)) {
        Text(roomName(r), color = WorkAcc, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(readiness(RoomService.offline, RoomService.forwardOk, RoomService.mode), color = WorkInk, fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text("$n", color = WorkInk, fontSize = 120.sp, lineHeight = 130.sp, fontWeight = FontWeight.Black)
            Text("급한 건", color = WorkInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        // H9·H10: 주행 중엔 동승자 확인 버튼을 띄우지 않는다(P 화면에서). 조작은 휴게소 버튼 1개.
        Column(Modifier.align(Alignment.BottomCenter)) { RestButton(WorkAcc, WorkBg1) }
    }
}

@Composable
private fun Rest() {
    val n = todo(unread(RoomService.inbox, RoomService.notifDone), RoomService.seen)
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(roomName("DRIVE_REST"), color = RestAcc, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("오늘 일은 여기까지", color = RestInk, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Black)
        Text(readiness(RoomService.offline, RoomService.forwardOk, RoomService.mode), color = RestInk, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        RestButton(RestAcc, RestBg1, Modifier.padding(bottom = 16.dp))
        // H7: 실제로 다음 첫 업무 운전에서 '어제 넘긴 전화 N건'을 말하고 P 화면 맨 위에 묶는다
        Text("업무 ${n}건은 다음 출근길에 알려 드릴게요", color = RestInk, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
    }
}

/** 꾸미기: PARK에서만 열린다. */
@Composable
private fun Settings(prefs: SharedPreferences, onBg: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by rememberSaveable { mutableStateOf("PARK") }
    val pick = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        val r = target
        if (uri != null) scope.launch {
            val f = File(ctx.filesDir, "bg_$r") // 원본 URI에 기대지 않게 복사해 둔다
            val ok = withContext(Dispatchers.IO) {
                runCatching { ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } } }.isSuccess
            }
            if (ok) { prefs.edit().putString(r, f.path).apply(); onBg() } else toast(ctx, "사진을 읽지 못했어요")
        }
    }
    var owner by rememberSaveable { mutableStateOf(prefs.getString("owner_label", "") ?: "") }
    var assistant by rememberSaveable { mutableStateOf(prefs.getString("assistant_name", "") ?: "") }
    Column(Modifier.fillMaxSize().background(ParkBg).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("설정", Modifier.weight(1f), color = ParkInk, fontSize = 30.sp, fontWeight = FontWeight.Black)
            TextButton(onClose, Tall) { Text("닫기", color = ParkInk, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
        for (r in listOf("PARK", "DRIVE_WORK", "DRIVE_REST")) {
            Text("${roomName(r)} 배경", color = ParkInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ target = r; pick.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }, Tall,
                    colors = ButtonDefaults.buttonColors(containerColor = ParkAcc)) { Text("사진/GIF 고르기", fontSize = 18.sp) }
                OutlinedButton({ prefs.edit().remove(r).apply(); scope.launch(Dispatchers.IO) { File(ctx.filesDir, "bg_$r").delete() }; onBg() }, Tall) {
                    Text("기본으로", color = ParkInk, fontSize = 18.sp)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("동승자 모드 (업무 음성 끔)", Modifier.weight(1f), color = ParkInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            PassengerToggle(ParkInk)
        }
        Text("휴식 제안: 연속 운전 ${RoomService.restMin}분부터", color = ParkInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((min, t) in listOf(1 to "1분(시연)", 60 to "60분", REST_DEFAULT_MIN to "120분")) {
                val on = RoomService.restMin == min
                Button({ RoomService.restMin = min; if (min != 1) prefs.edit().putInt("rest_min", min).apply() }, Tall, // 시연값은 저장 안 함
                    colors = ButtonDefaults.buttonColors(containerColor = if (on) ParkAcc else ParkNote)) {
                    Text(t, color = if (on) Color.White else ParkInk, fontSize = 18.sp)
                }
            }
        }
        Text("집 위치: ${RoomService.homeSrc}", color = ParkInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        OutlinedButton({ RoomService.addHomeFavorite?.invoke() }, Tall) { Text("내비 즐겨찾기에 집 추가", color = ParkInk, fontSize = 18.sp) }
        var favText by rememberSaveable { mutableStateOf(RoomService.favs.joinToString(", ")) }
        OutlinedTextField(favText, { favText = it.take(100); RoomService.favs = parseFavs(favText); prefs.edit().putString("fav_senders", favText).apply() },
            Modifier.fillMaxWidth(), singleLine = true, label = { Text("업무 시간 밖에도 바로 읽을 알림 보낸이", fontSize = 18.sp) },
            supportingText = { Text("쉼표로 구분(예: 엄마, 아빠). 나머지는 차를 세우면 묶어서 알려 드려요") }, textStyle = TextStyle(color = ParkInk, fontSize = 22.sp))
        Text("전화 응대", color = ParkInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        ModeButtons()
        OutlinedButton({ RoomService.reopenSetup(ctx); onClose() }, Tall) { Text("처음 설정(착신·방해 금지) 다시 보기", color = ParkInk, fontSize = 18.sp) }
        NameField("내 호칭", owner) { owner = it }
        NameField("AI 비서 이름", assistant) { assistant = it }
        Button({
            val o = owner.trim(); val a = assistant.trim()
            scope.launch {
                val ok = withContext(Dispatchers.IO) { Server.postSettings(o, a) }
                if (ok) prefs.edit().putString("owner_label", o).putString("assistant_name", a).apply()
                toast(ctx, if (ok) "저장했어요" else "저장 실패 — 다시 시도해 주세요")
            }
        }, Tall, enabled = validName(owner) && validName(assistant), colors = ButtonDefaults.buttonColors(containerColor = ParkAcc)) {
            Text("이름 저장", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun NameField(label: String, v: String, onChange: (String) -> Unit) = OutlinedTextField(
    v, { onChange(it.take(10)) }, Modifier.fillMaxWidth(), singleLine = true, isError = v.isNotEmpty() && !validName(v),
    label = { Text(label, fontSize = 18.sp) }, supportingText = { Text("1~10자, 글자·숫자·공백만") },
    textStyle = TextStyle(color = ParkInk, fontSize = 22.sp),
)
