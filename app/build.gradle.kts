plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.yomu.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yomu.reader"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // Clé de signature fixe : chaque nouvelle version s'installe par-dessus
    // l'ancienne sans perdre la bibliothèque.
    signingConfigs {
        create("yomu") {
            storeFile = file("yomu.keystore")
            storePassword = "yomu1234"
            keyAlias = "yomu"
            keyPassword = "yomu1234"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("yomu")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("yomu")
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
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
