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
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import java.time.LocalDate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.activity.compose.BackHandler
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

// 설정 화면(밝은 바탕) 토큰
private val ParkBg = Color(0xFFF3EEE4); private val ParkInk = Color(0xFF2B2620); private val ParkBody = Color(0xFF5E554A)
private val ParkAcc = Color(0xFFC2410C); private val ParkNote = Color(0xFFFFFDF8) // Brand: 흰 글자 대비 5.2:1
// 확정 시안(1B·2B·5A) 토큰. 창 1700x1210dp(밀도 160) = 시안 px 1:1
internal val Ink = Color(0xFF14161A); private val Brand = Color(0xFFC2410C); private val BrandHi = Color(0xFFFF7A33)
private val RestC = Color(0xFF1F6B5E); private val RestHi = Color(0xFF4FD1B5); private val Urgent = Color(0xFFB42318)
private val DBg = Color(0xFF111316); private val DS1 = Color(0xFF1A1D22); private val DS2 = Color(0xFF23272E); private val DLine = Color(0xFF2E333B)
internal val DTx = Color(0xFFF2F3F5); internal val DMute = Color(0xFFA6ACB4); private val DOut = Color(0xFF3B414A); internal val DriveBg = Color(0xFF0D0F12)
private val Paper = Color(0xFFEEEFF1); private val Mute = Color(0xFF5B616A); private val Line = Color(0xFFDFE1E5)
private val SheetBg = DS2; private val SheetInk = DTx
private val Tall = Modifier.heightIn(min = 64.dp)
private val hhmm = DateTimeFormatter.ofPattern("HH:mm")


private fun roomName(r: String) = when (r) { "PARK" -> "5분 사무실"; "DRIVE_WORK" -> "업무 운전"; "DRIVE_REST" -> "업무 시간 밖"; else -> "판정 전" }
private fun sentKind(i: InboxItem) = if (i.channel == "mail" || '@' in i.fromLabel) "보낸 메일" else "보낸 문자" // 데모 메일함(O2): 받는 사람이 이름일 수도 있어 meta.channel로
// Pleos(에뮬 11-02 실측)는 앱 토스트를 화면에 그리지 않는다 → 결과를 소리로도 알린다(경고 중이면 say가 보류)
private fun toast(c: Context, s: String) { Toast.makeText(c, s, Toast.LENGTH_SHORT).show(); speak(c, s) }
private fun label(i: InboxItem) = when (i.kind) {
    "task" -> "할 일"
    "sent" -> "${sentKind(i)} · ${i.fromLabel}"
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
    val room = RoomService.screenOverride ?: RoomService.room
    LaunchedEffect(room) { if (room != "PARK") settings = false } // 운전 시작하면 설정 닫음
    if (settings && room == "PARK" && prefs != null) { Settings(prefs, { ver++ }) { settings = false }; return }

    // 방 전환: 우리 창 안에서만 200ms 크로스페이드 1회(튐·깜빡임 없음)
    val bg = { r: String -> prefs?.getString(r, null)?.let(::File) } // 없는 파일은 loadBg(IO)가 null로 처리
    Column(Modifier.fillMaxSize()) {
    AlertBanner(RoomService.alert)
    Box(Modifier.weight(1f)) {
        Crossfade(room, animationSpec = tween(200), label = "room") { r -> RoomScreen(r, room, bg(r), ver) { settings = true } }
        AlertFrame(RoomService.alert)
    }
    AskBar(Modifier) // 2택·대답 띠도 화면을 밀어 올린다(덮으면 말하기가 잘림, 11-09 대표님 선택)
    ReplayBar() // 통화 중 띠는 화면을 밀어 올린다(덮으면 다음 일정·말하기가 가려짐)
    }
}

