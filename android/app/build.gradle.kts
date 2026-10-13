plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.sync.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.sync.app"
        // setIsStrongBoxBacked が API 28 以降のため
        minSdk = 28
        targetSdk = 35
        // versionCode はサーバーの auth.min-app-version と比較される (X-App-Version ヘッダ)
        versionCode = 1
        versionName = "1.0"

        // ./gradlew assembleDebug -PsyncServerUrl=https://sync-server.local:8443
        val serverUrl = (project.findProperty("syncServerUrl") as String?) ?: "https://sync-server.local:8443"
        buildConfigField("String", "SERVER_BASE_URL", "\"$serverUrl\"")
        // 失効(device_revoked)を受け取ったときにローカルデータを消去するか。運用ルールに合わせて決める。
        val wipe = (project.findProperty("wipeOnRevoked") as String?) ?: "false"
        buildConfigField("boolean", "WIPE_ON_REVOKED", wipe)
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":authclient"))
    // ローカル DB の暗号化 (鍵は Keystore でラップして保存する)
    implementation("net.zetetic:sqlcipher-android:4.6.1@aar")
    implementation("androidx.sqlite:sqlite:2.4.0")
}
