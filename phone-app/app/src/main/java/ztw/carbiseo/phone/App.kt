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
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.concurrent.Executors

// 폰 앱 '내비서 연결'의 화면·권한·설정 코드. 한 파일에 섞여 있어 읽는 순서를 적는다.
//  1. MainActivity.onCreate → Root → Onboarding(첫 실행) 또는 Home(홈·기록·설정 탭)
//  2. onResume → refresh(알림 접근 허용 여부·로컬 기록·하차 정보), syncContactsDaily, refreshGoogle
//  3. 하차 정보: readParked, finishParked, ParkedCard
//  4. 연락처 연결: setContacts → sendContacts·deleteContacts
//  5. 구글 캘린더 연결: connectGoogle → sendGoogle, disconnectGoogle
//  6. 화면 조각: SetupCard(착신전환·알림 권한), HomeTab, LogTab, SettingsTab, Timeline. 맨 아래 Ic는 아이콘.
// 맡지 않는 일: 알림 전달·답장(Listener.kt), 서버 호출(Relay.kt), 화면 판정 로직(Model.kt).
// 이 파일이 서버로 올리는 것: 연락처 이름·휴대폰 번호(설정에서 켠 때만), 구글 인증 코드(연결할 때만), 끝낸 일 id. 알림 전달은 Listener.kt.

// 시안 4B '다크 흐름형' 색
private val Bg = Color(0xFF101215); private val S1 = Color(0xFF1A1D22); private val S2 = Color(0xFF23272E)
private val Line = Color(0xFF2E333B); private val Tx = Color(0xFFF2F3F5); private val Mute = Color(0xFFA6ACB4)
private val Brand = Color(0xFFFF7A33); private val OnBrand = Color(0xFF14161A)
private val WarmBg = Color(0xFF3A1D10); private val WarmTx = Color(0xFFFFD2BA); private val WarmSub = Color(0xFFE8B79C)

/** "오후 2:12" 꼴 시각. */
private fun ampm(t: Long, z: ZoneId) = java.time.Instant.ofEpochMilli(t).atZone(z).let { "${if (it.hour < 12) "오전" else "오후"} ${if (it.hour % 12 == 0) 12 else it.hour % 12}:${"%02d".format(it.minute)}" }

