package ztw.carbiseo

import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.telltale.TelltaleColor
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.listener.DrivingTimeListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
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
    private val bgThread = HandlerThread("vehicle").apply { start() }
    private val bg = Handler(bgThread.looper)
    private var vehicle: Vehicle? = null
    var navi: NaviHelper? = null
        private set
    private var car: Any? = null

    // 차 상태 반응 입력(메인 스레드에서만 씀). null = 모름/미지원.
    var speedKmh: Float? = null; private set
    var limitKmh: Int? = null; private set
    var cameraOverspeed = false; private set
    var cautionZone = false; private set
    var etaMin: Int? = null; private set // 목적지까지 남은 분. null = 목적지 없음.
    var destName: String? = null; private set // 목적지 이름(내비 DestinationInfo.name). 비었거나 목적지 없음 = null
    // C: Pleos Vehicle SDK 값(메인 스레드에서만 씀). null = 모름/권한 없음.
    var turnOn = false; private set
    var steerDeg: Float? = null; private set
    var tripMin: Int? = null; private set
    var soc: Int? = null; private set
    var passengerSeat: Boolean? = null; private set // 조수석 착석 원값. 에뮬은 콘솔 값과 반대로 온다(26 실측) — 뒤집지 않고 RoomService가 SeatEdge 기준선으로 해석
    var telltale: String? = null; private set // 빨간색·켜짐 경고등 종류
    // 내비 앱은 부팅 후 자기 화면(지도)을 한 번 그리기 전까지 SDK 요청을 전부 버린다("navi not ready", 에뮬 실측). 첫 응답이 오면 준비된 것.
    var naviReady = false; private set
    var forcedLocation: LatLng? = null // 디버그 브로드캐스트 LOC로 주입한 위치(에뮬 위치가 고정이라)
    var onNaviReady: (() -> Unit)? = null
    private fun naviAnswered() = ui.post { if (!naviReady) { naviReady = true; Log.i(TAG, "navi ready"); onNaviReady?.invoke() } }

    private fun fail(what: String): (Exception) -> Unit = { e -> Log.w(TAG, "$what FAIL $e") }

    private fun set(f: Signals.() -> Signals) { ui.post { m.update(f) } }

    fun start() {
        runCatching { initGearAndUx() }.onFailure { Log.w(TAG, "gear/ux init FAIL $it") }
        runCatching { initNavi() }.onFailure { Log.w(TAG, "navi init FAIL $it") }
    }

    fun stop() {
        runCatching { navi?.release(); vehicle?.release() }; bgThread.quitSafely()
    }

    // ---- 기어(Pleos) + DO(android.car, 컴파일 스텁이 없어 리플렉션) ----
    private fun initGearAndUx() {
        val v = Vehicle(ctx).also { vehicle = it }
        v.initialize()
        // SDK가 첫 호출 때 kotlin-reflect로 클래스 메타데이터를 디스크에서 읽어 메인을 1.3초 막는다(StrictMode 실측) → 붙이기는 루퍼 있는 백그라운드 스레드에서(맨 Thread면 SDK 응답이 안 온다, 에뮬 실측). 콜백은 전부 ui.post.
        bg.postDelayed({
            runCatching {
                v.getGear().registerGearSelection(object : GearSelectionListener {
                    override fun onSelected(g: VehicleGear?) = set { copy(gear = g.toGear()) }
                    override fun onFailed(e: Exception) { Log.w(TAG, "gear register FAIL $e") }
                })
                v.getGear().getCurrentGearSelectionState({ g -> set { copy(gear = g.toGear()) } }, { Log.w(TAG, "gear get FAIL $it") })
            }.onFailure { Log.w(TAG, "gear attach FAIL $it") }
            attachVehicleExtras(v)
        }, 2000)

        val carCls = Class.forName("android.car.Car")
        car = carCls.getMethod("createCar", Context::class.java).invoke(null, ctx)
        ui.postDelayed({ runCatching { attachUx(carCls) }.onFailure { Log.w(TAG, "uxr attach FAIL ${it.cause ?: it}") } }, 2000)
        ui.postDelayed({ attachSpeed() }, 2000)
    }

    // 깜빡이(CAR_LIGHTS)·조향각·트립 주행시간(CAR_DRIVE)·SoC(CAR_ENERGY)·조수석 착석(CAR_SEATS)·경고등 30초 조회(CAR_SAFETY). 하나가 실패해도 나머지는 붙는다.
    private fun attachVehicleExtras(v: Vehicle) {
        runCatching {
            v.getTurnSignal().registerTurnSignalState(object : TurnSignalStateListener {
                override fun onTurnSignalStateUpdated(s: TurnSignalValue?) { Log.i(TAG, "turn $s"); ui.post { turnOn = s != null && s != TurnSignalValue.OFF } }
                override fun onFailed(e: Exception) { fail("turn")(e) }
            })
        }.onFailure { Log.w(TAG, "turn attach FAIL $it") }
        runCatching {
            v.getSteeringWheel().registerSteeringWheelAngle(object : SteeringWheelAngleListener {
                override fun onSteeringWheelAngleUpdated(a: Float?) {
                    ui.post { if ((a?.let { Math.abs(it) >= BIG_STEER_DEG } ?: false) != (steerDeg?.let { Math.abs(it) >= BIG_STEER_DEG } ?: false)) Log.i(TAG, "steer big=${a?.let { Math.abs(it) >= BIG_STEER_DEG }} $a"); steerDeg = a }
                }
                override fun onFailed(e: Exception) { fail("steer")(e) }
            })
        }.onFailure { Log.w(TAG, "steer attach FAIL $it") }
        runCatching {
            // ponytail: 단위를 분으로 가정(콘솔 usage_time_min과 같은 값이 오는지 에뮬 실측으로 확인). 초로 오면 /60
            val onTrip = { t: Int? -> Log.i(TAG, "tripTime raw=$t"); ui.post { tripMin = t }; Unit }
            // 에뮬 실측: Current는 ERROR_NOT_SUPPORTED_PROPERTY, 콘솔 TRIP_DRIVING_TIME(0x21410450)은 Cumulative 쪽
            val l = object : DrivingTimeListener {
                override fun onDrivingTimeUpdated(t: Int?) { onTrip(t) }
                override fun onFailed(e: Exception) { fail("trip")(e) }
            }
            val o = v.getOdometer()
            o.getTripCurrentDrivingTime({ onTrip(it); o.registerTripCurrentDrivingTime(l) }, { e ->
                Log.w(TAG, "trip current FAIL $e -> cumulative")
                o.getTripCumulativeDrivingTime({ onTrip(it); o.registerTripCumulativeDrivingTime(l) }, { e2 ->
                    Log.w(TAG, "trip cumulative FAIL $e2 -> sinceRefill")
                    o.getTripSinceRefillDrivingTime({ onTrip(it); o.registerTripSinceRefillDrivingTime(l) }, fail("trip sinceRefill"))
                })
            })
        }.onFailure { Log.w(TAG, "trip attach FAIL $it") }
        runCatching {
            val onSoc = { s: Int? -> Log.i(TAG, "soc $s"); ui.post { soc = s }; Unit }
            v.getEvBattery().registerEvBatteryStateOfCharge(object : EvBatteryStateOfChargeListener {
                override fun onEvBatteryStateOfChargeUpdated(s: Int?) { onSoc(s) }
                override fun onFailed(e: Exception) { fail("soc")(e) }
            })
            v.getEvBattery().getEvBatteryStateOfCharge({ onSoc(it) }, fail("soc get"))
        }.onFailure { Log.w(TAG, "soc attach FAIL $it") }
        runCatching {
            val seat = SeatArea(SeatValue.ROW_1_RIGHT)
            val onSeat = { b: Boolean? -> Log.i(TAG, "passengerSeat $b"); ui.post { passengerSeat = b }; Unit }
            v.getSeat().registerSeatOccupancy(seat, object : SeatOccupancyListener {
                override fun onSeatOccupancyUpdated(b: Boolean?) { onSeat(b) }
                override fun onFailed(e: Exception) { fail("seat")(e) }
            })
            v.getSeat().getCurrentSeatOccupancy(seat, { onSeat(it) }, fail("seat get"))
        }.onFailure { Log.w(TAG, "seat attach FAIL $it") }
        // registerTelltales는 PRIVATE_CAR_SAFETY가 필요해 쓰지 않는다. 조회만 30초마다, 실패하면 멈춘다.
        ui.post(object : Runnable {
            override fun run() {
                runCatching {
                    v.getSafety().getTelltalesInfo({ t ->
                        val red = t.telltales.firstOrNull { TelltaleColor.entries.getOrNull(it.color) == TelltaleColor.RED /* id = 순서(javap static init), findById는 internal */ && it.state == TelltaleState.ON }?.type?.name
                        ui.post { if (red != telltale) Log.i(TAG, "telltale red=$red"); telltale = red; ui.postDelayed(this, 30_000) }
                    }, { e -> Log.w(TAG, "telltale get FAIL $e (조회 중단)") })
                }.onFailure { Log.w(TAG, "telltale FAIL $it (조회 중단)") }
            }
        })
    }

    // 속도: Pleos Vehicle SDK엔 없음 -> 공식 FAQ대로 AAOS PERF_VEHICLE_SPEED(m/s). CAR_SPEED 권한이 늦게 허용될 수 있어 실패 시 5초 뒤 재시도.
    private fun attachSpeed() {
        runCatching {
            val pm = (car as Car).getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            pm.registerCallback(object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(v: CarPropertyValue<*>) { val k = (v.value as? Float)?.let { Math.abs(it) * 3.6f }; ui.post { speedKmh = k } }
                override fun onErrorEvent(id: Int, zone: Int) { Log.w("ROOM", "speed onError $id"); ui.post { speedKmh = null } }
            }, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_UI)
            Log.i(TAG, "speed attached")
        }.onFailure { Log.w("ROOM", "speed attach FAIL ${it.cause ?: it}"); if (it is SecurityException) ui.postDelayed({ attachSpeed() }, 5000) }
    }

    private fun attachUx(carCls: Class<*>) {
        val name = carCls.getField("CAR_UX_RESTRICTION_SERVICE").get(null) as String
        val mgr = carCls.getMethod("getCarManager", String::class.java).invoke(car, name) ?: return
        fun isDo(o: Any?) = o?.let { it.javaClass.getMethod("isRequiresDistractionOptimization").invoke(it) as Boolean } ?: false
        fun limits(o: Any?) = o?.let { UxLimits(it.javaClass.getMethod("getActiveRestrictions").invoke(it) as Int, it.javaClass.getMethod("getMaxRestrictedStringLength").invoke(it) as Int) } ?: UxLimits()
        fun apply(o: Any?) { val u = limits(o); ui.post { RoomService.ux = u }; Log.i(TAG, "ux active=0x${u.active.toString(16)} maxStr=${u.maxStr}") }
        val first = mgr.javaClass.getMethod("getCurrentCarUxRestrictions").invoke(mgr)
        val cur = isDo(first)
        set { copy(distractionOptimized = cur) }
        apply(first)
        val iface = Class.forName("android.car.drivingstate.CarUxRestrictionsManager\$OnUxRestrictionsChangedListener")
        val l = Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
            if (method.name == "onUxRestrictionsChanged") {
                val d = isDo(args?.firstOrNull()); set { copy(distractionOptimized = d) }
                apply(args?.firstOrNull())
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
            override fun onDestinationInfo(i: DestinationInfo) {
                naviAnswered()
                val none = i.latitude == 0.0 && i.longitude == 0.0
                Log.i(TAG, "dest poi=${i.poiId} provider=${i.poiProvider}") // O4: 실차에서 poiId로 번호 조회가 되는지 실측용
                val e = if (none) null else etaMinutes(i.remainTime)
                ui.post { etaMin = e; destName = if (none) null else i.name?.takeIf { it.isNotBlank() } }
                set { copy(destination = if (none) null else LatLng(i.latitude, i.longitude)) }
            }
            override fun onCurrentLocationInfo(i: CurrentLocationInfo) { naviAnswered(); set { copy(location = forcedLocation ?: LatLng(i.latitude, i.longitude)) } }
            // 취소/종료만 목적지 해제로 본다. 경유지 추가 직후의 취소->재개 깜빡임은 상태머신 2초 유예가 흡수한다.
            override fun onRouteCancelled() { Log.i(TAG, "onRouteCancelled"); ui.post { etaMin = null; destName = null }; set { copy(destination = null) } }
            override fun onRouteEnded() { ui.post { etaMin = null; destName = null }; set { copy(destination = null) } }
            override fun onDestinationChanged() { n.getDestinationInfo() }
            override fun onWaypointChanged(i: WaypointChangedInfo) { n.getDestinationInfo() }
            // 0 이하 = 제한속도 모름. 목록이 비면 경고/주의구간 해제로 본다.
            override fun onCurrentRoadSpeedLimit(l: Int) { ui.post { limitKmh = l.takeIf { it > 0 } } }
            override fun onCameraAlert(a: List<CameraAlert>) { val o = a.any { it.isOverSpeed || it.isOverSectionAvrSpeed }; ui.post { cameraOverspeed = o } }
            // ponytail: 남은 거리 단위를 m로 가정(문서 미확인), 에뮬 실측으로 조정
            override fun onCautionPointInfo(p: List<CautionPoint>) { val z = p.any { it.remainDistance in 0..300 }; ui.post { cautionZone = z } }
            // C①: 내비 즐겨찾기 '집'·'회사'로 방 판정. 집이 없으면 최근 목적지에서 찾고, 그래도 없으면 기존 설정 유지.
            override fun onBookmarkInfo(l: List<BookmarkInfo>) {
                // 에뮬 실측: 등록 안 된 HOME/OFFICE도 이름 ""·좌표 (0,0)으로 온다 → 빈 칸으로 본다
                val ok = l.filter { it.latitude != 0.0 || it.longitude != 0.0 }
                val h = ok.firstOrNull { it.category == BookmarkType.HOME }
                val o = ok.firstOrNull { it.category == BookmarkType.OFFICE }
                Log.i(TAG, "bookmarks n=${l.size} ${l.map { it.category }} home=${h != null} office=${o != null}")
                ui.post {
                    m.settings = m.settings.copy(home = h?.let { LatLng(it.latitude, it.longitude) } ?: m.settings.home, office = o?.let { LatLng(it.latitude, it.longitude) })
                    if (h != null) RoomService.homeSrc = "내비 즐겨찾기 '집'"
                }
                if (h == null) runCatching { n.getRecentDestinationInfo() }.onFailure { Log.w(TAG, "recent FAIL $it") }
            }
            override fun onRecentDestinationInfo(l: List<RecentDestinationInfo>) {
                val h = homeFromRecent(l.map { it.name to LatLng(it.latitude, it.longitude) })
                Log.i(TAG, "recent n=${l.size} home=${h != null}")
                if (h != null) ui.post { m.settings = m.settings.copy(home = h); RoomService.homeSrc = "최근 목적지 '집'" }
            }
            override fun onBookMarkChanged() { n.getBookmarkInfo() }
            override fun onNavigationStatus(s: NavigationStatus) { Log.i(TAG, "navStatus $s"); if (s == NavigationStatus.ROUTE_DRIVE) n.getDestinationInfo() }
        })
        n.initialize({
            n.getDestinationInfo(); n.getCurrentLocationInfo(); runCatching { n.getBookmarkInfo() }.onFailure { Log.w(TAG, "bookmark FAIL $it") }
            ui.post(object : Runnable { override fun run() { navi?.getCurrentLocationInfo(); navi?.getDestinationInfo(); ui.postDelayed(this, 5000) } })
        }, { Log.w(TAG, "navi initialize FAIL $it") })
    }

    companion object { const val TAG = "ROOMADP" }
}

/** DestinationInfo.remainTime(초, 에뮬 실측 RouteStartInfo duration=1620과 같은 단위) -> 분 반올림. 0 이하 = 모름. */
fun etaMinutes(remainSec: Int): Int? = if (remainSec <= 0) null else Math.round(remainSec / 60.0).toInt().coerceAtLeast(1) // "약 0분" 방지
