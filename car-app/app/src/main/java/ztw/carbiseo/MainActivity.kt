package ztw.carbiseo

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 화면 1장: 현재 방 + 입력 신호 값. 디자인 없음. 판정은 RoomService가 한다. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 0)
        startForegroundService(Intent(this, RoomService::class.java))
        setContent {
            Column(Modifier.padding(24.dp)) {
                Text("방: ${RoomService.room}", fontSize = 32.sp)
                Text(RoomService.sig, fontSize = 16.sp)
            }
        }
    }
}
