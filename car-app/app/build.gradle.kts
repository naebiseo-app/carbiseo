plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
// 현대차공모전/.env 의 CAR_TOKEN(없으면 빈 문자열). 값은 소스에 두지 않고 BuildConfig로만 주입한다.
val envLines = file("../../.env").takeIf { it.exists() }?.readLines().orEmpty()
fun env(k: String) = envLines.firstOrNull { it.startsWith("$k=") }?.substringAfter("=")?.trim()?.trim { it.code == 34 || it.code == 39 } ?: ""
val carToken = env("CAR_TOKEN")
// release 서명: 저장소 밖 현대차공모전_keys/keystore.properties. 없으면 서명 없이 빌드된다.
val keyDir = file("../../../현대차공모전_keys")
val keyProps = File(keyDir, "keystore.properties").takeIf { it.exists() }?.readLines().orEmpty().filter { "=" in it }.associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
android {
    namespace = "ztw.carbiseo"
    compileSdk = 34
    useLibrary("android.car") // CarPropertyManager(속도). 런타임은 매니페스트 uses-library
    defaultConfig { applicationId = "ztw.carbiseo"; minSdk = 28; targetSdk = 34; versionCode = 1; versionName = "0.1"
        buildConfigField("String", "SERVER_URL", "\"https://carbiseo-api.wondi81.workers.dev\"")
        buildConfigField("String", "CAR_TOKEN", "\"$carToken\"")
    }
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
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    testImplementation("junit:junit:4.13.2")
}
