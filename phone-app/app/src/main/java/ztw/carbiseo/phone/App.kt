package ztw.carbiseo.phone

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.concurrent.Executors

// 시안 4B '다크 흐름형' 색
private val Bg = Color(0xFF101215); private val S1 = Color(0xFF1A1D22); private val S2 = Color(0xFF23272E)
private val Line = Color(0xFF2E333B); private val Tx = Color(0xFFF2F3F5); private val Mute = Color(0xFFA6ACB4)
private val Brand = Color(0xFFFF7A33); private val OnBrand = Color(0xFF14161A)
private val WarmBg = Color(0xFF3A1D10); private val WarmTx = Color(0xFFFFD2BA); private val WarmSub = Color(0xFFE8B79C)

class MainActivity : ComponentActivity() {
    private var listenerOn by mutableStateOf(false)
    private var events by mutableStateOf(emptyList<Event>())
    private var now by mutableLongStateOf(System.currentTimeMillis())
    private val prefs by lazy { Store.prefs(this) }
    private val watch = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k == "events") refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Bg.toArgb()), SystemBarStyle.dark(Bg.toArgb()))
        if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 0) // 디버그 시험 알림용
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Bg, primary = Brand, onPrimary = OnBrand, onBackground = Tx, onSurface = Tx)) {
                Surface(Modifier.fillMaxSize(), color = Bg, contentColor = Tx) {
                    // 폰 폭에서 한글이 음절 단위로 끊겨 '요' 한 글자만 다음 줄로 넘어가던 것: 어절 단위 줄바꿈(API 33+, 그 아래는 기존대로). 어절 규칙은 한국어 로캘에서만 켜져 글자 로캘을 ko로 고정
                    ProvideTextStyle(LocalTextStyle.current.copy(lineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase), localeList = LocaleList("ko"))) { Root() }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); prefs.registerOnSharedPreferenceChangeListener(watch) }
    override fun onStop() { prefs.unregisterOnSharedPreferenceChangeListener(watch); super.onStop() }
    override fun onResume() { super.onResume(); refresh(); syncContactsDaily() } // 설정 화면에서 돌아오면 권한 상태를 다시 읽는다

    private fun refresh() {
        listenerOn = getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(this, Listener::class.java))
        events = Store.events(this); now = System.currentTimeMillis()
    }

    // ───────── 연락처 연결 ─────────
    private val net = Executors.newSingleThreadExecutor() // 네트워크는 메인 스레드 밖에서
    private val contactsOn by lazy { pref("contactsOn") }
    private val askContacts = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { set(contactsOn, "contactsOn", true); sendContacts() } // 거부면 꺼진 채로 둔다
    }
    private fun hasContactsPermission() = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    private fun setContacts(on: Boolean) {
        if (!on) { set(contactsOn, "contactsOn", false); deleteContacts() }
        else if (hasContactsPermission()) { set(contactsOn, "contactsOn", true); sendContacts() }
        else askContacts.launch(Manifest.permission.READ_CONTACTS)
    }

    /** 서버분을 지운다. 실패하면 표시를 남겨 다음에 앱을 열 때 다시 지운다. */
    private fun deleteContacts() {
        prefs.edit().putBoolean("contactsDelete", true).apply()
        net.execute { if (Relay.contacts(emptyList())) prefs.edit().remove("contactsDelete").apply() }
    }

    /** 폰 연락처를 읽어 서버로(이름·번호는 로그에 남기지 않는다). 성공한 시각만 저장해 실패하면 다음에 다시 보낸다. */
    private fun sendContacts() {
        val r = contentResolver
        net.execute {
            val rows = runCatching {
                r.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
                }
            }.getOrNull() ?: return@execute
            if (Relay.contacts(normalizeContacts(rows))) prefs.edit().putLong("contactsAt", System.currentTimeMillis()).apply()
        }
    }

    /** 켜져 있고 권한이 있으면 앱을 열 때 하루 1번만 다시 보낸다. 권한이 회수됐으면 끄고 서버분도 지운다. */
    private fun syncContactsDaily() {
        if (!contactsOn.value) { if (prefs.getBoolean("contactsDelete", false)) deleteContacts(); return }
        if (!hasContactsPermission()) { setContacts(false); return }
        if (System.currentTimeMillis() - prefs.getLong("contactsAt", 0L) >= 24 * 3600_000L) sendContacts()
    }

    private fun open(i: Intent) = runCatching { startActivity(i) }
    private fun openListenerSettings() = open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    private fun openDialer() = open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
    private fun openWeb(url: String) = open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    /** 첫 실행·설정 값(앱 전용 저장소). */
    private fun pref(k: String) = mutableStateOf(prefs.getBoolean(k, false))
    private val onboarded by lazy { pref("onboarded") }; private val dialerOpened by lazy { pref("dialerOpened") }
    private val forwardDone by lazy { pref("forwardDone") }
    private val carrier by lazy { mutableStateOf(prefs.getString("carrier", null)) }
    private fun set(s: MutableState<Boolean>, k: String, v: Boolean) { s.value = v; prefs.edit().putBoolean(k, v).apply() }
    private fun setCarrier(v: String) { carrier.value = v; prefs.edit().putString("carrier", v).apply() }

    @Composable private fun Root() {
        val z = remember { ZoneId.systemDefault() }
        if (!onboarded.value) Onboarding() else Home(z)
    }

    // ───────── 첫 실행 ─────────
    @Composable private fun Onboarding() {
        val s = step(carrier.value, dialerOpened.value, forwardDone.value, listenerOn)
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Row(Modifier.padding(top = 18.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    progress(forwardDone.value, listenerOn).forEach { Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(if (it) Brand else Line)) }
                }
                Text("운전 중 걸려 온 전화,\n이렇게 흘러가요", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 33.sp, letterSpacing = (-0.6).sp,
                    modifier = Modifier.semantics { heading() })
                Flow()
                SetupCard()
                Text("통화 내용은 요약만 차에 남고, 원본 녹음은 저장하지 않아요.", color = Mute, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 14.dp, bottom = 16.dp))
            }
            Pill(ctaLabel(s), enabled = s != Step.PICK_CARRIER) {
                when (s) {
                    Step.FORWARD -> { openDialer(); set(dialerOpened, "dialerOpened", true) }
                    Step.CONFIRM_FORWARD -> set(forwardDone, "forwardDone", true)
                    Step.LISTENER -> openListenerSettings()
                    Step.DONE -> set(onboarded, "onboarded", true)
                    Step.PICK_CARRIER -> {}
                }
            }
            if (s != Step.DONE) TextButton({ set(onboarded, "onboarded", true) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text("나중에 할게요", color = Mute, fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    @Composable private fun Flow() {
        Row(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 22.dp, start = 8.dp, end = 8.dp), verticalAlignment = Alignment.Top) {
            FlowNode(Ic.phone, "내 폰", false); Dash(); FlowNode(Ic.headset, "비서가 받음", true); Dash(); FlowNode(Ic.car, "차에 요약", false)
        }
    }
    @Composable private fun FlowNode(icon: ImageVector, label: String, me: Boolean) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(62.dp).clip(RoundedCornerShape(20.dp)).background(if (me) Brand else S1), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(28.dp), tint = if (me) OnBrand else Tx)
            }
            Text(label, color = Mute, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    @Composable private fun RowScope.Dash() {
        Canvas(Modifier.weight(1f).padding(top = 30.dp, start = 6.dp, end = 6.dp).height(2.dp)) {
            drawLine(Color(0xFF4A5059), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
        }
    }

    /** 착신전환·알림 권한 카드. 첫 실행과 설정 탭이 같이 쓴다. */
    @Composable private fun SetupCard() {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(S1).padding(horizontal = 18.dp, vertical = 4.dp)) {
            val c = carrier.value
            ToggleRow(Ic.phone, "무응답 착신전환",
                if (forwardDone.value) "설정했다고 표시했어요 · 폰이 직접 확인하지는 못해요"
                else if (c == null) "통신사를 고르면 설정할 곳을 열어 드려요" else "$c 안내에 따라 설정한 뒤 켜 두세요",
                forwardDone.value) { set(forwardDone, "forwardDone", it) }
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CARRIERS.keys.forEach { name ->
                    val on = c == name
                    Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(if (on) Brand else S2)
                        .selectable(on, role = Role.RadioButton) { setCarrier(name) }, contentAlignment = Alignment.Center) {
                        Text(name, color = if (on) OnBrand else Tx, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (c != null) Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinkButton("전화 앱 열기", Modifier.weight(1f)) { openDialer(); set(dialerOpened, "dialerOpened", true) }
                LinkButton("$c 고객센터", Modifier.weight(1f)) { openWeb(CARRIERS.getValue(c)) }
            }
            HorizontalDivider(color = Line)
            ToggleRow(Ic.bell, "알림 읽기 권한", if (listenerOn) "켜짐 · 카톡·문자를 차에서 읽어 줘요" else "꺼짐 · 눌러서 설정 화면에서 켜 주세요", listenerOn) { openListenerSettings() }
        }
    }

    @Composable private fun ToggleRow(icon: ImageVector, title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(on, role = Role.Switch, onValueChange = onChange).padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(S2), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp)) }
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(sub, color = Mute, fontSize = 13.sp, lineHeight = 18.sp)
            }
            Switch(on, null, colors = SwitchDefaults.colors(checkedTrackColor = Brand, checkedThumbColor = OnBrand, uncheckedTrackColor = Color(0xFF3A4049),
                uncheckedThumbColor = Mute, uncheckedBorderColor = Color.Transparent))
        }
    }

    @Composable private fun LinkButton(label: String, m: Modifier, onClick: () -> Unit) {
        Row(m.heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(S2).clickable(onClick = onClick).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Ic.chev, null, Modifier.padding(start = 4.dp).size(16.dp), tint = Mute)
        }
    }

    @Composable private fun Pill(label: String, enabled: Boolean = true, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(CircleShape).background(if (enabled) Brand else S2)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(label, color = if (enabled) OnBrand else Mute, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (enabled) Icon(Ic.chev, null, Modifier.padding(start = 8.dp).size(19.dp), tint = OnBrand)
        }
    }

    // ───────── 홈·기록·설정 ─────────
    @Composable private fun Home(z: ZoneId) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        val lists = List(3) { rememberLazyListState() } // 탭마다 스크롤 위치 유지
        val scope = rememberCoroutineScope()
        BackHandler(enabled = tab != 0) { tab = 0 }
        Column(Modifier.fillMaxSize()) {
            Crossfade(tab, Modifier.weight(1f), animationSpec = tween(200), label = "tab") { t ->
                when (t) { 0 -> HomeTab(lists[0], z); 1 -> LogTab(lists[1], z); else -> SettingsTab(lists[2]) }
            }
            Row(Modifier.fillMaxWidth().background(Bg).navigationBarsPadding().height(64.dp)) {
                listOf(Ic.home to "홈", Ic.list to "기록", Ic.gear to "설정").forEachIndexed { i, (icon, label) ->
                    val on = tab == i
                    Column(Modifier.weight(1f).fillMaxHeight().selectable(on, role = Role.Tab) {
                        if (on) scope.launch { lists[i].animateScrollToItem(0) } else tab = i // 지금 탭을 다시 누르면 맨 위로
                    }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(icon, null, Modifier.size(22.dp), tint = if (on) Brand else Mute)
                        Text(label, color = if (on) Brand else Mute, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
    }

    private val pad = Modifier.fillMaxSize().statusBarsPadding()
    private val contentPad = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp)

    @Composable private fun HomeTab(state: LazyListState, z: ZoneId) {
        val b = banner(listenerOn, events, now, z)
        val todays = today(events, now, z)
        LazyColumn(pad, state, contentPad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                val warm = b.tone == Tone.ACTIVE
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(if (warm) WarmBg else S1)
                    .then(if (!listenerOn) Modifier.clickable { openListenerSettings() } else Modifier).padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(if (b.tone == Tone.IDLE) S2 else Brand), contentAlignment = Alignment.Center) {
                        Icon(if (b.tone == Tone.WARN) Ic.alert else Ic.car, null, Modifier.size(26.dp), tint = if (b.tone == Tone.IDLE) Tx else OnBrand)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(b.title, color = if (warm) WarmTx else Tx, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Text(b.sub, color = if (warm) WarmSub else Mute, fontSize = 13.5.sp, lineHeight = 19.sp)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 대신 받은 전화: 통화는 차·서버에서 처리되고 폰으로 오는 경로가 없어 숫자를 만들지 않는다.
                    Stat(Modifier.weight(1f), null, "대신 받은 전화", "차 화면에서 확인")
                    Stat(Modifier.weight(1f), sentToday(events, now, z).toString(), "오늘 차로 보낸 알림", null)
                }
            }
            item { Text("오늘 비서가 한 일", color = Mute, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 6.dp).semantics { heading() }) }
            item { Timeline(todays, z, "아직 오늘 한 일이 없어요", "메시지 알림이 오면 차로 보내고 여기에 남겨요") }
        }
    }

    @Composable private fun Stat(m: Modifier, value: String?, label: String, hint: String?) {
        Column(m.fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(S1).padding(horizontal = 16.dp, vertical = 14.dp)) {
            if (value != null) Text(value, color = Brand, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 38.sp)
            else Text(hint.orEmpty(), color = Tx, fontSize = 17.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp)
            Text(label, color = Mute, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable private fun Timeline(es: List<Event>, z: ZoneId, emptyTitle: String, emptySub: String) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(S1).padding(vertical = 4.dp)) {
            if (es.isEmpty()) Column(Modifier.padding(18.dp)) {
                Text(emptyTitle, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(emptySub, color = Mute, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
            es.forEach { e ->
                val (title, sub) = line(e)
                val hero = e.kind == Kind.REPLIED
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(if (hero) Brand else S2), contentAlignment = Alignment.Center) {
                        Icon(when (e.kind) { Kind.SENT -> Ic.msg; Kind.REPLIED -> Ic.mic; else -> Ic.alert }, null, Modifier.size(18.dp), tint = if (hero) OnBrand else Tx)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sub, color = Mute, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(hm(e.at, z), color = Mute, fontSize = 12.5.sp)
                }
            }
        }
    }

    @Composable private fun TabTitle(t: String) =
        Text(t, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp).semantics { heading() })

    @Composable private fun LogTab(state: LazyListState, z: ZoneId) {
        val days = events.groupBy { dayLabel(it.at, now, z) } // 최신순 유지
        LazyColumn(pad, state, contentPad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { TabTitle("기록") }
            if (days.isEmpty()) item { Timeline(emptyList(), z, "아직 기록이 없어요", "알림 전달과 음성 답장이 여기에 쌓여요") }
            days.forEach { (d, es) ->
                item(key = d) { Text(d, color = Mute, fontSize = 13.5.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 6.dp)) }
                item(key = "$d-list") { Timeline(es, z, "", "") }
            }
        }
    }

    @Composable private fun SettingsTab(state: LazyListState) {
        LazyColumn(pad, state, contentPad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { TabTitle("설정") }
            item { SetupCard() }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(S1).padding(horizontal = 18.dp, vertical = 4.dp)) {
                    ToggleRow(Ic.msg, "연락처 연결", "이름으로 문자를 보낼 때 번호를 찾아요. 휴대폰 번호와 이름이 내비서 서버에 저장되고, 끄면 지워요.", contactsOn.value) { setContacts(it) }
                }
            }
            item { Text("통화 내용은 요약만 차에 남고, 원본 녹음은 저장하지 않아요. 알림은 메시지 앱 이름과 보낸 사람만 이 폰에 기록돼요.", color = Mute, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.padding(horizontal = 4.dp)) }
            item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(S1).padding(18.dp)) {
                    Text("버전", Modifier.weight(1f), fontSize = 15.sp); Text(BuildConfig.VERSION_NAME, color = Mute, fontSize = 15.sp)
                }
            }
        }
    }
}

