package ztw.carbiseo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.StrictMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** H1: 부팅·업데이트 뒤 서비스 자동 시작. ponytail: 잠긴 부팅(LOCKED_BOOT_COMPLETED)은 안 받는다 — prefs가 사용자 잠금 해제 뒤에야 열려서. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Log.i("ROOM", "boot ${i.action} -> start service")
        c.startForegroundService(Intent(c, RoomService::class.java))
    }
}

/** 방별 B안 화면(Screens.kt). 판정은 RoomService가 한다. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // 디버그 빌드만: 메인 스레드 디스크·네트워크 등 차단 호출을 logcat(StrictMode)에 남긴다
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        super.onCreate(savedInstanceState)
        // L6: 런타임 권한은 첫 실행 마지막 단계(Screens FirstRun)에서 한 번에 묻는다. 거부되면 설정 화면에 '권한 꺼짐 — 켜기' 줄.
        startForegroundService(Intent(this, RoomService::class.java))
        setContent { App() }
    }
    override fun onResume() { super.onResume(); RoomService.appFront = true }
    override fun onPause() { RoomService.appFront = false; super.onPause() }
}
