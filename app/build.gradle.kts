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
        // Sur GitHub Actions, le numéro de build sert de numéro de version
        // (c'est ce que compare le bouton « Mettre à jour l'appli »).
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "2.0.$build"
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
            // Version optimisée : code compacté et optimisé par R8, ressources inutiles retirées.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("yomu")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    // Analyse HTML + sélecteurs CSS pour les extensions JSON.
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Bibliothèques fournies aux extensions Tachiyomi/Mihon (mêmes que Mihon 0.21).
    implementation("io.reactivex:rxjava:1.3.8")
    implementation("uy.kohesive.injekt:injekt-core:1.16.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    // Installe les « profils de démarrage » de Compose : appli plus fluide dès le premier lancement.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
