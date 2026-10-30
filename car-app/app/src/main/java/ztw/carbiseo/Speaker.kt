package ztw.carbiseo

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.util.Log
import java.util.Locale

/** Pleos TTS SDK로 읽고, 초기화/재생이 실패하면 android.speech.tts로 대체한다. 메인 스레드에서만 호출. */
class Speaker(private val ctx: Context) {
    private var pleos: TextToSpeech? = null
    private var ready = false
    private var lastText = ""
    private var fallback: android.speech.tts.TextToSpeech? = null
    private var fallbackReady = false

    fun start() {
        runCatching {
            // SERVER 모드는 Pleos 콘솔에서 TextToSpeech가 '준비 중'(신청 불가, 10-29 확인)이라 401 → ON_DEVICE만 쓴다
            val t = TextToSpeech(ctx, Mode.ON_DEVICE).also { pleos = it }
            // SDK는 initialize 성공 뒤에만 setLocale/addEventListener를 받는다(에뮬 실측: 먼저 부르면 lateinit 예외)
            val events = object : EventListener {
                override fun onStart() {}
                override fun onReady() {}
                override fun onDone() { Log.i("ROOM", "TTS pleos done"); afterSpeech() }
                override fun onStop() {}
                override fun onUpdatedRms(rms: Double) {}
                override fun onError(e: ErrorType) = useFallback("pleos onError $e", replay = true)
            }
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    runCatching {
                        t.setLocale(Locale.KOREA); t.addEventListener(events) // ko_KR(엔진은 "ko"만이면 거절, STT 실측과 같은 원인 추정)
                        ready = true; Log.i("ROOM", "TTS pleos ready (on-device)")
                    }.onFailure { useFallback("pleos setup $it", replay = false) }
                }
                override fun onFailure(e: ErrorType) = useFallback("pleos init $e", replay = false)
            })
        }.onFailure { useFallback("pleos start $it", replay = false) }
    }

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private var then: (() -> Unit)? = null
    private var gen = 0 // 옛 문장의 어림 타이머가 새 문장의 then을 부르지 않게
    /** 다 읽은 뒤 한 번(음성 응답 듣기 시작). */
    private fun afterSpeech() = ui.post { then?.let { then = null; it() } }

    /** then: 다 읽은 뒤 할 일. 엔진 완료 콜백이 안 오면 글자 수로 어림한 시간 뒤. */
    fun speak(text: String, then: (() -> Unit)? = null) {
        this.then = then; val g = ++gen
        if (then != null) ui.postDelayed({ if (g == gen) afterSpeech() }, 1500L + text.length * 200L) // ponytail: 어림값, 콜백이 늘 오면 지워도 됨
        lastText = text
        Log.i("ROOM", "TTS speak: $text")
        if (ready) runCatching { pleos?.speak(text, false) }.onFailure { useFallback("pleos speak $it", replay = false) }
        else fallbackSpeak(text)
    }

    private fun useFallback(cause: String, replay: Boolean) {
        Log.w("ROOM", "TTS fallback: $cause")
        ready = false
        if (replay && lastText.isNotEmpty()) fallbackSpeak(lastText)
    }

    private fun fallbackSpeak(text: String) {
        if (fallback == null) {
            fallback = android.speech.tts.TextToSpeech(ctx) { s ->
                fallbackReady = s == android.speech.tts.TextToSpeech.SUCCESS
                if (fallbackReady) {
                    fallback?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) { afterSpeech() }
                        @Deprecated("") override fun onError(id: String?) { afterSpeech() }
                    })
                    fallback?.language = Locale.KOREAN; fallback?.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "inbox") }
                else Log.w("ROOM", "TTS fallback: android TTS init $s")
            }
        } else if (fallbackReady) fallback?.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "inbox")
    }

    fun stop() { runCatching { pleos?.release() }; fallback?.shutdown() }
}
