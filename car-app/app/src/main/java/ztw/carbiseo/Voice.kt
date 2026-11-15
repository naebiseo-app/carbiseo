package ztw.carbiseo

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

// 음성 한 마디 듣기(listen)와 그 결과(onHeard)만 맡는다. 들은 문장을 어떤 의도로 풀지는 Drive.kt(carIntent)·Notif.kt(voiceCmd),
// 이름 호출(항상 켜 둔 인식)은 Wake.kt다. 먼저 볼 곳: start(엔진 고르기) → listen → done.

/**
 * 한 마디 듣기. Pleos STT(ON_DEVICE) → 안 되면 Android SpeechRecognizer → 둘 다 안 되면 path="none"(화면 버튼).
 * 결과는 onHeard(문장 또는 null = 못 들음). 메인 스레드에서만 호출.
 */
class Voice(private val ctx: Context, private val onHeard: (String?) -> Unit) {
    var path = "none"; private set // "pleos" | "android" | "none". none이면 화면 버튼으로만 대답을 받는다
    private var pleos: SpeechToText? = null
    private var android: SpeechRecognizer? = null
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
                    }.onFailure { useAndroid("pleos setup $it") }
                }
                override fun onFailure(e: ErrorType) = useAndroid("pleos init $e")
            })
        }.onFailure { useAndroid("pleos start $it") }
    }

    private val ui = Handler(Looper.getMainLooper())

    private fun useAndroid(cause: String) {
        Log.w("ROOM", "voice fallback: $cause")
        pleos = null
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { path = "none"; Log.w("ROOM", "voice path none"); return }
        android = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle) = done(b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
                override fun onError(e: Int) { Log.w("ROOM", "voice android error $e"); done(null) }
                override fun onReadyForSpeech(p: Bundle?) { Log.i("ROOM", "voice android ready") }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(b: Bundle?) {}
                override fun onEvent(t: Int, b: Bundle?) {}
            })
        }
        path = "android"; Log.i("ROOM", "voice path android")
    }

    private var gen = 0
    fun listen() {
        waiting = true; partial = ""; val g = ++gen
        // 엔진이 말소리를 못 잡으면 계속 듣는다(에뮬 실측) → 10초 뒤 그때까지 들은 것(없으면 못 들음)으로 끝낸다
        ui.postDelayed({ if (g == gen && waiting) { runCatching { pleos?.stop(); android?.cancel() }; done(heardSoFar()) } }, LISTEN_MS)
        when (path) {
            "pleos" -> runCatching { pleos!!.request(); armed?.let { b -> armed = null; feed(b) } }.onFailure { Log.w("ROOM", "voice pleos request $it"); done(null) }
            "android" -> android!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR"))
            else -> done(null)
        }
    }

    /** 디버그 실측용: 다음 듣기 때 마이크 대신 이 PCM(16kHz·16bit·mono)을 엔진에 넣는다. 듣는 중에 넣으면 엔진 오류라(실측) 다음 듣기를 기다린다. */
    var armed: ByteArray? = null
    // 마이크와 같은 속도(16kHz·16bit·mono = 0.1초에 3200B)로 넣는다. 에뮬 실측: 인식은 되지만 불안정(정답·오인식·엔진 오류가 섞임, 한꺼번에 넣으면 엔진 서비스가 죽기도)
    private fun feed(b: ByteArray) = Thread { runCatching { b.toList().chunked(3200).forEach { pleos?.sendAudio(it.toByteArray()); Thread.sleep(100) }; pleos?.completeAudioSend() } }.start()

    /** 디버그·화면 버튼: 인식된 것처럼 넣는다. */
    fun inject(text: String) { waiting = true; runCatching { pleos?.stop(); android?.cancel() }; done(text) }

    companion object { const val LISTEN_MS = 10_000L }

    fun stop() { runCatching { pleos?.release() }; android?.destroy() }
}
