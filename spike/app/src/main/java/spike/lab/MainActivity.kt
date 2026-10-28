package spike.lab

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import java.lang.reflect.Proxy

// 스파이크 = 버리는 코드. 모든 결과는 Logcat 태그 SPIKE.
class MainActivity : Activity() {
    private lateinit var tv: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var navi: NaviHelper? = null
    private var vehicle: Vehicle? = null
    private var carObj: Any? = null

    private fun log(m: String) {
        Log.i("SPIKE", m)
        ui.post { tv.append(m + "\n") }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        tv = TextView(this).apply { textSize = 14f }
        setContentView(ScrollView(this).apply { addView(tv) })
        log("start sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL}")
        val f = IntentFilter("spike.CMD")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(cmd, f, Context.RECEIVER_EXPORTED) else registerReceiver(cmd, f)
        runCatching { initCar() }.onFailure { log("Q1 initCar FAIL $it") }
        runCatching { initNavi() }.onFailure { log("Q2 initNavi FAIL $it") }
        runCatching { initVehicle() }.onFailure { log("Q3 initVehicle FAIL $it") }
    }

    // ---------- Q1: android.car (리플렉션: 컴파일 스텁 없이) ----------
    private fun initCar() {
        val carCls = Class.forName("android.car.Car")
        val car = carCls.getMethod("createCar", Context::class.java).invoke(null, this)
        log("Q1 Car.createCar -> $car")
        carObj = car
        ui.postDelayed({ runCatching { attachCarManagers(carCls) }.onFailure { log("Q1 attach FAIL $it") } }, 2000)
    }

    private fun mgr(carCls: Class<*>, constName: String): Any? {
        val name = carCls.getField(constName).get(null) as String
        val m = carCls.getMethod("getCarManager", String::class.java).invoke(carObj, name)
        log("Q1 manager $constName($name) = ${m?.javaClass?.simpleName}")
        return m
    }

