package ztw.carbiseo

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.util.concurrent.LinkedBlockingQueue

/**
 * 이름 호출 문장 나누기(순수 함수). 공백을 뺀 text에 이름이 있으면 이름 뒤 문장(없으면 ""), 이름이 없으면 null.
 * 이름의 첫 글자가 '내'·'네'면 둘 다 같은 것으로 본다("네비서야" → "야"). 기본 이름은 인식기가 잘못 적는 꼴도 받는다.
 */
private val DEFAULT_NAMES = setOf("내비서", "네비서") // 인식기가 잘못 받아 적는 꼴을 함께 받는 이름

fun wakeSplit(text: String, name: String): String? {
    val t = text.filterNot { it.isWhitespace() }
    val n = name.filterNot { it.isWhitespace() }
    if (n.isEmpty()) return null
    val swap = when (n[0]) { '내' -> '네'; '네' -> '내'; else -> null }
    val names = listOfNotNull(n, swap?.let { it + n.substring(1) }) + if (n in DEFAULT_NAMES) listOf("내비사", "네비사", "내디서", "내리서", "앱이서") else emptyList()
    val hit = names.map { t.indexOf(it) to it.length }.filter { it.first >= 0 }.minByOrNull { it.first }
        ?: "넷이서".takeIf { n in DEFAULT_NAMES && t.startsWith(it) }?.let { 0 to it.length } // 흔한 낱말이라 문장 맨 앞만 (에뮬 실측 오인식)
        ?: return null
    var left = hit.first + hit.second // 공백을 뺀 글자 수로 센 이름 끝을 원문 위치로 옮긴다(말동무 AI에 띄어쓰기를 살려 보낸다)
    var i = 0
    while (left > 0) { if (!text[i].isWhitespace()) left--; i++ }
    val rest = text.substring(i).trim()
    return if (rest.trimEnd('.', '?', '!') in setOf("야", "아")) "" else rest // "내비서야"는 이름만 부른 것
}

/**
 * 이름 호출 대기. 마이크 → 기기 안 sherpa-onnx 스트리밍 인식(assets/sherpa-ko) → 끝점마다 wakeSplit.
 * 이름이 없는 문장은 끝점에서 reset으로 버린다: Log·파일·네트워크로 내보내지 않는다(이 클래스는 네트워크 코드가 없다).
 * onHeard는 전용 스레드에서 불린다(받는 쪽이 메인으로 넘길 것). onHeard("") = 이름만 들음, 8초 안의 다음 문장이 onHeard(문장).
 */
class Wake(private val ctx: Context, @Volatile var name: String, private val onHeard: (String) -> Unit) {
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var resumed = false  // 쉬었다 돌아오면 스레드가 스트림을 비운다(쉬는 동안 들어온 소리 조각이 이어 붙지 않게)
    @Volatile private var awaitUntil = 0L  // 이름만 들은 뒤 다음 문장을 기다리는 마감(elapsedRealtime). 0 = 기다리지 않음
    @Volatile var dead = false; private set // 모델·마이크를 못 열었다. 다시 만들어야 한다
    private var thread: Thread? = null
    private val fed = LinkedBlockingQueue<ByteArray>() // 디버그: 마이크 대신 넣는 PCM

    fun start() {
        if (running) return
        running = true; dead = false
        thread = Thread(::loop, "wake").also { it.isDaemon = true; it.start() }
    }

    fun stop() { running = false; thread?.interrupt(); thread = null }
    fun pause() { paused = true }
    fun resume() {
        if (!paused) return
        if (awaitUntil != 0L) awaitUntil = SystemClock.elapsedRealtime() + AWAIT_MS // "네" 말하는 동안은 기다림에 안 센다
        resumed = true; paused = false
    }

    /** 디버그 빌드 전용 시험: 마이크 대신 PCM(16kHz·16bit·mono)을 넣는다. 끝점이 나도록 끝에 3초 무음을 붙인다. */
    fun feedPcm(bytes: ByteArray) {
        bytes.asList().chunked(CHUNK * 2).forEach { fed.put(it.toByteArray()) }
        repeat(30) { fed.put(ByteArray(CHUNK * 2)) }
    }

