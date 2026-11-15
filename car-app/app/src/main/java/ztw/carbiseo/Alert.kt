package ztw.carbiseo

import java.time.Clock

// 과속·주의구간 판정(alertFor)과 그 해제 지연(AlertLatch), 경고·깜빡이 중 낭독 보류(SpeechHold), 낭독 문장 잇기(sentences).
// 속도·제한속도·카메라 신호를 받아 넘기는 곳은 CarAdapters.kt, 보류분을 실제로 읽는 곳은 RoomService.kt다.

enum class Alert { NONE, CAUTION, OVERSPEED }

const val OVERSPEED_MARGIN_KMH = 3

/** 순간 판정. null인 값은 그 조건을 무시한다. 우선순위 OVERSPEED > CAUTION > NONE. */
fun alertFor(speedKmh: Float?, limitKmh: Int?, cameraOverspeed: Boolean, cautionZone: Boolean): Alert = when {
    cameraOverspeed || (speedKmh != null && limitKmh != null && speedKmh > limitKmh + OVERSPEED_MARGIN_KMH) -> Alert.OVERSPEED
    cautionZone -> Alert.CAUTION
    else -> Alert.NONE
}

/**
 * OVERSPEED 해제 히스테리시스: 제한속도 이하(또는 판단 불가)가 releaseMs 동안 이어져야 내려간다.
 * 제한속도를 넘었지만 여유(OVERSPEED_MARGIN_KMH) 안이면 과속은 아니되 해제도 시작하지 않는다(테스트 참고).
 */
class AlertLatch(private val clock: Clock, private val releaseMs: Long = 2000) {
    var alert = Alert.NONE
        private set
    private var calmSince: Long? = null

    fun update(speedKmh: Float?, limitKmh: Int?, cameraOverspeed: Boolean, cautionZone: Boolean): Alert {
        val raw = alertFor(speedKmh, limitKmh, cameraOverspeed, cautionZone)
        // 과속이 아니었거나 지금 막 과속이면 바로 따라간다. 지연은 "과속 → 그 밖"으로 내려갈 때만 건다.
        if (raw == Alert.OVERSPEED || alert != Alert.OVERSPEED) { calmSince = null; alert = raw; return alert }
        val calm = !cameraOverspeed && (speedKmh == null || limitKmh == null || speedKmh <= limitKmh)
        if (!calm) { calmSince = null; return alert }
        val now = clock.millis()
        val since = calmSince ?: now.also { calmSince = it }
        if (now - since >= releaseMs) { calmSince = null; alert = raw }
        return alert
    }
}

/** 낭독 문장 이어붙이기는 여기서만: 앞뒤 공백을 떼고, 끝에 마침표(없으면)를 붙여 공백 하나로 잇는다(TTS가 문장 사이를 쉬게 한다). */
fun sentences(vararg parts: String?): String =
    parts.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.joinToString(" ") { if (it.last() in ".?!…") it else "$it." }

/** 조용히 할 때(경고·깜빡이·후진·큰 조향) 새 TTS를 보류한다. 전화는 항목을 쌓아 두었다가 풀릴 때 한 문장(급함·건수 유지), 그 밖의 문장은 최신 1개. */
class SpeechHold(private val mode: () -> String = { "ai" }) {
    private val items = mutableListOf<InboxItem>()
    private var text: String? = null
    /** 지금 읽을 문장(경고 중이면 null로 보류하고 최신 1개만 기억). */
    fun offer(text: String, quiet: Boolean): String? = if (!quiet) text else { this.text = text; null }
    fun offerItems(fresh: List<InboxItem>, room: Room?, quiet: Boolean): String? =
        if (!quiet) speechFor(fresh, room, mode()) else { items += fresh; null }
    /** quiet가 바뀔 때 호출. 풀렸으면 보류분을 지금 방 기준 한 문장으로 내주고 비운다. */
    fun release(quiet: Boolean, room: Room?): String? {
        if (quiet) return null
        val s = sentences(speechFor(items, room, mode()), text).ifEmpty { null }
        clear(); return s
    }
    fun clear() { items.clear(); text = null }
}
