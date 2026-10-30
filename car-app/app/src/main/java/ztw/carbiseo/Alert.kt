package ztw.carbiseo

import java.time.Clock

enum class Alert { NONE, CAUTION, OVERSPEED }

const val OVERSPEED_MARGIN_KMH = 3

/** 순간 판정. null인 값은 그 조건을 무시한다. 우선순위 OVERSPEED > CAUTION > NONE. */
fun alertFor(speedKmh: Float?, limitKmh: Int?, cameraOverspeed: Boolean, cautionZone: Boolean): Alert = when {
    cameraOverspeed || (speedKmh != null && limitKmh != null && speedKmh > limitKmh + OVERSPEED_MARGIN_KMH) -> Alert.OVERSPEED
    cautionZone -> Alert.CAUTION
    else -> Alert.NONE
}

/** OVERSPEED 해제 히스테리시스: 제한속도 이하(또는 판단 불가)가 releaseMs 동안 이어져야 내려간다. */
class AlertLatch(private val clock: Clock, private val releaseMs: Long = 2000) {
    var alert = Alert.NONE
        private set
    private var calmSince: Long? = null

    fun update(speedKmh: Float?, limitKmh: Int?, cameraOverspeed: Boolean, cautionZone: Boolean): Alert {
        val raw = alertFor(speedKmh, limitKmh, cameraOverspeed, cautionZone)
        if (raw == Alert.OVERSPEED || alert != Alert.OVERSPEED) { calmSince = null; alert = raw; return alert }
        val calm = !cameraOverspeed && (speedKmh == null || limitKmh == null || speedKmh <= limitKmh)
        if (!calm) { calmSince = null; return alert }
        val now = clock.millis()
        val since = calmSince ?: now.also { calmSince = it }
        if (now - since >= releaseMs) { calmSince = null; alert = raw }
        return alert
    }
}

/** 조용히 할 때(경고·깜빡이·후진·큰 조향) 새 TTS를 보류한다. 전화는 항목을 쌓아 두었다가 풀릴 때 한 문장(급함·건수 유지), 그 밖의 문장은 최신 1개. */
class SpeechHold {
    private val items = mutableListOf<InboxItem>()
    private var text: String? = null
    /** 지금 읽을 문장(경고 중이면 null로 보류). */
    fun offer(text: String, quiet: Boolean): String? = if (!quiet) text else { this.text = text; null }
    fun offerItems(fresh: List<InboxItem>, room: Room?, quiet: Boolean): String? =
        if (!quiet) speechFor(fresh, room) else { items += fresh; null }
    /** quiet가 바뀔 때 호출. 풀렸으면 보류분을 지금 방 기준 한 문장으로 내주고 비운다. */
    fun release(quiet: Boolean, room: Room?): String? {
        if (quiet) return null
        val s = listOfNotNull(speechFor(items, room), text).joinToString(" ").ifEmpty { null }
        clear(); return s
    }
    fun clear() { items.clear(); text = null }
}
