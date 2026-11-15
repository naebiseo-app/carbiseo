// phone-app 모듈 설정. 서버 주소·토큰을 BuildConfig로 넣고, Compose 화면과 구글 로그인(서버용 인증 코드) 의존성을 둔다.
// 먼저 읽을 곳: defaultConfig의 buildConfigField 두 줄(SERVER_URL, CAR_TOKEN).
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
// car-app과 같은 방식: 저장소 루트 .env의 CAR_TOKEN(없으면 빈 문자열)을 BuildConfig로만 주입한다. 값은 소스에 두지 않는다.
// 이 토큰은 시연용 공용 값이라 공개한 APK에서 꺼낼 수 있다(PRIVACY.md 5번).
val envLines = file("../../.env").takeIf { it.exists() }?.readLines().orEmpty()
// .env에서 `k=값` 줄의 값을 꺼내고 양쪽 따옴표(코드 34 = 큰따옴표, 39 = 작은따옴표)를 뗀다. 없으면 빈 문자열.
fun env(k: String) = envLines.firstOrNull { it.startsWith("$k=") }?.substringAfter("=")?.trim()?.trim { it.code == 34 || it.code == 39 } ?: ""
android {
    namespace = "ztw.carbiseo.phone"
    compileSdk = 34
    defaultConfig { applicationId = "ztw.carbiseo.phone"; minSdk = 28; targetSdk = 34; versionCode = 1; versionName = "0.1"
        // 서버(Cloudflare Workers) 주소.
        buildConfigField("String", "SERVER_URL", "\"https://carbiseo-api.wondi81.workers.dev\"")
        buildConfigField("String", "CAR_TOKEN", "\"${env("CAR_TOKEN")}\"")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" } // car-app과 같은 Compose 버전
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("com.google.android.gms:play-services-auth:22.0.0") // 구글 캘린더 권한(서버용 인증 코드)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // 단위 시험에서 org.json 쓰기(안드로이드 stub은 시험 JVM에서 동작 안 함)
}
