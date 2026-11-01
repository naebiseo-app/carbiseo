plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
// car-app과 같은 방식: 현대차공모전/.env 의 CAR_TOKEN(없으면 빈 문자열)을 BuildConfig로만 주입. 값은 소스에 두지 않는다.
val envLines = file("../../.env").takeIf { it.exists() }?.readLines().orEmpty()
fun env(k: String) = envLines.firstOrNull { it.startsWith("$k=") }?.substringAfter("=")?.trim()?.trim { it.code == 34 || it.code == 39 } ?: ""
android {
    namespace = "ztw.carbiseo.phone"
    compileSdk = 34
    defaultConfig { applicationId = "ztw.carbiseo.phone"; minSdk = 28; targetSdk = 34; versionCode = 1; versionName = "0.1"
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
    testImplementation("junit:junit:4.13.2")
}
