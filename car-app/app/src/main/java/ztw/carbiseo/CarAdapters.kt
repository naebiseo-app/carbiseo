package ztw.carbiseo

import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.Proxy

/**
 * 차량 신호 -> RoomStateMachine.update. SDK 콜백은 어느 스레드로 올지 몰라 전부 메인으로 넘긴다.
 * 충전 상태: Pleos Vehicle 2.3.2 EvBattery에 "충전 중" 읽기/구독 API가 없어(SOC, 충전스위치 제어, 포트열림뿐) 연결하지 않음 -> charging=false 고정.
 * 위치: FusedLocation 아티팩트 대신 NaviHelper CurrentLocationInfo를 쓴다(의존성 추가 없이 같은 좌표).
 */
class CarAdapters(private val ctx: Context, private val m: RoomStateMachine) {
    private val ui = Handler(Looper.getMainLooper())
    private var vehicle: Vehicle? = null
    var navi: NaviHelper? = null
        private set
    private var car: Any? = null

    private fun set(f: Signals.() -> Signals) { ui.post { m.update(f) } }

    fun start() {
        runCatching { initGearAndUx() }.onFailure { Log.w(TAG, "gear/ux init FAIL $it") }
        runCatching { initNavi() }.onFailure { Log.w(TAG, "navi init FAIL $it") }
    }

    fun stop() {
        runCatching { navi?.release(); vehicle?.release() }
    }

    // ---- 기어(Pleos) + DO(android.car, 컴파일 스텁이 없어 리플렉션) ----
    private fun initGearAndUx() {
        val v = Vehicle(ctx).also { vehicle = it }
        v.initialize()
        ui.postDelayed({
            runCatching {
                v.getGear().registerGearSelection(object : GearSelectionListener {
                    override fun onSelected(g: VehicleGear?) = set { copy(gear = g.toGear()) }
                    override fun onFailed(e: Exception) { Log.w(TAG, "gear register FAIL $e") }
                })
                v.getGear().getCurrentGearSelectionState({ g -> set { copy(gear = g.toGear()) } }, { Log.w(TAG, "gear get FAIL $it") })
            }.onFailure { Log.w(TAG, "gear attach FAIL $it") }
        }, 2000)

        val carCls = Class.forName("android.car.Car")
        car = carCls.getMethod("createCar", Context::class.java).invoke(null, ctx)
        ui.postDelayed({ runCatching { attachUx(carCls) }.onFailure { Log.w(TAG, "uxr attach FAIL ${it.cause ?: it}") } }, 2000)
    }

    private fun attachUx(carCls: Class<*>) {
        val name = carCls.getField("CAR_UX_RESTRICTION_SERVICE").get(null) as String
        val mgr = carCls.getMethod("getCarManager", String::class.java).invoke(car, name) ?: return
        fun isDo(o: Any?) = o?.let { it.javaClass.getMethod("isRequiresDistractionOptimization").invoke(it) as Boolean } ?: false
        val cur = isDo(mgr.javaClass.getMethod("getCurrentCarUxRestrictions").invoke(mgr))
        set { copy(distractionOptimized = cur) }
        val iface = Class.forName("android.car.drivingstate.CarUxRestrictionsManager\$OnUxRestrictionsChangedListener")
        val l = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
            if (method.name == "onUxRestrictionsChanged") {
                val d = isDo(args?.firstOrNull()); set { copy(distractionOptimized = d) }
            }
            null
        }
        mgr.javaClass.getMethod("registerListener", iface).invoke(mgr, l)
    }

    private fun VehicleGear?.toGear() = when (this?.name) {
        "GEAR_PARK" -> Gear.PARK
        "GEAR_DRIVE" -> Gear.DRIVE
        "GEAR_REVERSE" -> Gear.REVERSE
        "GEAR_NEUTRAL" -> Gear.NEUTRAL
        else -> Gear.UNKNOWN
    }

    // ---- 내비: 목적지, 위치 ----
    private fun initNavi() {
        val n = NaviHelper(ctx).also { navi = it }
        n.addListener(object : NaviHelperEventListener {
            override fun onError(c: ai.pleos.playground.navi.constants.NaviErrorCode) { Log.w(TAG, "navi onError $c") }
            // 경로가 없을 때 SDK가 (0,0)을 주므로 목적지 없음으로 취급한다.
            override fun onDestinationInfo(i: DestinationInfo) = set {
                copy(destination = if (i.latitude == 0.0 && i.longitude == 0.0) null else LatLng(i.latitude, i.longitude))
            }
            override fun onCurrentLocationInfo(i: CurrentLocationInfo) = set { copy(location = LatLng(i.latitude, i.longitude)) }
            // 취소/종료만 목적지 해제로 본다. 경유지 추가 직후의 취소->재개 깜빡임은 상태머신 2초 유예가 흡수한다.
            override fun onRouteCancelled() { Log.i(TAG, "onRouteCancelled"); set { copy(destination = null) } }
            override fun onRouteEnded() = set { copy(destination = null) }
            override fun onDestinationChanged() { n.getDestinationInfo() }
            override fun onWaypointChanged(i: WaypointChangedInfo) { n.getDestinationInfo() }
            override fun onNavigationStatus(s: NavigationStatus) { Log.i(TAG, "navStatus $s"); if (s == NavigationStatus.ROUTE_DRIVE) n.getDestinationInfo() }
        })
        n.initialize({
            n.getDestinationInfo(); n.getCurrentLocationInfo()
            ui.post(object : Runnable { override fun run() { navi?.getCurrentLocationInfo(); ui.postDelayed(this, 5000) } })
        }, { Log.w(TAG, "navi initialize FAIL $it") })
    }

    companion object { const val TAG = "ROOMADP" }
}
