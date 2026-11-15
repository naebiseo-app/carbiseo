package ztw.carbiseo

import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import java.io.File
import kotlin.math.sqrt

// 사용자가 고른 배경 이미지의 정책(bgPolicy)·이름 입력 검증(validName)·디코드(loadBg)만 맡는다.
// 배경을 화면에 그리는 일은 Screens.kt, 이미지 파일을 저장하는 일도 Screens.kt 쪽이다.

data class BgPolicy(val animate: Boolean, val scrimAlpha: Float)

/** 움직이는 배경은 세워 둔 동안만. 운전 중(판정 전 포함)엔 정지 + 짙은 스크림. */
fun bgPolicy(room: String): BgPolicy = if (room == Room.PARK.name) BgPolicy(true, 0.25f) else BgPolicy(false, 0.6f)

/** 호칭·비서 이름: 1~10자, 글자·숫자·공백만. */
fun validName(s: String) = s.isNotBlank() && s.matches(Regex("""[\p{L}\p{N} ]{1,10}"""))

/** 화면 크기로 다운샘플해 디코드. GIF/애니 WebP는 AnimatedImageDrawable(시작 전 = 첫 프레임). 실패면 null. */
fun loadBg(f: File, maxW: Int, maxH: Int): Drawable? = runCatching {
    ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) { d, info, _ ->
        val w = info.size.width; val h = info.size.height
        // 화면을 덮을 만큼(cover)만, 그래도 화면 2배 픽셀은 넘지 않게
        val s = minOf(1f, maxOf(maxW.toFloat() / w, maxH.toFloat() / h), sqrt(2f * maxW * maxH / (w.toFloat() * h)))
        if (s < 1f) d.setTargetSize((w * s).toInt().coerceAtLeast(1), (h * s).toInt().coerceAtLeast(1))
    }
}.onFailure { Log.w("ROOM", "bg decode fail ${f.name}: $it") }.getOrNull()