/** 시안의 선 아이콘(24 격자, 굵기 1.8, 둥근 끝)을 그대로 벡터로. */
private object Ic {
    private fun rr(x: Float, y: Float, w: Float, h: Float, r: Float) = "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 -$r ${r}h-${w - 2 * r}a$r $r 0 0 1 -$r -${r}v-${h - 2 * r}a$r $r 0 0 1 $r -${r}z"
    private fun circ(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 -${2 * r} 0z"
    private fun v(d: String) = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round).build()

    val phone = v("M5 4h3.5l1.6 4.2-2.1 1.4a11 11 0 0 0 6.4 6.4l1.4-2.1L20 15.5V19a1 1 0 0 1-1 1A16 16 0 0 1 4 5a1 1 0 0 1 1-1z")
    val headset = v("M4 14v-2a8 8 0 0 1 16 0v2" + rr(3.5f, 13.5f, 4f, 6f, 1.5f) + rr(16.5f, 13.5f, 4f, 6f, 1.5f) + "M20 19.5a3 3 0 0 1-3 2.5h-3")
    val car = v("M4 16v-3.5L6 7.5A2 2 0 0 1 7.9 6h8.2A2 2 0 0 1 18 7.5l2 5V16a1 1 0 0 1-1 1h-1.5M4 16a1 1 0 0 0 1 1h1.5M8.5 17h7M4.5 12.5h15" + circ(7.5f, 17f, 1.6f) + circ(16.5f, 17f, 1.6f))
    val bell = v("M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15zM10 20.5a2 2 0 0 0 4 0")
    val chev = v("M9 5l7 7-7 7")
    val msg = v("M4 6.5A2.5 2.5 0 0 1 6.5 4h11A2.5 2.5 0 0 1 20 6.5v7a2.5 2.5 0 0 1-2.5 2.5H10l-4.5 4v-4h0A1.5 1.5 0 0 1 4 14.5z")
    val mic = v(rr(9f, 3f, 6f, 11f, 3f) + "M5.5 11a6.5 6.5 0 0 0 13 0M12 17.5V21")
    val alert = v(circ(12f, 12f, 8.5f) + "M12 7.8v5M12 16.2h.01")
    val home = v("M4 11l8-6.5 8 6.5V20H4zM10 20v-5h4v5")
    val list = v("M8 7h12M8 12h12M8 17h12M4 7h.01M4 12h.01M4 17h.01")
    val gear = v(circ(12f, 12f, 3f) + "M12 2.8v2.4M12 18.8v2.4M21.2 12h-2.4M5.2 12H2.8M18.5 5.5l-1.7 1.7M7.2 16.8l-1.7 1.7M18.5 18.5l-1.7-1.7M7.2 7.2L5.5 5.5")
}
