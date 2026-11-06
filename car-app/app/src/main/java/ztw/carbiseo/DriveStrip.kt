package ztw.carbiseo

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

// ponytail: 내비 창 가로 위치(px, 에뮬 2560x1440 실측 [853,90][2535,1300]). 안 맞추면 왼쪽 차량 패널까지 덮는다. 실차 값이 다르면 여기만 고친다
private const val NAVI_X = 853
private const val NAVI_W = 1682

/** 내비 위 하단 띠 창. 메인 스레드에서만. 못 띄우면 로그만 남기고 띠 없이 음성 안내가 계속된다. */
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
            // 띠 크기만 한 창이라 밖의 터치는 내비로 간다(NOT_FOCUSABLE이면 NOT_TOUCH_MODAL도 켜진다)
            wm.addView(v, WindowManager.LayoutParams(NAVI_W, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM or Gravity.LEFT; x = NAVI_X })
            view = v
        }.onFailure { failed = true; Log.w("ROOM", "strip add FAIL $it") }
    }
}

/** 시안 B안: 주행 화면과 같은 값을 한 줄로. 창 밀도 160 = 시안 px 1:1. */
@Composable
private fun Strip(r: String) {
    val ctx = LocalContext.current
    val d = driveNow(r)
    val col = if (d.ready) d.acc else DMute
    Column(Modifier.fillMaxWidth().background(DriveBg)) {
        Box(Modifier.fillMaxWidth().height(6.dp).background(d.acc))
        Row(Modifier.fillMaxWidth().height(198.dp).padding(horizontal = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (d.rest) MoonIcon(col, 60.dp) else Icon(Icons.Filled.Call, null, Modifier.size(60.dp), tint = col)
                Text(d.title.replace('\n', ' '), Modifier.padding(start = 16.dp).weight(1f, fill = false), color = DTx, fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${d.n}", Modifier.padding(start = 64.dp), color = d.acc, fontSize = 104.sp, lineHeight = 110.sp, fontWeight = FontWeight.ExtraBold)
                Text(if (d.rest) "출근길 업무" else "급한 건", Modifier.padding(start = 18.dp), color = DTx, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
            Row(Modifier.width(295.dp).padding(start = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                d.at?.let { t ->
                    Text(t, color = DTx, fontSize = 72.sp, lineHeight = 80.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                    Text("도착", color = DMute, fontSize = 30.sp, maxLines = 1)
                }
            }
            Row(Modifier.width(300.dp).height(104.dp).clip(RoundedCornerShape(52.dp)).background(Color(0xFF1C2026)).press { speak(ctx, d.hear) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(40.dp), tint = DTx)
                Text(if (d.rest) "들려줘" else "급한 건 듣기", color = DTx, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
            Spacer(Modifier.width(16.dp))
            Row(Modifier.width(274.dp).height(104.dp).clip(RoundedCornerShape(52.dp)).background(d.acc).press { talk(ctx) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                MicIcon(Ink, 40.dp)
                Text(if (RoomService.listening) "듣는 중…" else "말하기", color = Ink, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
        }
    }
}
