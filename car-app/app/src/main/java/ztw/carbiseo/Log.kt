package ztw.carbiseo

/**
 * android.util.Log 감싸개. 릴리스 빌드에서는 아무것도 남기지 않는다(Pleos 앱 보안 가이드: 출시 전 민감 로그 비활성화).
 * 디버그 빌드만 logcat에 쓴다. 앱 전체가 android.util.Log 대신 이것만 부른다.
 */
object Log {
    fun i(tag: String, msg: String): Int = if (BuildConfig.DEBUG) android.util.Log.i(tag, msg) else 0
    fun w(tag: String, msg: String): Int = if (BuildConfig.DEBUG) android.util.Log.w(tag, msg) else 0
}
