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
    // 낭독은 한 줄로 이어서: 말하는 중에 다음 문장이 오면 끊지 않고 끝난 뒤 읽는다(방 안내 + 집 문턱 안내가 0.13초 간격으로 겹치던 것).
    private class Item(val text: String, val then: (() -> Unit)?)
    private val queue = ArrayDeque<Item>()
    private var playing = false
    val busy get() = playing // 말하는 중(이름 호출이 자기 음성을 듣지 않게)
    var onBusy: (() -> Unit)? = null // 말하기 시작·끝날 때(끝난 뒤 할 일을 한 다음)
    private var then: (() -> Unit)? = null    // 지금 읽는 문장이 끝난 뒤 할 일(음성 응답 듣기 시작)
    private var carried: (() -> Unit)? = null // 뒤에 더 읽을 게 있으면 듣기는 마지막 문장 뒤로 미룬다
    private var gen = 0 // 옛 문장의 어림 타이머가 새 문장을 끝내지 않게

    /** 다 읽었다(엔진 완료 콜백 또는 어림 타이머): 다음 문장을 읽고, 없으면 미뤄 둔 '듣기'를 시작. */
    private fun afterSpeech() = ui.post {
        if (!playing) return@post
        val t = then ?: carried; then = null; carried = null
        if (queue.isEmpty()) { playing = false; t?.invoke(); onBusy?.invoke() } else { carried = t; playNext() }
    }

    /** then: 다 읽은 뒤 할 일. 엔진 완료 콜백이 안 오면 글자 수로 어림한 시간 뒤. */
    fun speak(said: String, then: (() -> Unit)? = null) {
        queue.addLast(Item(spellDigits(said), then)) // 모든 낭독이 지나는 한 곳: 번호는 한 자리씩
        if (!playing) playNext()
    }

    private fun playNext() {
        val item = queue.removeFirstOrNull() ?: run { playing = false; return }
        playing = true; onBusy?.invoke(); then = item.then; val g = ++gen
        ui.postDelayed({ if (g == gen) afterSpeech() }, 1500L + item.text.length * 200L) // ponytail: 어림값(완료 콜백이 안 올 때만 쓰임), 콜백이 늘 오면 지워도 됨
        lastText = item.text
        Log.i("ROOM", "TTS speak: ${item.text}")
        if (ready) runCatching { pleos?.speak(item.text, false) }.onFailure { useFallback("pleos speak $it", replay = false) }
        else fallbackSpeak(item.text)
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

    fun stop() { queue.clear(); runCatching { pleos?.release() }; fallback?.shutdown() }
}
