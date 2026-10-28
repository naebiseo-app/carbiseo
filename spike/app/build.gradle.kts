plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "spike.lab"
    compileSdk = 34
    defaultConfig { applicationId = "spike.lab"; minSdk = 28; targetSdk = 34; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
}
