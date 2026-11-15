package ztw.carbiseo

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

// 내비 위에 띄우는 작은 카드(방 상태·급한 건 수·[말하기]). 언제 띄울지(stripVisible)·어디까지 끌 수 있는지(clampCard)는
// 순수 함수라 DriveStripTest가 지키고, 창을 만들고 지우는 DriveStrip은 RoomService가 200ms 틱마다 sync로 부른다.

/** 내비 위 카드를 띄울 때: 운전 방이고, 앱이 앞에 없고, 다른 앱 위 권한이 있을 때만. */
fun stripVisible(room: String?, appFront: Boolean, canOverlay: Boolean) = (room == "DRIVE_WORK" || room == "DRIVE_REST") && !appFront && canOverlay

// 한계: 내비 오른쪽 아래 빈칸(px, 에뮬 2560x1440 실측)이다. 음량·설정 버튼과 현재 위치를 피한다 (넘으려면 실차 화면에서 다시 재어 아래 4개 값만 고친다)
private const val CARD_X = 1800
private const val CARD_Y = 1020
private const val CARD_W = 595
private const val CARD_H = 165

/** 끌어 옮긴 카드 위치를 화면 안으로 묶는다(카드 전체가 보이게). */
fun clampCard(x: Int, y: Int, screenW: Int, screenH: Int): Pair<Int, Int> =
    x.coerceIn(0, maxOf(0, screenW - CARD_W)) to y.coerceIn(0, maxOf(0, screenH - CARD_H))

/** 내비 위 카드 창. 메인 스레드에서만. 못 띄우면 로그만 남기고 카드 없이 음성 안내가 계속된다. */
class DriveStrip(private val c: Context) : LifecycleOwner, SavedStateRegistryOwner {
    private val life = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this).apply { performRestore(null) }
    override val lifecycle: Lifecycle get() = life
    override val savedStateRegistry get() = saved.savedStateRegistry
    private val wm = c.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var failed = false // 조건이 풀릴 때까지 다시 안 띄운다(200ms마다 실패 로그 방지)
    private val prefs = c.getSharedPreferences("car_ui", Context.MODE_PRIVATE)
    private var lp: WindowManager.LayoutParams? = null

    /** 카드를 손가락으로 끈 만큼 옮긴다. 손을 떼면 위치를 저장해 다음에도 그 자리에 띄운다. */
    private fun drag(dx: Float, dy: Float) {
        val v = view ?: return; val p = lp ?: return
        val b = screen()
        clampCard(p.x + dx.toInt(), p.y + dy.toInt(), b.x, b.y).let { (x, y) -> p.x = x; p.y = y }
        runCatching { wm.updateViewLayout(v, p) }
    }
    // 앱 창(displayMetrics)이 아니라 화면 전체 크기. 앱 창 기준이면 카드가 왼쪽으로 밀린다(11-11 실측)
    @Suppress("DEPRECATION") private fun screen() = android.graphics.Point().also { wm.defaultDisplay.getRealSize(it) }
    private fun dragEnd() { lp?.let { prefs.edit().putInt("strip_x", it.x).putInt("strip_y", it.y).apply() } }

    init { life.currentState = Lifecycle.State.RESUMED }

    fun sync(show: Boolean) {
        if (!show) { failed = false; view?.let { v -> view = null; runCatching { wm.removeView(v) }.onFailure { Log.w("ROOM", "strip remove FAIL $it") } } }
        else if (view == null && !failed) runCatching {
            val v = ComposeView(c).apply {
                setViewTreeLifecycleOwner(this@DriveStrip); setViewTreeSavedStateRegistryOwner(this@DriveStrip)
                setContent { Strip(RoomService.screenOverride ?: RoomService.room, ::drag, ::dragEnd) }
            }
            // 카드 크기만 한 창이라 밖의 터치는 내비로 간다(NOT_FOCUSABLE이면 NOT_TOUCH_MODAL도 켜진다)
            val b = screen()
            val (x0, y0) = clampCard(prefs.getInt("strip_x", CARD_X), prefs.getInt("strip_y", CARD_Y), b.x, b.y)
            val p = WindowManager.LayoutParams(CARD_W, CARD_H,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT; x = x0; y = y0 }
            wm.addView(v, p)
            view = v; lp = p
        }.onFailure { failed = true; Log.w("ROOM", "strip add FAIL $it") }
    }
}

/** 내비 위 작은 카드: 상태 · 급한 건 수 · 말하기. 도착 시각은 내비가 보여 준다. 창 밀도 160 = px 1:1. */
@Composable
private fun Strip(r: String, onDrag: (Float, Float) -> Unit, onDragEnd: () -> Unit) {
    val ctx = LocalContext.current
    val d = driveNow(r)
    Row(Modifier.size(CARD_W.dp, CARD_H.dp).clip(RoundedCornerShape(28.dp)).background(DriveBg).border(3.dp, d.acc, RoundedCornerShape(28.dp))
        .pointerInput(Unit) { detectDragGestures(onDragEnd = onDragEnd) { ch, a -> ch.consume(); onDrag(a.x, a.y) } }.padding(horizontal = 28.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(d.title.replace('\n', ' '), color = DTx, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (d.rest) "출근길 업무" else "급한 건", color = DMute, fontSize = 28.sp, maxLines = 1)
                Text("${d.n}", Modifier.padding(start = 14.dp), color = d.acc, fontSize = 48.sp, lineHeight = 54.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Row(Modifier.size(176.dp, 96.dp).clip(RoundedCornerShape(48.dp)).background(d.acc).press { talk(ctx) },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(if (RoomService.listening) "듣는 중…" else "말하기", color = Ink, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        }
    }
}
