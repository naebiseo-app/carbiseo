package ztw.carbiseo

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Locale

// 음성 한 마디 듣기(listen)와 그 결과(onHeard)만 맡는다. 들은 문장을 어떤 의도로 풀지는 Drive.kt(carIntent)·Notif.kt(voiceCmd),
// 이름 호출(항상 켜 둔 인식)은 Wake.kt다. 먼저 볼 곳: start(엔진 고르기) → listen → done.

/**
 * 한 마디 듣기. Pleos SpeechToText(ON_DEVICE)만 쓴다 → 안 되면 path="none"(화면 버튼).
 * 다른 음성 인식기로 넘어가지 않는다: 차량 마이크 음성이 기기 밖 인식 서비스로 나갈 수 있는 길을 두지 않기 위해서다(공모전 공지 "음성 인식은 SpeechToText SDK").
 * 결과는 onHeard(문장 또는 null = 못 들음). 메인 스레드에서만 호출.
 */
class Voice(private val ctx: Context, private val onHeard: (String?) -> Unit) {
    var path = "none"; private set // "pleos" | "none". none이면 화면 버튼으로만 대답을 받는다
    private var pleos: SpeechToText? = null
    private var waiting = false
    @Volatile private var partial = "" // 엔진이 최종 결과 없이 끝내는 경우가 있어(에뮬 실측) 중간 결과를 쓴다
    private fun heardSoFar() = partial.trim().ifEmpty { null }

    // SDK 콜백은 바인더 스레드에서 올 수 있다 → 메인으로. waiting이 켜진 동안 첫 결과 하나만 전달한다
    private fun done(text: String?) { ui.post { if (waiting) { waiting = false; Log.i("ROOM", "voice heard($path): ${text != null}"); onHeard(text) } } }

    fun start() {
        runCatching {
            val s = SpeechToText(ctx, Mode.ON_DEVICE).also { pleos = it }
            s.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    runCatching {
                        s.setLocale(Locale.KOREA) // "ko"만 주면 엔진이 unsupported locale(에뮬 실측), ko_KR이어야 한다
                        s.addListener(object : ResultListener {
                            override fun onUpdated(text: String, isFinal: Boolean) { partial = text; if (isFinal) done(text) }
                            override fun onUpdatedEpdData(on: Long, off: Long) {}
                            override fun onStartedRecognition() { Log.i("ROOM", "voice pleos started") }
                            // 최종 결과가 조금 늦게 올 수 있어 1.5초 기다린 뒤에도 안 왔으면 중간 결과(없으면 못 들음)로 끝낸다
                            override fun onEndedRecognition() { val g = gen; ui.postDelayed({ if (g == gen) done(heardSoFar()) }, 1500) }
                            override fun onError(e: ErrorType) { Log.w("ROOM", "voice pleos error $e"); done(null) }
                            override fun onReady() { Log.i("ROOM", "voice pleos ready") }
                            override fun onUpdatedRms(rms: Double) {}
                        })
                        path = "pleos"; Log.i("ROOM", "voice path pleos (on-device)")
                    }.onFailure { noVoice("pleos setup $it") }
                }
                override fun onFailure(e: ErrorType) = noVoice("pleos init $e")
            })
        }.onFailure { noVoice("pleos start $it") }
    }

    private val ui = Handler(Looper.getMainLooper())

    private fun noVoice(cause: String) { Log.w("ROOM", "voice path none: $cause"); pleos = null; path = "none" }

    private var gen = 0
    fun listen() {
        waiting = true; partial = ""; val g = ++gen
        // 엔진이 말소리를 못 잡으면 계속 듣는다(에뮬 실측) → 10초 뒤 그때까지 들은 것(없으면 못 들음)으로 끝낸다
        ui.postDelayed({ if (g == gen && waiting) { runCatching { pleos?.stop() }; done(heardSoFar()) } }, LISTEN_MS)
        when (path) {
            "pleos" -> runCatching { pleos!!.request(); armed?.let { b -> armed = null; feed(b) } }.onFailure { Log.w("ROOM", "voice pleos request $it"); done(null) }
            else -> done(null)
        }
    }

    /** 디버그 실측용: 다음 듣기 때 마이크 대신 이 PCM(16kHz·16bit·mono)을 엔진에 넣는다. 듣는 중에 넣으면 엔진 오류라(실측) 다음 듣기를 기다린다. */
    var armed: ByteArray? = null
    // 마이크와 같은 속도(16kHz·16bit·mono = 0.1초에 3200B)로 넣는다. 에뮬 실측: 인식은 되지만 불안정(정답·오인식·엔진 오류가 섞임, 한꺼번에 넣으면 엔진 서비스가 죽기도)
    private fun feed(b: ByteArray) = Thread { runCatching { b.toList().chunked(3200).forEach { pleos?.sendAudio(it.toByteArray()); Thread.sleep(100) }; pleos?.completeAudioSend() } }.start()

    /** 디버그·화면 버튼: 인식된 것처럼 넣는다. */
    fun inject(text: String) { waiting = true; runCatching { pleos?.stop() }; done(text) }

    companion object { const val LISTEN_MS = 10_000L }

    fun stop() { runCatching { pleos?.release() } }
}