    @SuppressLint("MissingPermission") // 호출 전에 RoomService가 RECORD_AUDIO를 확인한다
    private fun loop() {
        var rec: AudioRecord? = null
        var asr: OnlineRecognizer? = null
        try {
            asr = OnlineRecognizer(ctx.assets, config())
            val stream = asr.createStream()
            val buf = ShortArray(CHUNK)
            rec = openMic()
            var micOn = false
            while (running) {
                if (paused) { if (micOn) { runCatching { rec?.stop() }; micOn = false }; fed.clear(); Thread.sleep(50); continue }
                if (resumed) { resumed = false; asr.reset(stream) }
                val pcm = fed.poll()
                val f: FloatArray = when {
                    pcm != null -> FloatArray(pcm.size / 2) { ((pcm[2 * it + 1].toInt() shl 8) or (pcm[2 * it].toInt() and 0xFF)).toShort() / 32768f }
                    rec != null -> {
                        if (!micOn) { rec.startRecording(); micOn = true }
                        val n = rec.read(buf, 0, buf.size)
                        if (n <= 0) { Thread.sleep(20); continue }
                        FloatArray(n) { buf[it] / 32768f }
                    }
                    else -> { Thread.sleep(50); continue }
                }
                stream.acceptWaveform(f, SAMPLE_RATE)
                while (asr.isReady(stream)) asr.decode(stream)
                if (asr.isEndpoint(stream)) {
                    val text = asr.getResult(stream).text
                    asr.reset(stream) // 이름이 없든 있든 이 문장은 여기서 비운다
                    handle(text)
                }
            }
            stream.release()
        } catch (e: InterruptedException) {
            // stop()
        } catch (e: Throwable) {
            dead = true; Log.w("ROOM", "wake dead: ${e.javaClass.simpleName}") // 메시지에 소리 내용은 없다
        } finally {
            running = false
            runCatching { rec?.stop() }; runCatching { rec?.release() }; runCatching { asr?.release() }
        }
    }

    private fun handle(raw: String) {
        if (raw.isBlank()) return // 빈 끝점(조용함): 기다림은 그대로
        val now = SystemClock.elapsedRealtime()
        val waiting = awaitUntil != 0L && now <= awaitUntil
        awaitUntil = 0L
        when (val rest = wakeSplit(raw, name)) {
            null -> if (waiting) onHeard(raw.trim()) // 이름만 부른 뒤 8초 안의 다음 문장은 그대로 넘긴다
            "" -> { awaitUntil = now + AWAIT_MS; onHeard("") }
            else -> onHeard(rest)
        }
        // 이름 없는 문장(기다리는 중이 아닐 때)은 아무 데도 안 남기고 끝
    }

    @SuppressLint("MissingPermission")
    private fun openMic(): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        return runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, SAMPLE_RATE * 2))
        }.getOrNull()?.takeIf { it.state == AudioRecord.STATE_INITIALIZED }
            ?: run { Log.w("ROOM", "wake mic not available"); null } // 마이크 없이도(디버그 feedPcm) 돌 수 있다
    }

    private fun config() = OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(encoder = "$DIR/$ENCODER", decoder = "$DIR/$DECODER", joiner = "$DIR/$JOINER"),
            tokens = "$DIR/$TOKENS", numThreads = 1, debug = false,
        ),
        endpointConfig = EndpointConfig(), // 기본 끝점 규칙(말 뒤 무음 1.4~2.4초)
        enableEndpoint = true, decodingMethod = "greedy_search",
    )

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val CHUNK = 1600 // 0.1초
        private const val AWAIT_MS = 8000L
        private const val DIR = "sherpa-ko"
        private const val ENCODER = "encoder-epoch-99-avg-1.int8.onnx"
        private const val DECODER = "decoder-epoch-99-avg-1.int8.onnx"
        private const val JOINER = "joiner-epoch-99-avg-1.int8.onnx"
        private const val TOKENS = "tokens.txt"

        /** 모델 파일 네 개가 앱에 들어 있나. 없으면(모델 없이 빌드) 스위치를 켜도 시작하지 않는다. */
        fun hasModel(ctx: Context): Boolean = runCatching { ctx.assets.list(DIR).orEmpty().toSet().containsAll(listOf(ENCODER, DECODER, JOINER, TOKENS)) }.getOrDefault(false)
    }
}
