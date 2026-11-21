import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val localVersionCode = (System.currentTimeMillis() / 60000).toInt()
val injectedVersionCode = ciRunNumber ?: localVersionCode
val injectedVersionName = System.getenv("VERSION_NAME") ?: "10.0-kappa1.11"
val buildTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

android {
    namespace = "com.downloadsource"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.lm.downloader"
        minSdk = 24
        targetSdk = 34
        versionCode = injectedVersionCode
        versionName = injectedVersionName
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild { cmake { cppFlags += "-std=c++17 -O3 -flto"; arguments += "-DANDROID_STL=c++_shared" } }
    }

    flavorDimensions += "channel"
    productFlavors {
        create("zeta") {
            dimension = "channel"
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            resValue("string", "app_name", "LM Ultra Fast Downloader Beta")
            buildConfigField("String", "CHANNEL", "\"zeta\"")
        }
        create("omega") {
            dimension = "channel"
            resValue("string", "app_name", "LM Ultra Fast Downloader Pro")
            buildConfigField("String", "CHANNEL", "\"omega\"")
        }
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_PATH")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // 关闭 R8：避免 Gson TypeToken / Room 反射被裁坏
            isMinifyEnabled = false
            isShrinkResources = false
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug { isMinifyEnabled = false }
    }

    buildFeatures { compose = true; buildConfig = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    packaging { jniLibs { useLegacyPackaging = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Room 数据库
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // 桌面小组件 (Glance)
    implementation("androidx.glance:glance:1.1.0")
    implementation("androidx.glance:glance-appwidget:1.1.0")

    // 媒体库
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    
    // 加密库
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    debugImplementation("androidx.compose.ui:ui-tooling")
}
