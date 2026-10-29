plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
// 현대차공모전/.env 의 CAR_TOKEN(없으면 빈 문자열). 값은 소스에 두지 않고 BuildConfig로만 주입한다.
val envLines = file("../../.env").takeIf { it.exists() }?.readLines().orEmpty()
fun env(k: String) = envLines.firstOrNull { it.startsWith("$k=") }?.substringAfter("=")?.trim()?.trim { it.code == 34 || it.code == 39 } ?: ""
val carToken = env("CAR_TOKEN")
android {
    namespace = "ztw.carbiseo"
    compileSdk = 34
    useLibrary("android.car") // CarPropertyManager(속도). 런타임은 매니페스트 uses-library
    defaultConfig { applicationId = "ztw.carbiseo"; minSdk = 28; targetSdk = 34; versionCode = 1; versionName = "0.1"
        buildConfigField("String", "SERVER_URL", "\"https://carbiseo-api.wondi81.workers.dev\"")
        buildConfigField("String", "CAR_TOKEN", "\"$carToken\"")
        // Pleos TTS 서버 모드용(에뮬엔 기기 안 TTS 엔진이 없어 ON_DEVICE가 실패). 비면 ON_DEVICE.
        buildConfigField("String", "PLEOS_CLIENT_ID", "\"${env("PLEOS_CLIENT_ID")}\"")
        buildConfigField("String", "PLEOS_CLIENT_SECRET", "\"${env("PLEOS_CLIENT_SECRET")}\"")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
}
dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    testImplementation("junit:junit:4.13.2")
}
