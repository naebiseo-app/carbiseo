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
            val t = TextToSpeech(ctx, Mode.ON_DEVICE).also { pleos = it }
            t.setLocale(Locale.KOREAN)
            t.addEventListener(object : EventListener {
                override fun onStart() {}
                override fun onReady() {}
                override fun onDone() {}
                override fun onStop() {}
                override fun onUpdatedRms(rms: Double) {}
                override fun onError(e: ErrorType) = useFallback("pleos onError $e", replay = true)
            })
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() { ready = true; Log.i("ROOM", "TTS pleos ready") }
                override fun onFailure(e: ErrorType) = useFallback("pleos init $e", replay = false)
            })
        }.onFailure { useFallback("pleos start $it", replay = false) }
    }

    fun speak(text: String) {
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
                if (fallbackReady) { fallback?.language = Locale.KOREAN; fallback?.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "inbox") }
                else Log.w("ROOM", "TTS fallback: android TTS init $s")
            }
        } else if (fallbackReady) fallback?.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "inbox")
    }

    fun stop() { runCatching { pleos?.release() }; fallback?.shutdown() }
}
