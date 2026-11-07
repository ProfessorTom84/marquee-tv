plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("app.cash.paparazzi")
}

android {
    namespace = "app.marquee.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.marquee.tv"
        // Read-only update token, injected by the Release workflow from the MARQUEE_UPDATE_TOKEN secret.
        // Never stored in the source; local builds get an empty one (the phone setup page can still set it).
        buildConfigField("String", "UPDATE_TOKEN", "\"${System.getenv("MARQUEE_UPDATE_TOKEN") ?: ""}\"")
        minSdk = 31
        targetSdk = 35
        versionCode = 30
        versionName = "0.14.1"
    }

    signingConfigs {
        create("release") {
            storeFile = file("marquee-release.jks")
            storePassword = System.getenv("MARQUEE_STORE_PASSWORD") ?: "marquee-dev"
            keyAlias = "marquee"
            keyPassword = System.getenv("MARQUEE_KEY_PASSWORD") ?: "marquee-dev"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.tv:tv-material:1.0.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-service:2.9.1")
    implementation("androidx.savedstate:savedstate-ktx:1.3.0")
    implementation("androidx.core:core-ktx:1.16.0")
    // Installs the Compose baseline profiles on sideloaded installs, so scrolling is compiled ahead of time.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.6.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
    testImplementation("junit:junit:4.13.2")
}
