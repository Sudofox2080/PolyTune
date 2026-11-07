plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.polytune"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.polytune"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.1.1"
        testInstrumentationRunner = "com.polytune.FormatSmokeInstrumentation"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    // 不使用 compose BOM：M3E 需要 material3 1.5.0-alpha22，BOM 会把它压回 1.4.0。
    val composeVersion = "1.12.0-alpha03"
    implementation("androidx.compose.runtime:runtime:$composeVersion")
    implementation("androidx.compose.ui:ui:$composeVersion")
    implementation("androidx.compose.ui:ui-graphics:$composeVersion")
    implementation("androidx.compose.ui:ui-tooling-preview:$composeVersion")
    implementation("androidx.compose.foundation:foundation:$composeVersion")
    implementation("androidx.compose.foundation:foundation-layout:$composeVersion")
    implementation("androidx.compose.animation:animation:$composeVersion")
    implementation("androidx.compose.animation:animation-core:$composeVersion")
    implementation("androidx.compose.material:material-icons-extended:1.7.6")
    implementation("androidx.compose.material3:material3:1.5.0-alpha22")
    debugImplementation("androidx.compose.ui:ui-tooling:$composeVersion")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    // 关于 FFmpeg 扩展解码器（APE/WMA/ALAC/MKA 等系统解不了的格式）：
    // 实测 1.5.1 以及全部 media3 版本都没有发布 media3-decoder-ffmpeg 这个产物——
    // https://repo1.maven.org/maven2/androidx/media3/media3-decoder-ffmpeg/ 返回 404，
    // https://dl.google.com/dl/android/maven2/androidx/media3/group-index.xml 里也没有，
    // 官方 libraries/decoder_ffmpeg/README.md 明说 "The module is not provided via Google's Maven repository"。
    // 所以这里不写一个永远 404 的坐标，改为显式声明软解接入点：
    // 接入方式是把 androidx/media 的 libraries/decoder_ffmpeg 用 NDK 编译（FFMPEG_MODULE_PATH + NDK_PATH），
    // 产物打进 APK 后 PlaybackEngine.renderersFactory() 的 EXTENSION_RENDERER_MODE_PREFER 会自动加载它，
    // 无需改代码。音频格式能力表见 player/AudioFormats.kt，运行时是否生效由
    // PlaybackEngine.isFfmpegRendererLoaded 反射探测。
    // TODO(ffmpeg)：编好 decoder_ffmpeg 的 aar 后在此加一行
    //   implementation(files("../libs/media3-decoder-ffmpeg-release.aar"))

    implementation("androidx.room:room-runtime:2.8.1")
    implementation("androidx.room:room-ktx:2.8.1")
    ksp("androidx.room:room-compiler:2.8.1")

    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")

    implementation("com.hierynomus:smbj:0.13.0")
    implementation("commons-net:commons-net:3.11.1")
}