/** 단일 액티비티. 화면이 읽는 상태(권한 여부·기록·하차 정보·설정 값)를 필드로 들고 Compose가 읽는다. */
class MainActivity : ComponentActivity() {
    private var listenerOn by mutableStateOf(false)
    private var events by mutableStateOf(emptyList<Event>())
    private var now by mutableLongStateOf(System.currentTimeMillis())
    private val prefs by lazy { Store.prefs(this) }
    // 로컬 기록("events")이 바뀌면 화면을 다시 읽는다.
    private val watch = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k == "events") refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Bg.toArgb()), SystemBarStyle.dark(Bg.toArgb()))
        if (BuildConfig.DEBUG && Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 0) // 디버그 시험 알림용
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Bg, primary = Brand, onPrimary = OnBrand, onBackground = Tx, onSurface = Tx)) {
                Surface(Modifier.fillMaxSize(), color = Bg, contentColor = Tx) {
                    // 한글을 어절 단위로 줄바꿈한다(API 33+, 그 아래는 기본 동작). 음절 단위면 폰 폭에서 '요' 한 글자만 다음 줄로 넘어간다.
                    // 어절 규칙은 한국어 로캘에서만 켜져 글자 로캘을 ko로 고정한다.
                    ProvideTextStyle(LocalTextStyle.current.copy(lineBreak = LineBreak.Paragraph.copy(wordBreak = LineBreak.WordBreak.Phrase), localeList = LocaleList("ko"))) { Root() }
                }
            }
        }
    }

    override fun onStart() { super.onStart(); prefs.registerOnSharedPreferenceChangeListener(watch) }
    override fun onStop() { prefs.unregisterOnSharedPreferenceChangeListener(watch); super.onStop() }
    override fun onResume() { super.onResume(); refresh(); syncContactsDaily(); refreshGoogle() } // 설정 화면에서 돌아오면 권한 상태를 다시 읽는다

    /** 알림 접근 허용 여부와 로컬 기록을 다시 읽고, 하차 정보는 서버에서 다시 받는다(readParked). */
    private fun refresh() {
        listenerOn = getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(ComponentName(this, Listener::class.java))
        events = Store.events(this); now = System.currentTimeMillis()
        readParked()
    }

    // ───────── 하차 정보 ─────────
    private var parked by mutableStateOf<Parked?>(null)
    private var parkedHide by mutableStateOf(emptySet<Long>()) // 틱을 누른 일. 서버 응답 전 읽기가 되살리지 못하게 parked와 따로 둔다
    private var parkedMsg by mutableStateOf("")

    /**
     * 하차 정보 읽기. 서버 응답 원문 저장본(오프라인·지하 주차장에서도 위치를 보이려고)으로 먼저 그리고, 서버가 답하면 판정(parkedNext)대로 바꾼다.
     * 서버로는 읽기 요청만 하고 올리는 것은 없다. 저장본에는 위치·주소·메모·할 일 제목이 남고 전화번호는 뺀다(parkedCache).
     */
    private fun readParked() {
        parkedMsg = ""
        if (parked == null) prefs.getString("parked", null)?.let(::parseParked)?.let { parked = it }
        val fake = if (BuildConfig.DEBUG) intent.getStringExtra("parked_json")?.let(::parseParked) else null // 디버그 빌드만: am start --es parked_json '<json>' 이면 서버 대신 그 값을 그린다(화면 확인용)
        if (fake != null) { parked = fake; return }
        net.execute {
            val n = parkedNext(Relay.parked(), prefs.getString("parked", null))
            if (n.clear) prefs.edit().remove("parked").apply() else n.write?.let { prefs.edit().putString("parked", parkedCache(it)).apply() }
            runOnUiThread { parked = n.show }
        }
    }

    /** 틱을 누른 일을 먼저 숨기고 서버에 끝냈다고 알린다(보내는 것은 일 id뿐). 실패하면 되살리고 안내 문구를 띄우고, 성공하면 저장본에서도 뺀다. */
    private fun finishParked(id: Long) {
        parkedHide += id; parkedMsg = ""
        net.execute { if (Relay.parkedDone(id)) prefs.getString("parked", null)?.let { prefs.edit().putString("parked", parkedCache(it, id)).apply() } else runOnUiThread { parkedHide -= id; parkedMsg = "끝내지 못했어요. 다시 눌러 주세요" } }
    }

    // ───────── 연락처 연결 ─────────
    // 설정 탭에서 사용자가 켠 때만 동작한다. 켜면 연락처 읽기 권한을 묻고, 허용되면 이름·휴대폰 번호를 서버에 올린다.
    // 끄면 서버 사본을 지운다. 권한을 거부하면 꺼진 채로 둔다.
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

    /** 서버분을 지운다(빈 목록을 올리면 서버가 저장분을 지운다). 실패하면 표시(contactsDelete)를 남겨 다음에 앱을 열 때 다시 지운다. */
    private fun deleteContacts() {
        prefs.edit().putBoolean("contactsDelete", true).apply()
        net.execute { if (Relay.contacts(emptyList())) prefs.edit().remove("contactsDelete").apply() }
    }

    /**
     * 폰 연락처의 이름·전화번호만 읽어 서버로 올린다(이름·번호는 로그에 남기지 않는다).
     * 휴대폰 번호 꼴이 아닌 행은 normalizeContacts가 버리고, 읽기에 실패하면 아무것도 올리지 않는다.
     * 성공한 시각만 저장해, 실패하면 다음에 앱을 열 때 다시 보낸다.
     */
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

    /**
     * 켜져 있고 권한이 있으면, 마지막으로 보낸 지 24시간이 지났을 때 앱을 열면서 다시 보낸다. 권한이 회수됐으면 끄고 서버분도 지운다.
     * 꺼진 상태에서는 지우기 실패 표시가 남아 있을 때만 다시 지운다.
     */
    private fun syncContactsDaily() {
        if (!contactsOn.value) { if (prefs.getBoolean("contactsDelete", false)) deleteContacts(); return }
        if (!hasContactsPermission()) { setContacts(false); return }
        if (System.currentTimeMillis() - prefs.getLong("contactsAt", 0L) >= 24 * 3600_000L) sendContacts()
    }

    // ───────── 구글 캘린더 연결 ─────────
    // 구글이 폰에 서버용 인증 코드를 주면 서버로 넘기기만 한다. 토큰은 폰에 저장하지 않는다.
    // 요청 범위는 일정 읽기 전용(calendar.events.readonly)이다. 연결을 끊으면 서버가 토큰 폐기를 요청한다(Relay.googleDisconnect).
    // googleState가 null이면 아직 서버 상태를 못 읽은 것이고, 연결 안 됨으로 그린다(googleLabels).
    private val googleState = mutableStateOf<GoogleState?>(null)
    private val googleMsg = mutableStateOf("")
    private val askGoogle = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        runCatching { Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(r.data).serverAuthCode }
            .onSuccess { sendGoogle(it) }.onFailure { googleMsg.value = "연결하지 못했어요. 다시 시도해 주세요" } // 취소도 여기로 온다
    }

    private fun refreshGoogle() { net.execute { googleState.value = Relay.googleState() } }

    private fun sendGoogle(code: String?) {
        if (code.isNullOrEmpty()) { googleMsg.value = "연결하지 못했어요. 다시 시도해 주세요"; return }
        googleMsg.value = "연결하는 중이에요"
        net.execute {
            googleMsg.value = if (Relay.googleConnect(code)) "" else "연결하지 못했어요. 잠시 뒤 다시 시도해 주세요"
            googleState.value = Relay.googleState()
        }
    }

    private fun connectGoogle() {
        googleMsg.value = ""
        val req = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope("https://www.googleapis.com/auth/calendar.events.readonly")))
            .requestOfflineAccess(GOOGLE_WEB_CLIENT_ID).build()
        // 처음이면 구글 동의 화면(pendingIntent)을 거쳐 askGoogle로, 이미 허용돼 있으면 바로 인증 코드를 서버로 넘긴다.
        Identity.getAuthorizationClient(this).authorize(req)
            .addOnSuccessListener { r ->
                val pi = r.pendingIntent
                if (r.hasResolution() && pi != null) askGoogle.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                else sendGoogle(r.serverAuthCode)
            }
            .addOnFailureListener { googleMsg.value = "연결하지 못했어요. 다시 시도해 주세요" }
    }

    private fun disconnectGoogle() {
        net.execute {
            googleMsg.value = if (Relay.googleDisconnect()) "" else "끊지 못했어요. 잠시 뒤 다시 시도해 주세요"
            googleState.value = Relay.googleState()
        }
    }

    // 처리할 앱이 없어 startActivity가 실패해도 앱이 죽지 않게 한다.
    private fun open(i: Intent) = runCatching { startActivity(i) }
    // 알림 접근은 앱이 직접 켤 수 없어 시스템 설정 화면을 연다.
    private fun openListenerSettings() = open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    private fun openDialer() = open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:")))
    private fun openWeb(url: String) = open(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    /**
     * 첫 실행·설정 값(앱 전용 저장소). 키: onboarded, dialerOpened, forwardDone, carrier, contactsOn, contactsAt, contactsDelete.
     * 같은 저장소에 parked(하차 저장본)와 events(로컬 기록, Store)도 있다. 전화번호는 저장하지 않는다.
     */
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

    /** 착신전환·알림 권한 카드. 첫 실행과 설정 탭이 같이 쓴다. 착신전환은 폰이 확인할 수 없어 사용자가 직접 켠 것으로 표시한다(forwardDone). */
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
            // 알림 접근은 앱이 켜고 끌 수 없어 누르면 시스템 설정 화면을 연다. 꺼져 있으면 홈 배너가 경고로 바뀐다(banner).
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
            parked?.let { p -> item(key = "parked") { ParkedCard(p, z) } }
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

    /**
     * 2a 위치 먼저: 주차 위치 카드(차 화면 링과 같은 조각 링) + 남은 일. 치수는 시안 그대로.
     * 지도 열기는 geo: 인텐트로 지도 앱에 좌표만 넘기고, 전화 아이콘은 다이얼러만 연다(바로 걸지 않는다).
     */
    @Composable private fun ParkedCard(p: Parked, z: ZoneId) {
        val (tasks, total) = parkedLeft(p, parkedHide)
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("주차 위치", Modifier.alignByBaseline().semantics { heading() }, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                if (p.at > 0) Text("${ampm(p.at, z)} 저장", Modifier.alignByBaseline(), color = Mute, fontSize = 13.sp)
            }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(S1).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(196.dp), contentAlignment = Alignment.Center) {
                    SegRing(minOf(total, 8))
                    Text(p.memo.ifBlank { "메모 없음" }, Modifier.width(150.dp), color = if (p.memo.isBlank()) Mute else Tx, fontSize = 27.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.01).em,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, style = LocalTextStyle.current.copy(lineBreak = LineBreak.Heading))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (p.address.isNotBlank()) Text(p.address, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    Text("다음 출발 때 지워져요", color = Mute, fontSize = 13.sp, lineHeight = 18.sp)
                }
                val src = remember { MutableInteractionSource() }
                val k by animateFloatAsState(if (src.collectIsPressedAsState().value) 0.97f else 1f, tween(160), label = "press")
                Row(Modifier.fillMaxWidth().height(48.dp).graphicsLayer { scaleX = k; scaleY = k }.clip(CircleShape).background(Brand)
                    .clickable(src, LocalIndication.current, role = Role.Button) { open(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.lat},${p.lng}?q=${p.lat},${p.lng}"))) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    Icon(Ic.pin, null, Modifier.size(20.dp), tint = OnBrand)
                    Text("지도 열기", color = OnBrand, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            if (total == 0) Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ParkedPic()
                Text("남은 일이 없어요", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Text("주차 위치만 남겨 둘게요", color = Mute, fontSize = 13.sp)
            } else {
                Row(Modifier.padding(top = 20.dp, bottom = 6.dp).semantics(mergeDescendants = true) { heading() }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("남은 일", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    Text("$total", color = Brand, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                }
                if (parkedMsg.isNotEmpty()) Text(parkedMsg, color = WarmSub, fontSize = 13.sp, modifier = Modifier.padding(bottom = 6.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(S1).padding(start = 4.dp, end = 12.dp)) {
                    tasks.forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 8.dp), color = Line)
                        Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { finishParked(t.id) }.semantics { contentDescription = "${t.title} 끝냈어요" }, contentAlignment = Alignment.Center) {
                                Box(Modifier.size(26.dp).border(2.dp, Color(0xFF4A5059), CircleShape))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(t.title, fontSize = 15.5.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                t.sub?.let { Text(it, color = Mute, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                            t.number?.let { n ->
                                Box(Modifier.size(44.dp).clip(CircleShape).background(S2).clickable(role = Role.Button) { open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n"))) }, contentAlignment = Alignment.Center) {
                                    Icon(Ic.phone, "${t.title} 전화 걸기", Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                    if (total > tasks.size) Text("외 ${total - tasks.size}건", Modifier.padding(start = 54.dp, top = 4.dp, bottom = 14.dp), color = Mute, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    /** 조각 n개(0이면 온전한 링). 차 화면 SegRing과 같은 각도. */
    @Composable private fun SegRing(n: Int) = Canvas(Modifier.fillMaxSize()) {
        val st = 12.dp.toPx(); val o = Offset(8.dp.toPx(), 8.dp.toPx()); val box = Size(size.width - 2 * o.x, size.height - 2 * o.y)
        drawArc(if (n == 0) Brand else Color(0xFF2A1A12), 0f, 360f, false, o, box, style = Stroke(st))
        for (k in 0 until n) drawArc(Brand, -90f + k * 360f / n + 8f, 360f / n - 16f, false, o, box, style = Stroke(st, cap = StrokeCap.Round))
    }

    /** 시안 그림 parked(120 격자): 주차선 안에 세운 차를 위에서 본 모습 + 끝남 표시. */
    @Composable private fun ParkedPic() = Canvas(Modifier.size(96.dp)) {
        scale(size.width / 120f, pivot = Offset.Zero) {
            for (x in listOf(18f, 102f)) drawLine(Color(0xFF4A5059), Offset(x, 14f), Offset(x, 106f), 4f, StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
            drawRoundRect(Tx, Offset(36f, 18f), Size(48f, 84f), CornerRadius(16f), style = Stroke(3.5f))
            val line = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            drawPath(PathParser().parsePathString("M44 46c10-6 22-6 32 0l-4 12c-8-3-16-3-24 0z").toPath(), Tx, style = line)
            drawPath(PathParser().parsePathString("M47 84c8 3 18 3 26 0").toPath(), Tx, style = line)
            drawCircle(Brand, 20f, Offset(90f, 92f))
            drawPath(PathParser().parsePathString("M81 92l7 7l12-13").toPath(), OnBrand, style = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
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
                    // 켜면 이름·휴대폰 번호가 서버에 올라가고 끄면 지운다(setContacts).
                    ToggleRow(Ic.msg, "연락처 연결", "이름으로 문자를 보낼 때 번호를 찾아요. 휴대폰 번호와 이름이 내비서 서버에 저장되고, 끄면 지워요.", contactsOn.value) { setContacts(it) }
                }
            }
            item {
                // 연결돼 있으면 끊기, 연결 안 됨·만료면 (다시) 연결.
                val (sub, action) = googleLabels(googleState.value)
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(S1).padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(S2), contentAlignment = Alignment.Center) { Icon(Ic.list, null, Modifier.size(20.dp)) }
                        Column(Modifier.weight(1f)) {
                            Text("구글 캘린더 연결", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(sub, color = Mute, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                    }
                    if (googleMsg.value.isNotEmpty()) Text(googleMsg.value, color = WarmSub, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    LinkButton(action, Modifier.fillMaxWidth().padding(top = 10.dp)) { if (googleState.value?.connected == true && !googleState.value!!.expired) disconnectGoogle() else connectGoogle() }
                }
            }
            // 개인정보 안내 한 줄. 서버로 나가는 것의 전체 목록은 PRIVACY.md에 있다.
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
    /** 둥근 사각형 path 문자열(왼쪽 위 x·y, 폭, 높이, 모서리 반지름). */
    private fun rr(x: Float, y: Float, w: Float, h: Float, r: Float) = "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 -$r ${r}h-${w - 2 * r}a$r $r 0 0 1 -$r -${r}v-${h - 2 * r}a$r $r 0 0 1 $r -${r}z"
    /** 원 path 문자열(중심 cx·cy, 반지름 r). */
    private fun circ(cx: Float, cy: Float, r: Float) = "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 -${2 * r} 0z"
    /** path 문자열 → 선 아이콘 벡터. 선은 검정이고 색은 쓰는 쪽의 tint가 정한다. */
    private fun v(d: String) = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round).build()

    val pin = v("M9 11a3 3 0 1 0 6 0a3 3 0 0 0-6 0M17.657 16.657l-4.243 4.243a2 2 0 0 1-2.827 0l-4.244-4.243a8 8 0 1 1 11.314 0z")
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
