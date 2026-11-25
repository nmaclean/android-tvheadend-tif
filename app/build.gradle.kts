import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24"
}

android {
    namespace = "com.nmaclean.tvheadend"
    compileSdk = 34

    val ciVersionCode = project.findProperty("versionCode")?.toString()?.toIntOrNull()
    val ciVersionName = project.findProperty("versionName")?.toString()?.takeIf { it.isNotBlank() }

    defaultConfig {
        applicationId = "com.nmaclean.tvheadend"
        minSdk = 26
        targetSdk = 34
        versionCode = ciVersionCode ?: 10000
        versionName = ciVersionName ?: "1.0.0"

        val buildTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            val hasKeystore = !System.getenv("KEYSTORE_PATH").isNullOrBlank()
            signingConfig = if (hasKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // AndroidX TV Frameworks & Core UI
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.tvprovider:tvprovider:1.1.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.security:security-crypto:1.0.0")

    // Jetpack Compose for TV
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // AndroidX Media3 (ExoPlayer)
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.3.1+1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Async & Networking
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // Kotlinx Serialization & Ktor Server CIO
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("io.ktor:ktor-server-core:2.3.11")
    implementation("io.ktor:ktor-server-cio:2.3.11")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.11")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.11")

    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.24.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.robolectric:robolectric:4.18.1")
    testImplementation("io.ktor:ktor-server-test-host:2.3.11")
}


