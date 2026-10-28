package ztw.carbiseo

import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.RouteInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Clock

/** 화면 1장: 현재 방 + 입력 신호 값. 디자인 없음. */
class MainActivity : ComponentActivity() {
    private val ui = Handler(Looper.getMainLooper())
    private var roomText by mutableStateOf("(판정 전)")
    private var sigText by mutableStateOf("")
    private lateinit var machine: RoomStateMachine
    private lateinit var adapters: CarAdapters

    private val ticker = object : Runnable {
        override fun run() { machine.tick(); sigText = machine.signals.toString(); ui.postDelayed(this, 200) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        machine = RoomStateMachine(Clock.systemDefaultZone()) // settings = 기본 상수. 설정 화면이 machine.settings를 덮어쓴다.
        machine.onChange = { from, to ->
            Log.i("ROOM", "$from -> $to")
            roomText = to.name
        }
        adapters = CarAdapters(this, machine).also { it.start() }
        setContent {
            Column(Modifier.padding(24.dp)) {
                Text("방: $roomText", fontSize = 32.sp)
                Text(sigText, fontSize = 16.sp)
            }
        }
        ui.post(ticker)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) registerDebugRoute()
    }

    // 디버그 빌드 전용: adb shell am broadcast -a ztw.carbiseo.ROUTE --ef lat 37.4979 --ef lng 127.0276
    private fun registerDebugRoute() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val lat = i.getFloatExtra("lat", 0f).toDouble()
                val lng = i.getFloatExtra("lng", 0f).toDouble()
                Log.i("ROOM", "debug requestRoute $lat,$lng")
                adapters.navi?.requestRoute(RouteInfo(lat, lng, null, null, "debug", "", "", "", RouteOption.RECOMMENDED, "", false))
            }
        }
        val f = IntentFilter("ztw.carbiseo.ROUTE")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED) else registerReceiver(r, f)
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker); adapters.stop(); super.onDestroy()
    }
}
