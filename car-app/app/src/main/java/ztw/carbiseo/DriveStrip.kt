package ztw.carbiseo

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
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

/** 내비 위 띠를 띄울 때: 운전 방이고, 앱이 앞에 없고, 다른 앱 위 권한이 있을 때만. */
fun stripVisible(room: String?, appFront: Boolean, canOverlay: Boolean) = (room == "DRIVE_WORK" || room == "DRIVE_REST") && !appFront && canOverlay

// ponytail: 내비 오른쪽 아래 빈칸(px, 에뮬 2560x1440 실측). 음량·설정 버튼과 현재 위치를 피한다. 실차 값이 다르면 여기만 고친다
private const val CARD_X = 1800
private const val CARD_Y = 1020
private const val CARD_W = 595
private const val CARD_H = 165

/** 내비 위 카드 창. 메인 스레드에서만. 못 띄우면 로그만 남기고 띠 없이 음성 안내가 계속된다. */
class DriveStrip(private val c: Context) : LifecycleOwner, SavedStateRegistryOwner {
    private val life = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this).apply { performRestore(null) }
    override val lifecycle: Lifecycle get() = life
    override val savedStateRegistry get() = saved.savedStateRegistry
    private val wm = c.getSystemService(WindowManager::class.java)
    private var view: ComposeView? = null
    private var failed = false // 조건이 풀릴 때까지 다시 안 띄운다(200ms마다 실패 로그 방지)

    init { life.currentState = Lifecycle.State.RESUMED }

    fun sync(show: Boolean) {
        if (!show) { failed = false; view?.let { v -> view = null; runCatching { wm.removeView(v) }.onFailure { Log.w("ROOM", "strip remove FAIL $it") } } }
        else if (view == null && !failed) runCatching {
            val v = ComposeView(c).apply {
                setViewTreeLifecycleOwner(this@DriveStrip); setViewTreeSavedStateRegistryOwner(this@DriveStrip)
                setContent { Strip(RoomService.screenOverride ?: RoomService.room) }
            }
            // 카드 크기만 한 창이라 밖의 터치는 내비로 간다(NOT_FOCUSABLE이면 NOT_TOUCH_MODAL도 켜진다)
            wm.addView(v, WindowManager.LayoutParams(CARD_W, CARD_H,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT; x = CARD_X; y = CARD_Y })
            view = v
        }.onFailure { failed = true; Log.w("ROOM", "strip add FAIL $it") }
    }
}

/** 내비 위 작은 카드: 상태 · 급한 건 수 · 말하기. 도착 시각은 내비가 보여 준다. 창 밀도 160 = px 1:1. */
@Composable
private fun Strip(r: String) {
    val ctx = LocalContext.current
    val d = driveNow(r)
    Row(Modifier.size(CARD_W.dp, CARD_H.dp).clip(RoundedCornerShape(28.dp)).background(DriveBg).border(3.dp, d.acc, RoundedCornerShape(28.dp)).padding(horizontal = 28.dp),
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
