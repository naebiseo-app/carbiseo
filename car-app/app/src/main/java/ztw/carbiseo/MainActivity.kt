package ztw.carbiseo

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/** 방별 B안 화면(Screens.kt). 판정은 RoomService가 한다. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // CAR_SPEED는 AAOS에서 런타임 권한. 거부되면 속도만 null(제한속도·카메라·주의구간은 그대로 동작).
        requestPermissions(arrayOf("android.car.permission.CAR_SPEED") + if (Build.VERSION.SDK_INT >= 33) arrayOf("android.permission.POST_NOTIFICATIONS") else emptyArray(), 0)
        startForegroundService(Intent(this, RoomService::class.java))
        setContent { App() }
    }
}
