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

/** 주차 중 카드를 연 항목 = 확인한 건. 프로세스 동안만 기억(회전에도 유지). */
private var seenIds by mutableStateOf(setOf<Long>())

private fun roomName(r: String) = when (r) { "PARK" -> "5분 사무실"; "DRIVE_WORK" -> "업무 운전"; "DRIVE_REST" -> "퇴근길"; else -> "판정 전" }
private fun accent(r: String) = when (r) { "PARK" -> ParkAcc; "DRIVE_REST" -> RestAcc; else -> WorkAcc }
private fun toast(c: Context, s: String) = Toast.makeText(c, s, Toast.LENGTH_SHORT).show()
private fun label(i: InboxItem) = i.fromLabel.ifBlank { "모르는 번호" }

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("car_ui", Context.MODE_PRIVATE) }
    var ver by remember { mutableIntStateOf(0) } // 배경 바뀌면 +1 -> 다시 디코드
    var settings by rememberSaveable { mutableStateOf(false) }
    val room = RoomService.room
    LaunchedEffect(room) { if (room != "PARK") settings = false } // 운전 시작하면 설정 닫음
    if (settings && room == "PARK") { Settings(prefs, { ver++ }) { settings = false }; return }

    // 방 전환: 우리 창 안에서만 대각선 와이프 300ms 1회
    var prev by remember { mutableStateOf(room) }
    val p = remember { Animatable(1f) }
    LaunchedEffect(room) { if (room != prev) { p.snapTo(0f); p.animateTo(1f, tween(300)); prev = room } }
    val bg = { r: String -> prefs.getString(r, null)?.let(::File)?.takeIf { it.exists() } }
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
    val items = RoomService.inbox
    var sel by rememberSaveable { mutableStateOf<Long?>(null) }
    val ink = if (onImage) Color.White else ParkInk
    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(roomName("PARK"), Modifier.weight(1f), color = if (onImage) Color.White else ParkAcc, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            TextButton(onSettings, Tall) { Text("꾸미기", color = ink, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
        Text(if (items.isEmpty()) "세워 둔 동안, 볼 건 없어요" else "세워 둔 동안, ${items.size}건만 보면 돼요",
            color = ink, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Black)
        val calls = todayCalls(items, java.time.LocalDate.now())
        if (calls > 0) Text("오늘 AI가 대신 받은 전화 ${calls}통 = 운전 중 통화 ${calls}번 줄임",
            Modifier.padding(top = 6.dp), color = if (onImage) Color.White else ParkBody, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        LazyColumn(Modifier.weight(1f).padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(items, key = { it.id }) { i ->
                Note(i, i.id == sel) { sel = if (sel == i.id) null else i.id; seenIds = seenIds + i.id }
            }
        }
        items.firstOrNull { it.id == sel }?.let { Sheet(it) { sel = null } }
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
            if (i.urgent) Text("급함", Modifier.padding(start = 8.dp).background(ParkAcc, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 2.dp),
                color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Text(i.summary.ifBlank { i.raw.take(80) }, color = ParkBody, fontSize = 18.sp, lineHeight = 26.sp)
    }
}

/** 보내기 전 확인. 시연 단계라 실제 발송 없음. */
@Composable
private fun Sheet(i: InboxItem, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember(i.id) { mutableStateOf("안녕하세요, ${i.fromLabel.ifBlank { "고객" }}님. 확인하고 다시 연락드리겠습니다.") }
    var edit by remember(i.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(SheetBg, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("${label(i)}님께 문자 · 보내기 전 확인", color = SheetInk.copy(alpha = 0.8f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        if (edit) OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), textStyle = TextStyle(color = SheetInk, fontSize = 20.sp))
        else Text("“$text”", color = SheetInk, fontSize = 20.sp, lineHeight = 28.sp)
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton({ edit = !edit }, Tall) { Text(if (edit) "다 고침" else "고치기", color = SheetInk, fontSize = 20.sp) }
            Button({ toast(ctx, "시연: 전송은 2차에서"); onDone() }, Tall, colors = ButtonDefaults.buttonColors(containerColor = ParkAcc)) {
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
    val n = RoomService.inbox.count { it.urgent && it.id !in seenIds }
    Box(Modifier.fillMaxSize().padding(24.dp)) {
        Text(roomName(r), color = WorkAcc, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("AI가 대신 받는 중", color = WorkInk, fontSize = 30.sp, fontWeight = FontWeight.Black)
            Text("$n", color = WorkInk, fontSize = 120.sp, lineHeight = 130.sp, fontWeight = FontWeight.Black)
            Text("급한 건", color = WorkInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        RestButton(WorkAcc, WorkBg1, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun Rest() {
    val n = RoomService.inbox.count { it.id !in seenIds }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text(roomName("DRIVE_REST"), color = RestAcc, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("오늘 일은 여기까지", color = RestInk, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Black)
        // TODO: 집까지 ETA·가족 전화 카드 — 내비 ETA·가족 연락처 데이터가 생기면 추가
        Spacer(Modifier.weight(1f))
        RestButton(RestAcc, RestBg1, Modifier.padding(bottom = 16.dp))
        Text("업무 ${n}건은 내일 아침에 알려 드릴게요", color = RestInk, fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
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
            Text("꾸미기", Modifier.weight(1f), color = ParkInk, fontSize = 30.sp, fontWeight = FontWeight.Black)
            TextButton(onClose, Tall) { Text("닫기", color = ParkInk, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
        for (r in listOf("PARK", "DRIVE_WORK", "DRIVE_REST")) {
            Text("${roomName(r)} 배경", color = ParkInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ target = r; pick.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }, Tall,
                    colors = ButtonDefaults.buttonColors(containerColor = ParkAcc)) { Text("사진/GIF 고르기", fontSize = 18.sp) }
                OutlinedButton({ prefs.edit().remove(r).apply(); File(ctx.filesDir, "bg_$r").delete(); onBg() }, Tall) {
                    Text("기본으로", color = ParkInk, fontSize = 18.sp)
                }
            }
        }
        Text("휴식 제안: 연속 운전 ${RoomService.restMin}분부터", color = ParkInk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((min, t) in listOf(1 to "1분(시연)", 60 to "60분", REST_DEFAULT_MIN to "120분")) {
                val on = RoomService.restMin == min
                Button({ RoomService.restMin = min; prefs.edit().putInt("rest_min", min).apply() }, Tall,
                    colors = ButtonDefaults.buttonColors(containerColor = if (on) ParkAcc else ParkNote)) {
                    Text(t, color = if (on) Color.White else ParkInk, fontSize = 18.sp)
                }
            }
        }
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