/** O3 재현 통화 중: 화면 아래 띠. '통화 중' 표시 + 최근 자막 한 줄, 정차 중엔 도움 카드(추천 답변·사실 정정·빈 시간). 운전 중 도움은 귓속말 TTS라 카드가 없다. */
@Composable
private fun ReplayBar() {
    val v = RoomService.replay ?: return
    Column(Modifier.fillMaxWidth().background(SheetBg).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        v.hint?.let { h ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(ParkAcc).padding(horizontal = 24.dp, vertical = 14.dp)) {
                Text(h.label, color = Color.White.copy(alpha = 0.86f), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(RoomService.ux.clip(h.text), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Text("통화 중 · ${v.title}", color = DMute, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        v.caption?.takeIf { RoomService.ux.caption }?.let { c -> Text("${when (c.who) { "ai" -> "AI 비서"; "peer" -> "상대"; else -> "나" }}  ${RoomService.ux.clip(c.text)}", color = SheetInk, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 2) }
    }
}

/** 폰 알림 대답 대기 중: '듣는 중' 한 줄 + 큰 버튼(P·저속에서만, 주행 중엔 음성만). */
@Composable
private fun AskBar(m: Modifier) {
    RoomService.choice?.let { c -> // Q1: 두 번 못 알아들은 질문은 화면 2택으로(음성 대기 종료)
        Column(m.fillMaxWidth().background(SheetBg).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(c.prompt, color = SheetInk, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((first, t) in listOf(true to c.first, false to c.second)) Button({ RoomService.pick(first) }, Modifier.weight(1f).heightIn(min = 96.dp), colors = ButtonDefaults.buttonColors(containerColor = if (first) ParkAcc else Color(0xFF3B414A))) {
                    Text(t, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black)
                }
            }
        }
        return
    }
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
        "PARK" -> SolidColor(DBg)
        else -> SolidColor(DriveBg)
    }
    Box(Modifier.fillMaxSize().background(base)) {
        val onImage = bgFile != null && BgImage(bgFile, ver, bgPolicy(live))
        if (r == "PARK") Park(onImage, onSettings) else Drive(r)
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

// ── 공통 부품: 누름 피드백(0.97 축소, 120ms) · 알약 버튼 76dp · 둥근 버튼 76dp ──
@Composable
private fun reduceMotion(): Boolean {
    val c = LocalContext.current
    return remember { android.provider.Settings.Global.getFloat(c.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

@Composable
internal fun Modifier.press(onClick: () -> Unit): Modifier {
    val src = remember { MutableInteractionSource() }
    val down by src.collectIsPressedAsState()
    val k by animateFloatAsState(if (down && !reduceMotion()) 0.97f else 1f, tween(120), label = "press")
    return graphicsLayer { scaleX = k; scaleY = k }.clickable(src, LocalIndication.current, role = Role.Button, onClick = onClick)
}

/** primary = 주황 채움(어두운 바탕은 BrandHi+검은 글자, 밝은 바탕은 Brand+흰 글자), 아니면 선(어두운)·회색 채움(밝은). */
@Composable
private fun Pill(text: String, icon: ImageVector? = null, primary: Boolean = false, m: Modifier = Modifier, light: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(38.dp)
    val ink = when { primary && light -> Color.White; primary || light -> Ink; else -> DTx }
    val box = when { primary -> Modifier.background(if (light) Brand else BrandHi, shape); light -> Modifier.background(Color(0xFFE2E4E8), shape); else -> Modifier.border(2.dp, DOut, shape) }
    Row(m.heightIn(min = 76.dp).clip(shape).then(box).press(onClick).padding(horizontal = 30.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
        if (icon != null) Icon(icon, null, Modifier.size(30.dp), tint = ink)
        Text(text, color = ink, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun Round(icon: ImageVector, desc: String, bg: Color = DS1, onClick: () -> Unit) =
    Box(Modifier.size(76.dp).clip(CircleShape).background(bg).press(onClick), Alignment.Center) { Icon(icon, desc, Modifier.size(30.dp), tint = DTx) }

/** 시안의 마이크 선 아이콘(24 격자). 아이콘 묶음에 마이크가 없어 직접 그린다. */
@Composable
internal fun MicIcon(c: Color, s: Dp) = Canvas(Modifier.size(s)) {
    val u = size.width / 24f; val st = Stroke(2.1f * u, cap = StrokeCap.Round)
    drawRoundRect(c, Offset(9 * u, 3 * u), Size(6 * u, 11 * u), CornerRadius(3 * u), style = st)
    drawArc(c, 0f, 180f, false, Offset(5.5f * u, 4.5f * u), Size(13 * u, 13 * u), style = st)
    drawLine(c, Offset(12 * u, 17.5f * u), Offset(12 * u, 21 * u), 2.1f * u, StrokeCap.Round)
}

private fun dial(ctx: Context, n: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$n")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { toast(ctx, "전화를 걸 수 있는 연결된 폰이 없어요") }
}
internal fun talk(ctx: Context) { ctx.startService(Intent(ctx, RoomService::class.java).setAction(RoomService.ACTION_TALK)) }
private fun time(ms: Long) = hhmm.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
/** 다음 일정 시각: 오늘이 아니면 "내일"·"요일"을 붙인다(밤에 잡힌 일정이 "00:00"만 보이던 문제). */
private fun dayTime(ms: Long): String {
    val z = RoomService.clock.zone
    val d = Instant.ofEpochMilli(ms).atZone(z).toLocalDate().toEpochDay() - Instant.ofEpochMilli(RoomService.clock.millis()).atZone(z).toLocalDate().toEpochDay()
    val day = when (d) { 0L -> ""; 1L -> "내일 "; else -> "${Instant.ofEpochMilli(ms).atZone(z).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.KOREAN)}요일 " }
    return day + hhmm.format(Instant.ofEpochMilli(ms).atZone(z))
}

/** 카드 머리: (이름, 보조 줄). 회신 번호는 끝 4자리만(H15). */
private fun head(i: InboxItem): Pair<String, String?> = when (i.kind) {
    "task" -> "할 일" to null
    "sent" -> sentKind(i) to "받는 사람 · ${i.fromLabel}"
    "notif" -> i.fromLabel.ifBlank { i.app } to i.app.takeIf { it.isNotBlank() && i.fromLabel.isNotBlank() }
    else -> i.fromLabel.ifBlank { "모르는 번호" } to i.fromNumber.takeLast(4).takeIf { it.length == 4 && "끝번호" !in i.fromLabel }?.let { "끝번호 $it" }
}

// ── 1B 정차 화면: 왼쪽 오늘 요약 레일 + 오른쪽 지금 처리할 1건 + 한 줄 대기열 + [말하기] ──
@Composable
private fun Park(onImage: Boolean, onSettings: () -> Unit) {
    if (!RoomService.setupDone) { FirstRun(); return }
    RoomService.parkCard?.let { ParkCardScreen(it); return } // [확인했어요]를 누르면 아래 정차 화면
    val ctx = LocalContext.current
    // 시각을 상태로: 1분마다, 그리고 디버그 TIME 때 다시 읽는다 → 자정·아침이 지나면 미룬 카드 복귀·퇴근 후 접기가 저절로 바뀐다
    val now by produceState(RoomService.clock.millis(), RoomService.timeVer) { while (true) { value = RoomService.clock.millis(); kotlinx.coroutines.delay(60_000) } }
    // H7: 어제(업무 외 시간에) 넘긴 통화를 맨 앞에. 열면 본 것으로 저장(M5).
    val today = Instant.ofEpochMilli(now).atZone(RoomService.clock.zone).toLocalDate().toEpochDay()
    val items = parkOrder(RoomService.cards(today))
    val carry = carryOver(items, RoomService.deferred, RoomService.seen, today)
    val rest = items.filter { it !in carry }
    // M6: 저녁·주말 P(집 차고 등)는 급한 것만 펼치고 업무 카드는 접는다. 1탭으로 펼침
    var showAll by rememberSaveable { mutableStateOf(false) }
    val shown = if (offDuty(Instant.ofEpochMilli(now).atZone(RoomService.clock.zone).toLocalDateTime(), RoomSettings(workEnd = workEndTime(RoomService.workEndHour))) && !showAll) rest.filter { it.urgent || it.kind == "hint" } else rest // 통화 정리(hint)는 방금 내가 한 통화라 접지 않는다
    val queue = carry + shown
    var sel by rememberSaveable { mutableStateOf<Long?>(null) }
    var sheet by rememberSaveable { mutableStateOf(false) }
    val focus = queue.firstOrNull { it.id == sel } ?: queue.firstOrNull() // 고른 게 없으면 맨 앞(급한 것·새것)이 바로 보인다
    val a = if (onImage) 0.86f else 1f // 사용자 배경 위에선 표면을 살짝 비친다
    val carryTag = { i: InboxItem -> if (i !in carry) null else if (RoomService.deferred[i.id] == today - 1) "어제 넘긴 건" else "지난번 넘긴 건" }
    Row(Modifier.fillMaxSize()) {
        Rail(items, today, a)
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = 36.dp, end = 40.dp, top = 36.dp, bottom = 36.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("지금 처리할 건", color = DTx, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                if (focus != null) Text("${queue.indexOf(focus) + 1} / ${queue.size}", Modifier.weight(1f), color = DMute, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                else Spacer(Modifier.weight(1f))
                PassengerToggle()
                Round(Icons.Filled.Settings, "설정", onClick = onSettings)
            }
            PassengerOffer()
            if (focus == null) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(DS1.copy(alpha = a)).padding(horizontal = 32.dp, vertical = 40.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("세워 둔 동안, 볼 건 없어요", color = DTx, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                Text("전화가 오면 비서가 받아서 여기에 정리해 둘게요", color = DMute, fontSize = 24.sp)
            } else Focus(focus, carryTag(focus), a) { sheet = true; RoomService.markSeen(ctx, focus.id) }
            if (sheet && focus != null && focus.kind in setOf("call", "task")) Box(Modifier.weight(1f).fillMaxWidth(), Alignment.BottomCenter) { Sheet(focus) { sheet = false } }
            else Queue(queue.filter { it != focus }, rest.size - shown.size, a, carryTag, { showAll = true }) { sel = it.id; sheet = false; RoomService.markSeen(ctx, it.id) }
            Row(Modifier.fillMaxWidth().height(112.dp).clip(RoundedCornerShape(56.dp)).background(BrandHi).press { talk(ctx) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
                MicIcon(Ink, 40.dp)
                Text(if (RoomService.listening) "듣는 중…" else "말하기", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

// ── 1a 하차 카드: 왼쪽 링(조각 수 = 남은 일, 안에 주차 메모) + 오른쪽 남은 일. 치수는 시안 px 1:1 ──
private fun lineIcon(d: String) = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
    .addPath(addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round).build()
private val PinIcon = lineIcon("M9 11a3 3 0 1 0 6 0a3 3 0 0 0-6 0M17.657 16.657l-4.243 4.243a2 2 0 0 1-2.827 0l-4.244-4.243a8 8 0 1 1 11.314 0z")
private val CallIcon = lineIcon("M5 4h4l2 5l-2.5 1.5a11 11 0 0 0 5 5l1.5-2.5l5 2v4a2 2 0 0 1-2 2a16 16 0 0 1-15-15a2 2 0 0 1 2-2")
private val FileIcon = lineIcon("M14 3v4a1 1 0 0 0 1 1h4M17 21h-10a2 2 0 0 1-2-2v-14a2 2 0 0 1 2-2h7l5 5v11a2 2 0 0 1-2 2z")
private val SendIcon = lineIcon("M10 14l11-11M21 3l-6.5 18a.55 .55 0 0 1-1 0l-3.5-7l-7-3.5a.55 .55 0 0 1 0-1l18-6.5")
private val CheckIcon = lineIcon("M5 12l5 5l10-10")

/** 하차 카드 할 일 아이콘: 보내기·문자·메일이 든 일은 보내기, 전화는 전화, 나머지는 서류. */
private fun taskIcon(t: ParkTask) = if (t.kind == "call") CallIcon else if (listOf("보내", "문자", "메일").any { it in t.title }) SendIcon else FileIcon

private fun ampm(ms: Long) = Instant.ofEpochMilli(ms).atZone(RoomService.clock.zone).let { "${if (it.hour < 12) "오전" else "오후"} ${if (it.hour % 12 == 0) 12 else it.hour % 12}:${"%02d".format(it.minute)}" }

@Composable
private fun ParkCardScreen(c: ParkCard) {
    val rest = !c.work
    val acc = if (rest) RestHi else BrandHi
    val n = c.total
    Row(Modifier.fillMaxSize().background(DBg).padding(horizontal = 64.dp, vertical = 60.dp)) { // 사용자 배경 사진 위에서도 읽히게 불투명
        Column(Modifier.width(776.dp).fillMaxHeight().padding(end = 72.dp), Arrangement.spacedBy(36.dp, Alignment.CenterVertically), Alignment.CenterHorizontally) {
            Box(Modifier.size(640.dp), Alignment.Center) {
                SegRing(minOf(n, 8), acc, if (rest) Color(0xFF16302B) else Color(0xFF2A1A12))
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Icon(PinIcon, null, Modifier.size(72.dp), tint = acc)
                    Text(c.memo.ifBlank { "메모 없음" }, Modifier.width(460.dp), color = if (c.memo.isBlank()) DMute else DTx, fontSize = 84.sp, lineHeight = 94.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.02).em,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, style = TextStyle(lineBreak = LineBreak.Heading, localeList = LocaleList("ko")))
                    Text("${ampm(c.savedAt)} 저장", color = DMute, fontSize = 30.sp, lineHeight = 45.sp)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(parkPlaceText(c.place, c.hasLocation), color = DTx, fontSize = 36.sp, lineHeight = 47.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("다음 출발 때 지워져요", color = DMute, fontSize = 30.sp, lineHeight = 45.sp)
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = 20.dp)) {
            RoomBadge(if (rest) "DRIVE_REST" else "DRIVE_WORK")
            if (n == 0) Column(Modifier.weight(1f), Arrangement.spacedBy(18.dp, Alignment.CenterVertically)) {
                ParkedPic(220.dp, acc)
                Text("남은 일이 없어요", color = DTx, fontSize = 64.sp, lineHeight = 77.sp, fontWeight = FontWeight.ExtraBold)
                Text("주차 위치만 남겨 둘게요", color = DMute, fontSize = 32.sp, lineHeight = 48.sp)
            } else {
                Row(Modifier.padding(top = 44.dp).height(170.dp).offset(y = (-30).dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) { // 이 글꼴은 170dp 칸에서 숫자가 30dp 아래로 내려앉아 시안 자리로 올린다
                    Text("$n", Modifier.alignByBaseline(), color = acc, fontSize = 200.sp, lineHeight = 170.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-10).sp, overflow = TextOverflow.Visible, style = TextStyle(lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)))
                    Text("남은 일", Modifier.alignByBaseline(), color = DTx, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                }
                BoxWithConstraints(Modifier.padding(top = 52.dp).weight(1f)) {
                    val shown = c.tasks.take(4); val more = n - shown.size
                    // 외 N건이 있으면 버튼 위 24dp를 남기도록 행 간격을 줄인다(행 96dp·외 N건 39dp 기준, 44dp 넘게는 안 벌린다)
                    val gap = if (more > 0 && shown.isNotEmpty()) ((maxHeight - 24.dp - 96.dp * shown.size - 39.dp) / shown.size).coerceIn(8.dp, 44.dp) else 44.dp
                    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                        for (t in shown) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                            Box(Modifier.size(80.dp).clip(CircleShape).background(DS2), Alignment.Center) { Icon(taskIcon(t), null, Modifier.size(36.dp), tint = DTx) }
                            Column(Modifier.weight(1f)) {
                                Text(t.title, color = DTx, fontSize = 46.sp, lineHeight = 57.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(t.sub, color = DMute, fontSize = 30.sp, lineHeight = 39.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (more > 0) Text("외 ${more}건", Modifier.padding(start = 106.dp), color = DMute, fontSize = 30.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.weight(1f).height(104.dp).clip(RoundedCornerShape(52.dp)).background(DS2).press { RoomService.editParkMemo() },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
                    MicIcon(DTx, 40.dp)
                    Text(if (RoomService.listening) "듣는 중…" else if (c.memo.isBlank()) "메모 남기기" else "메모 고치기", color = DTx, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                }
                Row(Modifier.weight(1f).height(104.dp).clip(RoundedCornerShape(52.dp)).background(acc).press { RoomService.ackParkCard() },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
                    Icon(CheckIcon, null, Modifier.size(40.dp), tint = Ink)
                    Text("확인했어요", color = Ink, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                }
            }
        }
    }
}

/** 조각 n개(0이면 온전한 링). 시안 dasharray처럼 조각마다 16도를 비우고 8도 밀어 시작. 처음 600ms 동안 시계 방향으로 쓸어 그리고, 애니메이션 끔이면 바로. */
@Composable
private fun SegRing(n: Int, acc: Color, track: Color) {
    val still = reduceMotion()
    val p = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(Unit) { if (!still) p.animateTo(1f, tween(600, 150, CubicBezierEasing(0.23f, 1f, 0.32f, 1f))) }
    Canvas(Modifier.fillMaxSize()) {
        val st = 36.dp.toPx(); val o = Offset(24.dp.toPx(), 24.dp.toPx()); val box = Size(size.width - 2 * o.x, size.height - 2 * o.y)
        val swept = 360f * p.value
        if (n == 0) { drawArc(acc, -90f, swept, false, o, box, style = Stroke(st, cap = StrokeCap.Round)); return@Canvas }
        drawArc(track, 0f, 360f, false, o, box, style = Stroke(st))
        val seg = 360f / n
        for (k in 0 until n) {
            val from = k * seg + 8f
            val sweep = (swept - from).coerceIn(0f, seg - 16f)
            if (sweep > 0f) drawArc(acc, -90f + from, sweep, false, o, box, style = Stroke(st, cap = StrokeCap.Round))
        }
    }
}

/** 시안 그림 parked(120 격자): 주차선 안에 세운 차를 위에서 본 모습 + 끝남 표시. */
@Composable
private fun ParkedPic(s: Dp, acc: Color) = Canvas(Modifier.size(s)) {
    scale(size.width / 120f, pivot = Offset.Zero) {
        for (x in listOf(18f, 102f)) drawLine(Color(0xFF4A5059), Offset(x, 14f), Offset(x, 106f), 4f, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
        drawRoundRect(DTx, Offset(36f, 18f), Size(48f, 84f), CornerRadius(16f), style = Stroke(3.5f))
        val line = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(PathParser().parsePathString("M44 46c10-6 22-6 32 0l-4 12c-8-3-16-3-24 0z").toPath(), DTx, style = line)
        drawPath(PathParser().parsePathString("M47 84c8 3 18 3 26 0").toPath(), DTx, style = line)
        drawCircle(acc, 20f, Offset(90f, 92f))
        drawPath(PathParser().parsePathString("M81 92l7 7l12-13").toPath(), Ink, style = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** 왼쪽 레일: 방 배지 · 오늘 대신 받은 전화 · 급한 건/미룬 건/읽어 준 알림 · 상태 경고 · 다음 일정. */
@Composable
private fun Rail(items: List<InboxItem>, today: Long, a: Float) {
    val day = LocalDate.ofEpochDay(today)
    val isToday = { i: InboxItem -> Instant.ofEpochMilli(i.createdAt + RoomService.clock.offsetMs).atZone(ZoneId.systemDefault()).toLocalDate() == day }
    Column(Modifier.width(520.dp).fillMaxHeight().background(DS1.copy(alpha = a)).padding(horizontal = 36.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
        Row(Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(DS2).padding(start = 14.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(24.dp).border(2.dp, BrandHi, RoundedCornerShape(6.dp)), Alignment.Center) { Text("P", color = BrandHi, fontSize = 15.sp, fontWeight = FontWeight.Black) }
            Text(roomName("PARK"), color = DTx, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        }
        val calls = todayCalls(RoomService.inbox, day, offsetMs = RoomService.clock.offsetMs) // 완료한 카드도 '대신 받은 전화'엔 그대로 센다
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$calls", color = DTx, fontSize = 150.sp, lineHeight = 143.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-6).sp)
                Text("통", Modifier.padding(start = 6.dp, bottom = 18.dp), color = DTx, fontSize = 44.sp, fontWeight = FontWeight.Bold)
            }
            Text(if (calls > 0) "오늘 비서가 대신 받은 전화예요" else "오늘은 대신 받은 전화가 없어요", Modifier.padding(top = 8.dp), color = DMute, fontSize = 24.sp)
            val (week, weekRest) = weekCalls(RoomService.inbox, day, RoomService.clock.zone, RoomService.clock.offsetMs)
            Text("이번 주 ${week}통 · 퇴근 뒤 넘긴 전화 ${weekRest}통", Modifier.padding(top = 4.dp), color = DMute, fontSize = 24.sp)
        }
        Column {
            HorizontalDivider(color = DLine)
            for ((k, v, hot) in listOf(
                Triple("급한 건", items.count { it.urgent }, true),
                Triple("내일 아침으로 미룬 건", RoomService.snoozed.count { it.value == today }, false),
                Triple("차로 읽어 준 알림", RoomService.inbox.count { it.id in RoomService.notifDone && isToday(it) }, false),
            )) {
                Row(Modifier.fillMaxWidth().height(72.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(k, Modifier.weight(1f), color = DMute, fontSize = 24.sp)
                    Text("$v", color = if (hot && v > 0) BrandHi else DTx, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                }
                HorizontalDivider(color = DLine)
            }
        }
        val warn: @Composable (String) -> Unit = { t -> Text(t, Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Urgent).padding(horizontal = 20.dp, vertical = 14.dp),
            color = Color.White, fontSize = 21.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold) }
        if (RoomService.offline) warn("서버 연결 끊김 — 다시 연결하는 중. 그동안 전화는 통화 서비스가 기본 안내로 받아요")
        RoomService.telltale?.let { warn("계기판 빨간 경고등($it) — 출발 전에 확인해 주세요") }
        if (!RoomService.offline && !RoomService.forwardOk) warn("AI 응대 준비 안 됨(착신 설정 필요) — 설정에서 '처음 설정'을 다시 볼 수 있어요")
        Spacer(Modifier.weight(1f))
        nextEvent(RoomService.schedule, System.currentTimeMillis())?.let { NextEvent(it) }
    }
}

/** F7: 다음 일정 카드 + 지금 출발(내비 경로 요청). */
@Composable
private fun NextEvent(e: Event) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(DS2).padding(horizontal = 26.dp, vertical = 24.dp)) {
        Text("다음 일정", color = DMute, fontSize = 21.sp)
        Text("${dayTime(e.start)} ${e.title}", Modifier.padding(top = 4.dp, bottom = 2.dp), color = DTx, fontSize = 38.sp, lineHeight = 46.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (e.place.isNotBlank()) Text(e.place, color = DMute, fontSize = 21.sp)
        Pill("지금 출발", Icons.Filled.LocationOn, primary = true, m = Modifier.fillMaxWidth().padding(top = 18.dp)) {
            ctx.startService(Intent(ctx, RoomService::class.java).setAction(RoomService.ACTION_GO)
                .putExtra("lat", e.at.lat).putExtra("lng", e.at.lng).putExtra("name", e.place))
            toast(ctx, "${e.place}(으)로 길 안내를 시작해요")
        }
    }
}

@Composable
private fun Badge(t: String, hot: Boolean = false) = Text(t, Modifier.clip(RoundedCornerShape(10.dp)).background(if (hot) Color(0xFF3A1D14) else DS2).padding(horizontal = 12.dp, vertical = 3.dp),
    color = if (hot) Color(0xFFFFB28A) else Color(0xFFD7DADF), fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)

@Composable
private fun Avatar(i: InboxItem, name: String) {
    // 진짜 이름이면 첫 글자, 아니면(끝번호·모르는 번호·할 일 등) 종류 아이콘
    val icon = when (i.kind) { "task" -> Icons.Filled.Check; "sent" -> Icons.Filled.Send; "notif" -> Icons.Filled.Notifications; "hint" -> Icons.Filled.Star; else -> Icons.Filled.Call }
    val initial = name.firstOrNull()?.takeIf { i.kind in setOf("call", "notif", "hint") && it.isLetter() && !name.startsWith("끝번호") && !name.startsWith("모르는") }
    Box(Modifier.size(72.dp).clip(CircleShape).background(BrandHi), Alignment.Center) {
        if (initial != null) Text("$initial", color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold) else Icon(icon, null, Modifier.size(32.dp), tint = Ink)
    }
}

/** 포커스 카드: 지금 처리할 1건. 버튼은 통화·할 일에만(F3 완료·H2 내일 아침에). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Focus(i: InboxItem, carryTag: String?, a: Float, onSms: () -> Unit) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(32.dp)
    val (name, sub) = head(i)
    Column(Modifier.fillMaxWidth().clip(shape).background(DS1.copy(alpha = a)).border(2.dp, Color(0xFF5A3420), shape).padding(horizontal = 32.dp, vertical = 30.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Avatar(i, name)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(name, Modifier.weight(1f, fill = false), color = DTx, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (i.urgent) Badge("급함", hot = true)
                    if (i.kind == "hint") Badge("AI 제안")
                    carryTag?.let { Badge(it) }
                }
                sub?.let { Text(it, color = DMute, fontSize = 22.sp, maxLines = 1) }
            }
            Text(time(i.createdAt), color = DMute, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(cardText(i).ifBlank { "AI가 통화 내용을 정리하고 있어요" }, color = DTx, fontSize = 30.sp, lineHeight = 43.sp, fontWeight = FontWeight.SemiBold, maxLines = 4, overflow = TextOverflow.Ellipsis)
        if (i.kind == "sent") Text(sentStatus(i), color = if (i.status == "sent") DMute else Color(0xFFFF8A80), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (i.car.isNotBlank()) FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("쓴 차량 데이터", Modifier.align(Alignment.CenterVertically), color = DMute, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            for (c in i.car.split(" · ").filter { it.isNotBlank() })
                Row(Modifier.clip(RoundedCornerShape(20.dp)).background(DS2).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    carChipIcon(c)?.let { Icon(painterResource(it), null, Modifier.size(18.dp), tint = Color(0xFFD7DADF)) }
                    Text(c, color = Color(0xFFD7DADF), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                }
        }
        if (i.kind == "call" || i.kind == "task") Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // H15: 서버가 보관한 실제 번호로 회신(화면엔 끝 4자리만). 문자는 보내기 전 확인 시트에서.
            if (i.kind == "call" && i.fromNumber.isNotBlank()) Pill("전화", Icons.Filled.Call, primary = true) { dial(ctx, i.fromNumber) }
            val shop = if (i.kind == "task") shopPhone(i.summary) else null // O4 유선 가게: 문자 대신 전화(카드는 정차 중에만 보인다)
            if (shop != null) Pill("가게에 전화", Icons.Filled.Call, primary = true) { dial(ctx, shop) }
            else if (i.kind == "task" && reserveShop(i.summary) != null && taskDraft(i.summary).first.isBlank()) Pill("내비에서 가게 찾기", Icons.Filled.Call, primary = true) { RoomService.searchInNavi?.invoke(reserveShop(i.summary)!!) }
            else if (i.kind == "task") Pill(if (mailDraft(i.summary) != null) "메일 보내기" else "문자 보내기", Icons.Filled.Send, primary = true, onClick = onSms)
            else Pill("문자", Icons.Filled.Send, onClick = onSms)
            Pill("완료", Icons.Filled.Check) { RoomService.markCardDone(ctx, i.id) }
            Pill("내일 아침에", Icons.Filled.DateRange) { RoomService.snooze(ctx, i.id) }
        }
        if (i.kind == "hint") Pill("완료", Icons.Filled.Check) { RoomService.markCardDone(ctx, i.id) } // 통화 정리도 치울 수 있게(안 그러면 쌓이기만 함)
    }
}

/** '쓴 차량 데이터' 칩 글머리(서버 carChip 문구 기준). 모르는 칩은 아이콘 없이. */
private fun carChipIcon(c: String): Int? = when {
    c.startsWith("기어") -> R.drawable.ic_car_gear
    c.startsWith("도착") -> R.drawable.ic_car_flag
    c.endsWith("운전") -> R.drawable.ic_car_trip
    c == "동승자" -> R.drawable.ic_car_people
    c == "경고등" -> R.drawable.ic_car_warn
    c.startsWith("배터리") -> R.drawable.ic_car_batt
    else -> null
}

/** 한 줄 대기열. 줄을 누르면 포커스로 올린다. */
@Composable
private fun ColumnScope.Queue(q: List<InboxItem>, hidden: Int, a: Float, carryTag: (InboxItem) -> String?, onShowAll: () -> Unit, onPick: (InboxItem) -> Unit) {
    val ctx = LocalContext.current
    val list = rememberLazyListState()
    val newest = q.maxOfOrNull { it.id }
    LaunchedEffect(newest) { if (newest != null) list.animateScrollToItem(0) } // 새 카드가 위에 숨지 않게
    if (q.isEmpty() && hidden == 0) { Spacer(Modifier.weight(1f)); return }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(DS1.copy(alpha = a)), list) {
        items(q, key = { it.id }) { i ->
            val (name, sub) = head(i)
            Row(Modifier.fillMaxWidth().height(102.dp).press { onPick(i) }.padding(start = 28.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(if (i.urgent) BrandHi else Color(0xFF4A5059)))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(name, Modifier.weight(1f, fill = false), color = DTx, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        (carryTag(i) ?: sub ?: if (i.kind == "hint") "AI 제안" else null)?.let { Text(it, color = DMute, fontSize = 20.sp, maxLines = 1) }
                    }
                    Text(cardText(i).ifBlank { "AI가 통화 내용을 정리하고 있어요" }, color = DMute, fontSize = 23.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(time(i.createdAt), color = DMute, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                if (i.kind == "call" && i.fromNumber.isNotBlank()) Round(Icons.Filled.Call, "전화", DS2) { dial(ctx, i.fromNumber) }
            }
            HorizontalDivider(color = DLine)
        }
        if (hidden > 0) item(key = "more") {
            Row(Modifier.fillMaxWidth().heightIn(min = 96.dp).press(onShowAll).padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("업무 ${hidden}건은 다음 평일 아침에 봐도 돼요", Modifier.weight(1f), color = DMute, fontSize = 23.sp)
                Text("지금 보기", color = BrandHi, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** C⑤ 조수석 착석 감지 시 확인 버튼(자동으로 바꾸지 않는다). */
@Composable
private fun PassengerOffer() {
    if (!RoomService.passengerOffer) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Pill("동승자가 탔어요 · 동승자 모드로 바꿀까요?", Icons.Filled.Person, primary = true, m = Modifier.weight(1f)) { RoomService.togglePassenger(true); RoomService.passengerOffer = false }
        Pill("아니요") { RoomService.passengerOffer = false }
    }
}

/** H3 동승자 모드 토글: 정차 화면(어두운 알약)·설정(밝은 글자 버튼). H9: 이번 운전만(저장 안 함). */
@Composable
private fun PassengerToggle(light: Boolean = false) {
    val on = RoomService.passenger
    val t = if (on) "동승자 켜짐(이번 운전만)" else "동승자 없음"
    if (light) { TextButton({ RoomService.togglePassenger(!on) }, Tall) { Text(t, color = if (on) ParkAcc else ParkInk, fontSize = 20.sp, fontWeight = FontWeight.Bold) }; return }
    Row(Modifier.height(76.dp).clip(RoundedCornerShape(38.dp)).background(if (on) Color(0xFF3A1D14) else DS1).press { RoomService.togglePassenger(!on) }.padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.Person, null, Modifier.size(28.dp), tint = if (on) BrandHi else DTx)
        Text(t, color = if (on) Color(0xFFFFB28A) else DTx, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 응대 모드 저장: 서버 저장이 성공해야 바뀐다(끔 없음, 2택). */
private fun saveMode(ctx: Context, scope: kotlinx.coroutines.CoroutineScope, m: String, t: String) = scope.launch {
    val ok = withContext(Dispatchers.IO) { Server.postMode(m).also { if (it) ctx.getSharedPreferences("car_ui", Context.MODE_PRIVATE).edit().putString("mode", m).apply() } }
    if (ok) RoomService.mode = m
    toast(ctx, if (ok) "$t(으)로 바꿨어요" else "저장 실패 — 다시 시도해 주세요")
}
private val MODES = listOf(
    Triple("ai", "AI가 대신 받기", "용건을 묻고 정리해서 카드로 남겨요"),
    Triple("ment", "한 마디 안내", "운전 중이라고만 알리고 끊어요. 문구는 설정에서 바꿔요"),
)
private val TITLES = listOf("님", "과장", "대표", "직접 입력")

// ── 5A 첫 실행(H13): 왼쪽 입력 3단계(①이름·호칭·응대 방식 ②착신 전환 ③방해 금지), 오른쪽 '상대가 듣게 될 첫 마디' ──
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FirstRun() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("car_ui", Context.MODE_PRIVATE) } // App이 IO에서 이미 읽어 둔 캐시
    var page by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf(prefs.getString("owner_label", "") ?: "") }
    var title by rememberSaveable { mutableIntStateOf(0) }
    var custom by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val owner = listOf(name.trim(), when (title) { 0 -> ""; 3 -> custom.trim(); else -> TITLES[title] }).filter { it.isNotEmpty() }.joinToString(" ")
    val ok = owner.isEmpty() || validName(owner)
    val words = firstWords(owner, prefs.getString("assistant_name", "") ?: "", RoomService.mode, prefs.getString("ment_text", "") ?: "")
    val askPerms = rememberPermAsk { RoomService.finishSetup(ctx) } // 마지막 단계: 거부해도 설정은 끝난다(설정 화면에 '권한 꺼짐' 줄)
    val next = {
        if (page < 2) {
            val saved = prefs.getString("owner_label", "") ?: ""
            if (page == 0 && owner.isNotEmpty() && owner != saved) {
                busy = true
                scope.launch {
                    val done = withContext(Dispatchers.IO) { Server.postOwner(owner).also { if (it) prefs.edit().putString("owner_label", owner).apply() } }
                    busy = false
                    if (!done) toast(ctx, "이름 저장 실패 — 설정에서 다시 저장할 수 있어요")
                    page = 1
                }
            } else page++
        } else missingPerms(ctx).let { if (it.isEmpty()) RoomService.finishSetup(ctx) else askPerms(it) }
    }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().background(Paper).padding(start = 60.dp, end = 60.dp, top = 56.dp, bottom = 40.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (k in 0..2) Box(Modifier.size(48.dp, 8.dp).clip(RoundedCornerShape(4.dp)).background(if (k <= page) Brand else Color(0xFFD2D5DA)))
                Text("${page + 1} / 3", Modifier.padding(start = 8.dp), color = Mute, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
            // 키보드가 올라오면 창이 줄고(adjustResize) 이 부분만 스크롤된다. 아래 버튼 줄은 항상 보인다.
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Text(listOf("전화를 대신 받을 때\n뭐라고 소개할까요?", "폰에서 착신 전환을\n설정해 주세요", "운전 중 방해 금지를\n켜 두세요")[page],
                    Modifier.padding(top = 34.dp, bottom = 40.dp), color = Ink, fontSize = 54.sp, lineHeight = 64.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp)
                val label: @Composable (String) -> Unit = { Text(it, Modifier.padding(bottom = 12.dp), color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                val body: @Composable (String) -> Unit = { Text(it, Modifier.padding(bottom = 16.dp), color = Ink.copy(alpha = 0.82f), fontSize = 26.sp, lineHeight = 39.sp) }
                when (page) {
                    0 -> {
                        label("이름")
                        Field(name, "예: 김민수", 38.sp, 96.dp) { name = it.take(10) }
                        FlowRow(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            TITLES.forEachIndexed { k, t ->
                                val on = title == k
                                Box(Modifier.height(76.dp).clip(RoundedCornerShape(38.dp)).background(if (on) Ink else Color.White)
                                    .then(if (on) Modifier else Modifier.border(2.dp, Line, RoundedCornerShape(38.dp))).press { title = k }.padding(horizontal = 28.dp), Alignment.Center) {
                                    Text(t, color = if (on) Color.White else Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        if (title == 3) Box(Modifier.padding(top = 12.dp)) { Field(custom, "호칭(예: 팀장)", 28.sp, 76.dp) { custom = it.take(6) } }
                        if (!ok) Text("이름과 호칭을 합쳐 10자 이내, 글자·숫자·공백만 쓸 수 있어요", Modifier.padding(top = 10.dp), color = Urgent, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                        Box(Modifier.height(34.dp))
                        label("응대 방식")
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            for ((m, t, d) in MODES) {
                                val on = modeShown(RoomService.mode) == m
                                val shape = RoundedCornerShape(24.dp)
                                Row(Modifier.fillMaxWidth().heightIn(min = 104.dp).clip(shape).background(if (on) Color(0xFFFFF3EC) else Color.White)
                                    .border(if (on) 3.dp else 2.dp, if (on) Brand else Line, shape).press { if (!on) saveMode(ctx, scope, m, t) }.padding(horizontal = 28.dp, vertical = 18.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                    Box(Modifier.size(40.dp).clip(CircleShape).border(if (on) 12.dp else 3.dp, if (on) Brand else Color(0xFFB5BAC1), CircleShape))
                                    Column {
                                        Text(t, color = Ink, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                        Text(d, color = Mute, fontSize = 22.sp)
                                    }
                                }
                            }
                        }
                    }
                    1 -> {
                        body("폰 설정 > 착신 전환 > '통화 중일 때'·'응답 없을 때'를 아래 번호로 바꿔 주세요.")
                        Text(RoomService.serviceNumber.ifBlank { "(서비스 번호 불러오는 중)" }, Modifier.padding(bottom = 24.dp), color = Ink, fontSize = 40.sp, fontWeight = FontWeight.ExtraBold)
                        if (RoomService.forwardOk) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Filled.Check, null, Modifier.size(32.dp), tint = RestC)
                            Text("착신 설정 확인됨", color = RestC, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        } else Pill("착신 설정 했어요", Icons.Filled.Check, light = true) { RoomService.confirmForward(ctx) }
                        Box(Modifier.height(20.dp))
                        body("실제 착신 전화가 한 번 들어오면 자동으로 확인돼요.")
                    }
                    else -> {
                        body("폰 설정 > 방해 금지(운전 모드) > 차 블루투스 연결 시 자동 켜기, '즐겨찾기 허용'에 가족 번호, '반복 전화 허용' 켜기.")
                        body("가족과 바로 다시 건 급한 분은 폰이 직접 울리고, 나머지는 AI가 받아요.")
                        body("벨은 폰에서 먼저 울려요. 앱이 막을 수는 없어서, 통신사 착신 '응답 없을 때' 대기 시간을 가장 짧게 해 두세요.")
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page == 0) Pill("건너뛰기", light = true) { page = 1 } else Pill("이전", light = true) { page-- }
                Spacer(Modifier.weight(1f))
                Pill(if (page == 2) "다 했어요" else "다음", if (page == 2) Icons.Filled.Check else Icons.Filled.ArrowForward, primary = true, light = true,
                    m = Modifier.alpha(if (busy || !ok) 0.5f else 1f)) { if (!busy && ok) next() }
            }
        }
        Column(Modifier.width(600.dp).fillMaxHeight().background(Ink).padding(horizontal = 48.dp, vertical = 56.dp), verticalArrangement = Arrangement.spacedBy(26.dp)) {
            Text("상대가 듣게 될 첫 마디", color = Color(0xFFB9BEC6), fontSize = 22.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(60.dp).clip(CircleShape).background(BrandHi), Alignment.Center) { Text("비", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold) }
                Text("내비서 · ${owner.ifEmpty { "내 전화" }} 대신", color = Color(0xFFC9CDD3), fontSize = 22.sp)
            }
            Text(buildAnnotatedString {
                append(words)
                if (owner.isNotEmpty()) words.indexOf(owner).takeIf { it >= 0 }?.let { addStyle(SpanStyle(color = Brand, fontWeight = FontWeight.ExtraBold), it, it + owner.length) }
            }, Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp, 32.dp, 32.dp, 8.dp)).background(Color.White).padding(horizontal = 32.dp, vertical = 30.dp),
                color = Ink, fontSize = 28.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold)
            Pill("목소리로 들어보기", Icons.Filled.PlayArrow, primary = true) { speak(ctx, words) }
        }
    }
}

/** 시안 입력칸: 흰 바탕 + 3dp 검은 테두리, 큰 글자. */
@Composable
private fun Field(v: String, hint: String, fs: androidx.compose.ui.unit.TextUnit, h: Dp, onChange: (String) -> Unit) =
    BasicTextField(v, onChange, Modifier.fillMaxWidth(), singleLine = true, cursorBrush = SolidColor(Brand),
        textStyle = TextStyle(color = Ink, fontSize = fs, fontWeight = FontWeight.Bold)) { inner ->
        Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(24.dp)).background(Color.White).border(3.dp, Ink, RoundedCornerShape(24.dp)).padding(horizontal = 30.dp),
            Alignment.CenterStart) {
            if (v.isEmpty()) Text(hint, color = Mute, fontSize = fs, fontWeight = FontWeight.Bold)
            inner()
        }
    }

/** 설정 화면용 응대 모드 2택. */
@Composable
private fun ModeButtons() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for ((m, t) in listOf("ai" to "AI 응대", "ment" to "한 마디 안내")) {
            val on = modeShown(RoomService.mode) == m
            Button({ saveMode(ctx, scope, m, t) }, Tall, colors = ButtonDefaults.buttonColors(containerColor = if (on) ParkAcc else ParkNote)) {
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
    val mail = remember(i.id) { if (i.kind == "task") mailDraft(i.summary) else null } // O2: 메일 줄이 있으면 문자 대신 메일
    var subject by remember(i.id) { mutableStateOf(mail?.second ?: "") }
    val draft = remember(i.id) { if (mail != null) mail.first to mail.third else if (i.kind == "task") taskDraft(i.summary) else i.fromNumber to "운전 중이라 전화를 못 받았습니다. 확인하고 다시 연락드리겠습니다." }
    val auto = i.kind == "call" && i.fromNumber.isNotBlank()
    var number by remember(i.id) { mutableStateOf(draft.first) }
    var text by remember(i.id) { mutableStateOf(draft.second) }
    var edit by remember(i.id) { mutableStateOf(false) }
    var busy by remember(i.id) { mutableStateOf(false) }
    val digits = number.filter { it.isDigit() }
    val fc = OutlinedTextFieldDefaults.colors(focusedTextColor = SheetInk, unfocusedTextColor = SheetInk, focusedBorderColor = BrandHi, unfocusedBorderColor = DOut,
        focusedLabelColor = BrandHi, unfocusedLabelColor = DMute, cursorColor = BrandHi)
    val ts = TextStyle(color = SheetInk, fontSize = 26.sp)
    val canSend = !busy && (if (mail != null) number.isNotBlank() else digits.isNotEmpty()) && text.isNotBlank()
    BackHandler(onBack = onDone)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(SheetBg).padding(horizontal = 32.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(if (mail != null) "메일 · 보내기 전 확인" else "${if (i.kind == "task") "할 일" else "${label(i)}님께"} 문자 · 보내기 전 확인", color = DMute, fontSize = 21.sp, fontWeight = FontWeight.Bold)
        if (auto) Text("받는 번호 · 끝번호 ${digits.takeLast(4)}(자동)", color = SheetInk, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        else OutlinedTextField(number, { number = it.take(if (mail != null) 80 else 13) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(if (mail != null) "받는 사람" else "받는 번호", fontSize = 20.sp) },
            textStyle = ts, colors = fc)
        if (mail != null) OutlinedTextField(subject, { subject = it.take(100) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("제목", fontSize = 20.sp) }, textStyle = ts, colors = fc)
        if (edit) OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), textStyle = ts, colors = fc)
        else Text("“$text”", color = SheetInk, fontSize = 26.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Pill("닫기", onClick = onDone)
            Spacer(Modifier.weight(1f))
            Pill(if (edit) "다 고침" else "고치기") { edit = !edit }
            Pill(if (busy) "보내는 중…" else "보내기", Icons.Filled.Send, primary = true, m = Modifier.alpha(if (canSend) 1f else 0.45f)) {
                if (!canSend) return@Pill
                if (mail == null && sendable(digits) == null) { toast(ctx, LANDLINE_SPEECH); speak(ctx, LANDLINE_SPEECH); return@Pill }
                busy = true
                scope.launch {
                    val code = withContext(Dispatchers.IO) { if (mail != null) Server.mail(number.trim(), subject.trim(), text.trim()) else Server.send(if (auto) i.fromLabel else "", digits, text.trim()) }
                    busy = false
                    toast(ctx, when (code) { "ok" -> "보냈어요"; null -> "서버 연결 실패 — 다시 시도해 주세요"; "overseas_ip_blocked" -> "못 보냄(서버 IP 제한)"; else -> "못 보냄 · ${sendFailReason(code)}" })
                    if (code == "ok") { RoomService.dropLocal(ctx, i.id); onDone() }
                }
            }
        }
    }
}

/** S2: 연속 주행 기준을 넘으면 주행 화면에 큰 버튼 1개. 움직임 없음, 한 번 터치. */
@Composable
private fun RestButton(bg: Color) {
    if (!RoomService.restOffer) return
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp).height(104.dp).clip(RoundedCornerShape(52.dp)).background(bg)
        .press { ctx.startService(Intent(ctx, RoomService::class.java).setAction(RoomService.ACTION_REST)) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Text("가까운 휴게소 들르기", color = Ink, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** L6: 런타임 권한 요청 창(여러 개 한 번에). 결과가 오면 onDone. */
@Composable
private fun rememberPermAsk(onDone: () -> Unit): (List<String>) -> Unit {
    val l = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { onDone() }
    return { l.launch(it.toTypedArray()) }
}
private fun missingPerms(ctx: Context) = missingPermissions(android.os.Build.VERSION.SDK_INT) { ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }

internal fun speak(ctx: Context, t: String) { ctx.startService(Intent(ctx, RoomService::class.java).setAction(RoomService.ACTION_SPEAK).putExtra("t", t)) }

/** 링 안 비서 상태: (두 줄 제목, 한 줄 설명, 준비됨). 실제 상태(서버·착신·응대 모드·방)에서만 고른다. */
private fun ringState(rest: Boolean, offline: Boolean, forwardOk: Boolean, mode: String, recent: InboxItem?): Triple<String, String, Boolean> = when {
    offline -> Triple("서버 연결\n끊김", "알림이 늦어져요", false)
    !forwardOk -> Triple("응대 준비\n안 됨", "착신 설정이 필요해요", false)
    rest -> Triple("조용히\n운전 중", "업무 전화는 메모만", true)
    modeShown(mode) == "ment" -> Triple("안내 멘트로\n응대 중", "운전 중이라고만 알려요", true)
    else -> Triple("전화\n대신 받는 중", recent?.let { "방금 ${head(it).first}" } ?: "용건을 받아 정리해요", true)
}

/** 주행 화면과 내비 위 띠가 같이 읽는 값. hear = [듣기] 버튼이 읽을 문장, at = 도착 시각(목적지 없으면 null). */
internal class DriveNow(val rest: Boolean, val acc: Color, val n: Int, val title: String, val sub: String, val ready: Boolean, val at: String?, val hear: String)
internal fun driveNow(r: String): DriveNow {
    val rest = r == "DRIVE_REST"
    val cards = RoomService.cards()
    val urgent = cards.filter { it.urgent && it.id !in RoomService.seen }
    val recent = cards.firstOrNull { it.kind == "call" && System.currentTimeMillis() - it.createdAt < 10 * 60_000 }
    val (title, sub, ready) = ringState(rest, RoomService.offline, RoomService.forwardOk, RoomService.mode, recent)
    return DriveNow(rest, if (rest) RestHi else BrandHi, if (rest) todo(cards, RoomService.seen) else urgent.size, title, sub, ready,
        RoomService.etaMin?.let { java.time.LocalTime.now(RoomService.clock).plusMinutes(it.toLong()).format(hhmm) },
        if (rest) recentCallsSpeech(cards) else if (urgent.none { it.kind == "call" }) "급한 전화는 없어요" else recentCallsSpeech(urgent).replaceFirst("최근 전화", "급한 전화"))
}

@Composable
private fun RoomBadge(r: String) {
    val rest = r == "DRIVE_REST"
    Row(Modifier.height(68.dp).clip(RoundedCornerShape(34.dp)).background(if (rest) Color(0xFF0F3A33) else Color(0xFF3A1D10)).padding(horizontal = 30.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val tc = if (rest) Color(0xFF7FE3CB) else Color(0xFFFFB48C)
        when (r) { "DRIVE_REST" -> R.drawable.ic_room_rest; "DRIVE_WORK" -> R.drawable.ic_room_work; else -> null }?.let { Icon(painterResource(it), null, Modifier.size(32.dp), tint = tc) }
        Text(when (r) { "DRIVE_REST" -> "쉼 방"; "DRIVE_WORK" -> "업무 방"; else -> "방 판정 전" }, color = tc, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
    }
}

// ── 2B 운전 화면 '다크 링': 업무 방 = 주황 열린 링, 쉼 방 = 청록 닫힌 링. 조작은 듣기 1개(+휴게소 S2) ──
@Composable
private fun Drive(r: String) {
    val ctx = LocalContext.current
    val d = driveNow(r); val rest = d.rest; val acc = d.acc
    Row(Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 60.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(776.dp)) { Ring(rest, d.ready, acc, d.title, d.sub) }
        Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = 20.dp)) {
            RoomBadge(r)
            Text("${d.n}", Modifier.padding(top = 56.dp), color = acc, fontSize = 200.sp, lineHeight = 170.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-10).sp)
            // H7: 쉼 방의 업무 건은 다음 첫 업무 운전에 '어제 넘긴 건'으로 말하고 P 화면 맨 앞에 묶는다
            Text(if (rest) "다음 출근길에 알려 드릴 업무" else "급한 건", Modifier.padding(top = 16.dp), color = DTx, fontSize = 38.sp, fontWeight = FontWeight.Bold)
            d.at?.let { t ->
                HorizontalDivider(Modifier.padding(top = 44.dp), thickness = 2.dp, color = Color(0xFF2A2F36))
                Row(Modifier.padding(top = 30.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(t, color = DTx, fontSize = 84.sp, lineHeight = 90.sp, fontWeight = FontWeight.ExtraBold)
                    Text("${RoomService.destName ?: "목적지"} 도착", Modifier.padding(bottom = 14.dp), color = DMute, fontSize = 30.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.weight(1f))
            RestButton(acc)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.weight(1f).height(104.dp).clip(RoundedCornerShape(52.dp)).background(Color(0xFF1C2026)).press { speak(ctx, d.hear) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(40.dp), tint = DTx)
                Text(if (rest) "들려줘" else "급한 건 듣기", color = DTx, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
            }
            Row(Modifier.weight(1f).height(104.dp).clip(RoundedCornerShape(52.dp)).background(acc).press { talk(ctx) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
                MicIcon(Ink, 40.dp)
                Text(if (RoomService.listening) "듣는 중…" else "말하기", color = Ink, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
            }
            }
        }
    }
}

/** 640dp 링. 들어올 때 한 번 차오르고(600ms) 상태가 바뀌면 그 값으로 이어서 움직인다. 깜빡임·반복 없음, 애니메이션 끔이면 바로. */
@Composable
private fun Ring(rest: Boolean, ready: Boolean, acc: Color, title: String, sub: String) {
    var on by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { on = true }
    val target = when { rest -> 1f; ready -> 0.7f; else -> 0.18f }
    val sweep by animateFloatAsState(if (on) target else 0f, if (reduceMotion()) snap() else tween(600, easing = FastOutSlowInEasing), label = "ring")
    val col = if (ready) acc else DMute
    Box(Modifier.size(640.dp), Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val st = 36.dp.toPx(); val o = 24.dp.toPx(); val box = Size(size.width - 2 * o, size.height - 2 * o)
            if (!rest) drawArc(Color(0xFF2A1A12), 0f, 360f, false, Offset(o, o), box, style = Stroke(st))
            else drawCircle(Color(0xFF16302B), 236.dp.toPx(), style = Stroke(2.dp.toPx()))
            drawArc(col.copy(alpha = if (rest) 0.9f else 1f), -90f, 360f * sweep, false, Offset(o, o), box, style = Stroke(st, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            if (rest) MoonIcon(col, 88.dp) else Icon(Icons.Filled.Call, null, Modifier.size(88.dp), tint = col)
            Text(title, color = DTx, fontSize = 52.sp, lineHeight = 60.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            Text(sub, color = DMute, fontSize = 28.sp, maxLines = 1)
        }
    }
}

/** 초승달(아이콘 묶음에 없음): 원에서 원을 뺀 모양. 사용자 배경 사진 위에서도 비치게 차집합으로. */
@Composable
internal fun MoonIcon(c: Color, s: Dp) = Canvas(Modifier.size(s)) {
    val u = size.width / 24f
    val disc = { cx: Float, cy: Float, r: Float -> Path().apply { addOval(Rect(Offset(cx * u, cy * u), r * u)) } }
    drawPath(Path.combine(PathOperation.Difference, disc(11f, 13f, 8.5f), disc(15.5f, 8.5f, 7.5f)), c)
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
        var missing by remember { mutableStateOf(missingPerms(ctx)) }
        var asked by remember { mutableStateOf(false) }
        val askPerms = rememberPermAsk { missing = missingPerms(ctx) }
        if (missing.isNotEmpty()) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0xFFFBE9E7)).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("권한 꺼짐 — ${missing.joinToString("·") { permName(it) }}", Modifier.weight(1f), color = Urgent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton({
                // 한 번 물었는데도 꺼져 있으면(시스템이 더 안 묻는 경우) 앱 정보 화면으로
                if (asked) runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { toast(ctx, "설정 앱에서 내비서 권한을 켜 주세요") }
                else { asked = true; askPerms(missing) }
            }, Tall) { Text("켜기", color = ParkAcc, fontSize = 22.sp, fontWeight = FontWeight.Black) }
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
            PassengerToggle(light = true)
        }
        val canOverlay = remember(RoomService.appFront) { android.provider.Settings.canDrawOverlays(ctx) } // 권한 화면에서 돌아오면 다시 읽는다
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("내비 위 상태 띠", Modifier.weight(1f), color = ParkInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton({
                if (!canOverlay) runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { toast(ctx, "설정 앱에서 내비서 권한을 켜 주세요") }
            }, Tall) { Text(if (canOverlay) "켜짐" else "꺼짐 — 켜기", color = if (canOverlay) ParkAcc else ParkInk, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
        // 이름 호출: 켜 두면 시동이 켜진 동안 마이크로 이름을 기다린다. 이름 전 음성은 기기 안에서 바로 버린다.
        val wakeOn = RoomService.wakeOn
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("이름으로 부르기", Modifier.weight(1f), color = ParkInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            TextButton({ RoomService.setWakeOn(ctx, !wakeOn) }, Tall) { Text(if (wakeOn) "켜짐" else "꺼짐", color = if (wakeOn) ParkAcc else ParkInk, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
        if (wakeOn) {
            var wakeName by rememberSaveable { mutableStateOf(RoomService.wakeName) }
            NameField("부를 이름", wakeName, "2~10자, 글자·숫자·공백만") { wakeName = it; if (validName(it) && it.count { c -> !c.isWhitespace() } >= 2) RoomService.setWakeName(ctx, it.trim()) }
            Text("이름을 듣기 전 음성은 저장·전송하지 않아요", color = ParkInk, fontSize = 18.sp)
            Text("음성 모델은 한국지능정보사회진흥원 사업결과인 AI Hub 「한국어 음성」 데이터로 학습됐어요", color = ParkInk, fontSize = 18.sp) // AI Hub 이용정책: 2차적 저작물에도 출처 표기
            Text("켜 두면 Gleo 버튼이 듣지 않아요", color = ParkInk, fontSize = 18.sp) // Gleo는 마이크가 쓰이는 중이면 버튼을 무시한다(에뮬 실측)
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
        Text("먼저 말 걸기", color = ParkInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((lv, t) in listOf(Proactive.OFF to "끄기", Proactive.NORMAL to "필요할 때만", Proactive.OFTEN to "자주")) {
                val on = RoomService.proactive == lv
                Button({ RoomService.proactive = lv; prefs.edit().putString("proactive", lv.name).apply() }, Tall,
                    colors = ButtonDefaults.buttonColors(containerColor = if (on) ParkAcc else ParkNote)) {
                    Text(t, color = if (on) Color.White else ParkInk, fontSize = 18.sp)
                }
            }
        }
        // 전화 건 사람에게 도착 예정을 말할지: 서버 저장이 성공해야 바뀐다. 모르는 번호엔 '운전 중'까지만(기본).
        var etaShare by remember { mutableStateOf(prefs.getString("eta_share", "known") ?: "known") }
        Text("전화 건 분께 도착 예정 알리기", color = ParkInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((v, t) in listOf("off" to "알리지 않기", "known" to "아는 번호만", "all" to "모두에게")) {
                Button({ scope.launch {
                    val ok = withContext(Dispatchers.IO) { Server.postEtaShare(v) }
                    if (ok) { etaShare = v; prefs.edit().putString("eta_share", v).apply() } else toast(ctx, "저장 실패 — 다시 시도해 주세요")
                } }, Tall, colors = ButtonDefaults.buttonColors(containerColor = if (etaShare == v) ParkAcc else ParkNote)) {
                    Text(t, color = if (etaShare == v) Color.White else ParkInk, fontSize = 18.sp)
                }
            }
        }
        Text("업무 끝 시각: ${RoomService.workEndHour}시", color = ParkInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (h in WORK_END_CHOICES) {
                val on = RoomService.workEndHour == h
                Button({ RoomService.setWorkEnd(ctx, h) }, Tall, colors = ButtonDefaults.buttonColors(containerColor = if (on) ParkAcc else ParkNote)) {
                    Text("${h}시", color = if (on) Color.White else ParkInk, fontSize = 18.sp)
                }
            }
        }
        Text("집 위치: ${RoomService.homeSrc}", color = ParkInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        OutlinedButton({ RoomService.addHomeFavorite?.invoke() }, Tall) { Text("내비 즐겨찾기에 집 추가", color = ParkInk, fontSize = 18.sp) }
        OutlinedButton({ toast(ctx, if (RoomService.setHomeHere?.invoke(ctx) == true) "지금 위치를 집으로 저장했어요" else "위치를 아직 못 받았어요") }, Tall) { Text("지금 위치를 집으로", color = ParkInk, fontSize = 18.sp) }
        var favText by rememberSaveable { mutableStateOf(RoomService.favs.joinToString(", ")) }
        OutlinedTextField(favText, { favText = it.take(100); RoomService.favs = parseFavs(favText); prefs.edit().putString("fav_senders", favText).apply() },
            Modifier.fillMaxWidth(), singleLine = true, label = { Text("업무 시간 밖에도 바로 읽을 알림 보낸이", fontSize = 18.sp) },
            supportingText = { Text("쉼표로 구분(예: 엄마, 아빠). 나머지는 차를 세우면 묶어서 알려 드려요") }, textStyle = TextStyle(color = ParkInk, fontSize = 22.sp))
        // H1: 번호는 서버에만 저장하고 앱엔 가려진 표시(끝 4자리)만 남긴다
        var family by rememberSaveable { mutableStateOf("") }
        var familyLabel by rememberSaveable { mutableStateOf(prefs.getString("family_label", "") ?: "") }
        OutlinedTextField(family, { family = it.filter(Char::isDigit).take(11) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("가족 번호(집에 가까워지면 도착 문자)", fontSize = 18.sp) },
            supportingText = { Text(if (familyLabel.isNotEmpty()) "저장됨 $familyLabel · 비우고 저장하면 지워요" else "숫자 10~11자리") }, textStyle = TextStyle(color = ParkInk, fontSize = 22.sp))
        Button({
            scope.launch {
                val l = withContext(Dispatchers.IO) { Server.postFamilyNumber(family) }
                if (l != null) { familyLabel = l; family = ""; prefs.edit().putString("family_label", l).apply() }
                toast(ctx, if (l != null) "저장했어요" else "저장 실패 — 다시 시도해 주세요")
            }
        }, Tall, enabled = family.isEmpty() || family.length in 10..11, colors = ButtonDefaults.buttonColors(containerColor = ParkAcc)) {
            Text("가족 번호 저장", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        // 바로 연결: 입력한 번호들로 목록을 통째로 바꾼다(원본은 서버에만, 앱엔 가려진 표시). 쉼·피로 중에도 이 번호의 전화는 차가 읽는다.
        var direct by rememberSaveable { mutableStateOf("") }
        var directLabels by rememberSaveable { mutableStateOf(prefs.getString("direct_labels", "") ?: "") }
        val directList = parseDirect(direct)
        fun saveDirect(l: List<String>) = scope.launch {
            val r = withContext(Dispatchers.IO) { Server.postDirectNumbers(l) }
            if (r != null) { directLabels = r.joinToString(", "); direct = ""; prefs.edit().putString("direct_labels", directLabels).apply() }
            toast(ctx, if (r != null) "저장했어요" else "저장 실패 — 번호를 확인하고 다시 시도해 주세요")
        }
        OutlinedTextField(direct, { direct = it.take(150) }, Modifier.fillMaxWidth(), singleLine = true, isError = direct.isNotBlank() && directList == null,
            label = { Text("바로 연결 번호(쉼표로 여러 개)", fontSize = 18.sp) },
            supportingText = { Text(if (directLabels.isNotEmpty()) "저장됨 $directLabels · 새로 저장하면 이 목록으로 바뀌어요" else "숫자 10~11자리, 최대 10개") }, textStyle = TextStyle(color = ParkInk, fontSize = 22.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button({ directList?.let(::saveDirect) }, Tall, enabled = !directList.isNullOrEmpty(), colors = ButtonDefaults.buttonColors(containerColor = ParkAcc)) {
                Text("바로 연결 저장", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton({ saveDirect(emptyList()) }, Tall, enabled = directLabels.isNotEmpty()) { Text("목록 비우기", color = ParkInk, fontSize = 18.sp) }
        }
        // 응대 멘트: 응대 모드가 '멘트'일 때 전화 건 사람에게 들려주는 문구(빈 값 = 기본 문구)
        var ment by rememberSaveable { mutableStateOf(prefs.getString("ment_text", "") ?: "") }
        var mentErr by rememberSaveable { mutableStateOf(false) }
        OutlinedTextField(ment, { ment = it.take(60); mentErr = false }, Modifier.fillMaxWidth(), singleLine = true, isError = mentErr, label = { Text("응대 멘트(멘트 모드일 때)", fontSize = 18.sp) },
            supportingText = { Text(if (mentErr) MENT_BAD else "비우고 저장하면 기본 문구예요") }, textStyle = TextStyle(color = ParkInk, fontSize = 22.sp))
        Button({
            scope.launch {
                val (v, err) = withContext(Dispatchers.IO) { Server.postMent(ment) }
                if (v != null) { ment = v; prefs.edit().putString("ment_text", v).apply() }
                mentErr = err == "bad ment_text"
                toast(ctx, if (v != null) "저장했어요" else if (mentErr) MENT_BAD else "저장 실패 — 다시 시도해 주세요")
            }
        }, Tall, colors = ButtonDefaults.buttonColors(containerColor = ParkAcc)) {
            Text("멘트 저장", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Text("전화 응대", color = ParkInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        ModeButtons()
        OutlinedButton({ RoomService.reopenSetup(ctx); onClose() }, Tall) { Text("처음 설정(착신·방해 금지) 다시 보기", color = ParkInk, fontSize = 18.sp) }
        NameField("내 이름(전화 인사에 나옴)", owner, "전화 받을 때 '○○님의 AI 비서입니다'로 소개해요") { owner = it }
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
private fun NameField(label: String, v: String, hint: String = "1~10자, 글자·숫자·공백만", onChange: (String) -> Unit) = OutlinedTextField(
    v, { onChange(it.take(10)) }, Modifier.fillMaxWidth(), singleLine = true, isError = v.isNotEmpty() && !validName(v),
    label = { Text(label, fontSize = 18.sp) }, supportingText = { Text(hint) },
    textStyle = TextStyle(color = ParkInk, fontSize = 22.sp),
)
