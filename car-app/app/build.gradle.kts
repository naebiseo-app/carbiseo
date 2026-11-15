plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
// 저장소 루트 .env 의 CAR_TOKEN(없으면 빈 문자열). 값은 소스에 두지 않고 BuildConfig로만 주입한다.
// 심사용 공유 시연 토큰이다. 공개 APK에서 꺼낼 수 있으므로 이 토큰으로 읽히는 서버 자료는 가명 시연 카드뿐이게 운영한다. 공개 기간 뒤 교체하고, 상용에서는 기기별 발급 토큰으로 바꾼다(README '알려진 제약').
val envLines = file("../../.env").takeIf { it.exists() }?.readLines().orEmpty()
fun env(k: String) = envLines.firstOrNull { it.startsWith("$k=") }?.substringAfter("=")?.trim()?.trim { it.code == 34 || it.code == 39 } ?: ""
val carToken = env("CAR_TOKEN")
// release 서명: 저장소 밖 ../keys/keystore.properties(README "차 앱 빌드" 절). 없으면 서명 없이 빌드된다.
val keyDir = file("../../../keys")
val keyProps = File(keyDir, "keystore.properties").takeIf { it.exists() }?.readLines().orEmpty().filter { "=" in it }.associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
android {
    namespace = "ztw.carbiseo"
    compileSdk = 34
    useLibrary("android.car") // CarPropertyManager(속도). 런타임은 매니페스트 uses-library
    defaultConfig { applicationId = "ztw.carbiseo"; minSdk = 28; targetSdk = 34; versionCode = 1; versionName = "0.1"
        buildConfigField("String", "SERVER_URL", "\"https://carbiseo-api.wondi81.workers.dev\"")
        buildConfigField("String", "CAR_TOKEN", "\"$carToken\"")
        ndk { abiFilters += listOf("x86_64", "arm64-v8a") } // sherpa-onnx 네이티브 라이브러리: 에뮬·차(arm64)만
    }
    androidResources { noCompress += "onnx" } // 이름 호출 모델(assets/sherpa-ko, git 제외)을 압축 없이 담아 빨리 연다
    if (keyProps.isNotEmpty()) signingConfigs.create("release") {
        storeFile = File(keyDir, keyProps.getValue("storeFile"))
        storePassword = keyProps["storePassword"]; keyAlias = keyProps["keyAlias"]; keyPassword = keyProps["keyPassword"]
    }
    // minify 끔: Pleos SDK가 kotlin-reflect로 클래스 메타데이터를 읽어서 R8이 깨뜨릴 수 있다.
    buildTypes { release { isMinifyEnabled = false; signingConfigs.findByName("release")?.let { signingConfig = it } } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
}
dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation("ai.pleos.playground:SpeechToText:3.3.1") // 알림 음성 응답(35)
    implementation(files("libs/sherpa-onnx-2.0.1.aar")) // 이름 호출: 기기 안 스트리밍 음성 인식(k2-fsa/sherpa-onnx v2.0.1)
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    testImplementation("junit:junit:4.13.2")
}
