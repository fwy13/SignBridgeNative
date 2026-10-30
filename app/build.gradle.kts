import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val local = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
fun config(name: String, default: String = "") = (local.getProperty(name) ?: default).replace("\\", "\\\\").replace("\"", "\\\"")
android {
    namespace = "com.fwy13.signbridge"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.fwy13.SignBridgeApp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "API_URL", "\"${config("API_URL", "https://se.cit.ctu.edu.vn/signbridge/api/v1")}\"")
        buildConfigField("String", "WS_BASE", "\"${config("WS_BASE", "wss://se.cit.ctu.edu.vn/signbridge")}\"")
        buildConfigField("String", "LLM_PROVIDER", "\"${config("LLM_PROVIDER", "ollama")}\"")
        buildConfigField("String", "OLLAMA_BASE_URL", "\"${config("OLLAMA_BASE_URL")}\"")
        buildConfigField("String", "OLLAMA_MODEL", "\"${config("OLLAMA_MODEL", "qwen2.5:7b-instruct")}\"")
        buildConfigField("String", "GROQ_API_KEY", "\"${config("GROQ_API_KEY")}\"")
        buildConfigField("String", "GROQ_MODEL", "\"${config("GROQ_MODEL", "llama-3.1-8b-instant")}\"")
        buildConfigField("String", "ALPHABET_CLOUD", "\"${config("ALPHABET_CLOUD", "dwdsrtyim")}\"")
        // Matches the existing demo: sentence generation is implemented but its UI is hidden.
        buildConfigField("boolean", "SENTENCE_BUILDING_AVAILABLE", "false")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
    implementation("com.google.ai.edge.litert:litert:1.0.1")
    // Required native dependency, not shipped in the source-only Repomix upload.
    // Retain the ARMv8-A rebuild: upstream 1.4.0 can SIGILL on the target Vivo V2206.
    implementation(files("libs/executorch-android-1.4.0-armv8a.aar"))
    implementation("com.facebook.fbjni:fbjni:0.7.0")
    implementation("com.facebook.soloader:nativeloader:0.10.5")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("com.google.zxing:core:3.5.3")
}