    private fun proxyListener(ifaceName: String, tag: String, show: (Any?) -> String): Any {
        val iface = Class.forName(ifaceName)
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
            if (method.declaringClass != Any::class.java) log("$tag ${method.name} ${show(args?.firstOrNull())}")
            null
        }
    }

    private fun attachCarManagers(carCls: Class<*>) {
        // 1) UX restrictions
        runCatching { mgr(carCls, "CAR_UX_RESTRICTION_SERVICE")?.let { m ->
            val cur = m.javaClass.getMethod("getCurrentCarUxRestrictions").invoke(m)
            log("Q1 UXR initial ${uxr(cur)}")
            val ifn = "android.car.drivingstate.CarUxRestrictionsManager\$OnUxRestrictionsChangedListener"
            val l = proxyListener(ifn, "Q1 UXR") { uxr(it) }
            m.javaClass.getMethod("registerListener", Class.forName(ifn)).invoke(m, l)
            log("Q1 UXR registered")
        } }.onFailure { log("Q1 UXR FAIL ${it.cause ?: it}") }
        // 2) driving state (대체 신호)
        runCatching { mgr(carCls, "CAR_DRIVING_STATE_SERVICE")?.let { m ->
            val ifn = "android.car.drivingstate.CarDrivingStateManager\$CarDrivingStateEventListener"
            val l = proxyListener(ifn, "Q1 DRVSTATE") { it?.toString() ?: "null" }
            m.javaClass.getMethod("registerListener", Class.forName(ifn)).invoke(m, l)
            log("Q1 DRVSTATE registered")
        } }.onFailure { log("Q1 DRVSTATE FAIL ${it.cause ?: it}") }
        // 3) CarPropertyManager: 속도/기어
        runCatching { mgr(carCls, "PROPERTY_SERVICE")?.let { m ->
            val ifn = "android.car.hardware.property.CarPropertyManager\$CarPropertyEventCallback"
            val cb = Class.forName(ifn)
            val l = Proxy.newProxyInstance(cb.classLoader, arrayOf(cb)) { _, method, args ->
                if (method.name == "onChangeEvent") log("Q1 PROP ${args?.get(0)}")
                else if (method.name == "onErrorEvent") log("Q1 PROP err ${args?.toList()}")
                null
            }
            for ((id, nm) in listOf(0x11600207 to "PERF_VEHICLE_SPEED", 0x11400400 to "GEAR_SELECTION", 0x11200402 to "PARKING_BRAKE_ON")) {
                runCatching {
                    val ok = m.javaClass.getMethod("registerCallback", cb, Int::class.java, Float::class.java).invoke(m, l, id, 5f)
                    log("Q1 PROP register $nm -> $ok")
                }.onFailure { log("Q1 PROP register $nm FAIL ${it.cause ?: it}") }
            }
        } }.onFailure { log("Q1 PROP FAIL ${it.cause ?: it}") }
    }

    private fun uxr(o: Any?): String = if (o == null) "null" else runCatching {
        "DO=${o.javaClass.getMethod("isRequiresDistractionOptimization").invoke(o)} active=${o.javaClass.getMethod("getActiveRestrictions").invoke(o)}"
    }.getOrElse { o.toString() }

    // ---------- Q2: NaviHelper ----------
    private fun initNavi() {
        val n = NaviHelper(this).also { navi = it }
        n.addListener(object : NaviHelperEventListener {
            override fun onTBTInfo(l: List<TBTInfo>) = log("Q2 onTBTInfo n=${l.size} " + l.joinToString { "[${it.type} dist=${it.distance} '${it.description}']" })
            override fun onCautionPointInfo(l: List<CautionPoint>) = log("Q2 onCautionPointInfo n=${l.size} " + l.joinToString { "[${it.type} remain=${it.remainDistance}]" })
            override fun onNavigationStatus(s: ai.pleos.playground.navi.constants.NavigationStatus) = log("Q2 onNavigationStatus $s")
            override fun onRouteStarted(i: RouteStartInfo) = log("Q2 onRouteStarted $i")
            override fun onRouteEnded() = log("Q2 onRouteEnded")
            override fun onRoutingCompleted() = log("Q2 onRoutingCompleted")
            override fun onRouteCancelled() = log("Q2 onRouteCancelled")
            override fun onWaypointChanged(i: WaypointChangedInfo) = log("Q4 onWaypointChanged $i")
            override fun onWayPointInfo(l: List<WaypointInfo>) = log("Q4 onWayPointInfo n=${l.size} $l")
            override fun onDestinationInfo(i: DestinationInfo) = log("Q2 onDestinationInfo $i")
            override fun onRouteStateInfo(i: RouteStateInfo) = log("Q2 onRouteStateInfo $i")
            override fun onCurrentLocationInfo(i: CurrentLocationInfo) = log("Q2 onCurrentLocationInfo $i")
            override fun onDrivingInfo(i: DrivingInfo) = log("Q2 onDrivingInfo $i")
            override fun onCurrentRoadSpeedLimit(v: Int) = log("Q2 onCurrentRoadSpeedLimit $v")
            override fun onCameraAlert(l: List<CameraAlert>) = log("Q2 onCameraAlert $l")
            override fun onError(c: ai.pleos.playground.navi.constants.NaviErrorCode) = log("Q2 onError $c")
        })
        n.initialize({ log("Q2 navi initialize OK") }, { log("Q2 navi initialize FAIL $it") })
    }

    // ---------- Q3: Vehicle SDK ----------
    private fun initVehicle() {
        val v = Vehicle(this).also { vehicle = it }
        v.initialize()
        ui.postDelayed({ runCatching { attachVehicle(v) }.onFailure { log("Q3 attach FAIL $it") } }, 2000)
    }

    private fun attachVehicle(v: Vehicle) {
        v.getGear().registerGearSelection(object : GearSelectionListener {
            override fun onSelected(g: VehicleGear?) = log("Q1 GEAR(PleosSDK) onSelected $g")
            override fun onFailed(e: Exception) = log("Q1 GEAR register FAIL $e")
        })
        v.getGear().getCurrentGearSelectionState({ log("Q1 GEAR current $it") }, { log("Q1 GEAR current FAIL $it") })
        v.getSeat().checkSeatOccupancyCapabilities({ a: List<SeatArea> -> log("Q3 seat occupancy caps ${a.map { it.values.toList() }}") }, { e: Exception -> log("Q3 seat caps FAIL $e") })
        for (sv in listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_CENTER, SeatValue.ROW_2_RIGHT)) {
            val area = SeatArea(sv)
            v.getSeat().getCurrentSeatOccupancy(area, { log("Q3 SEAT $sv current=$it") }, { log("Q3 SEAT $sv current FAIL $it") })
            v.getSeat().registerSeatOccupancy(area, object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(b: Boolean?) = log("Q3 SEAT $sv updated=$b")
                override fun onFailed(e: Exception) = log("Q3 SEAT $sv register FAIL $e")
            })
        }
        v.getSafety().checkTelltalesCapabilities({ log("Q3 telltale caps $it") }, { log("Q3 telltale caps FAIL $it") })
        v.getSafety().getTelltalesInfo({ logTell("current", it) }, { log("Q3 telltale get FAIL $it") })
        v.getSafety().registerTelltales(object : TelltaleInfoListener {
            override fun onTelltaleInfoUpdated(t: Telltales?) { if (t != null) logTell("updated", t) }
            override fun onFailed(e: Exception) = log("Q3 telltale register FAIL $e")
        })
    }

    private fun logTell(w: String, t: Telltales) {
        val all = t.telltales
        val key = all.filter { it.type.name.let { n -> n == "DRIVER_ATTENTION" || n == "SEAT_BELT" } }
        log("Q3 TELLTALE $w total=${all.size} key=" + key.joinToString { "${it.type}=${it.state}" } +
            " nonOff=" + all.filter { it.state.name != "OFF" }.joinToString { "${it.type}=${it.state}" })
    }

    // ---------- adb shell am broadcast -a spike.CMD --es cmd route|wp|cancel|dump|gear|tell|uxr ----------
    private val cmd = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val s = i.getStringExtra("cmd") ?: return
            log("CMD $s")
            val n = navi
            runCatching {
                when (s) {
                    "route" -> n?.requestRoute(RouteInfo(37.5663, 126.9779, null, null, "Seoul City Hall", "", "", "", RouteOption.RECOMMENDED, "", false))
                    "wp" -> n?.addWaypoint(RequestWaypointInfo(37.2636, 127.0286, "Test rest area", WaypointIndex.FIRST, "", "", "", ""))
                    "cancel" -> n?.cancelRoute()
                    "dump" -> { n?.getRouteStateInfo(); n?.getDestinationInfo(); n?.getWaypointInfo(); n?.getTBTInfo(); n?.getCurrentLocationInfo() }
                    "gear" -> vehicle?.getGear()?.getCurrentGearSelectionState({ log("Q1 GEAR current $it") }, { log("Q1 GEAR FAIL $it") })
                    "tell" -> vehicle?.getSafety()?.getTelltalesInfo({ logTell("poll", it) }, { log("Q3 telltale poll FAIL $it") })
                    "uxr" -> carObj?.let { car ->
                        val cc = Class.forName("android.car.Car")
                        val m = cc.getMethod("getCarManager", String::class.java).invoke(car, cc.getField("CAR_UX_RESTRICTION_SERVICE").get(null))
                        log("Q1 UXR poll " + uxr(m!!.javaClass.getMethod("getCurrentCarUxRestrictions").invoke(m)))
                    }
                    else -> {}
                }
            }.onFailure { log("CMD $s FAIL $it") }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(cmd); navi?.release(); vehicle?.release() }
        super.onDestroy()
    }
}
