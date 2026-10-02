import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Klucz do podpisu wydania leży poza repo: keystore.properties w katalogu
// projektu (jest w .gitignore) z samą ścieżką i aliasem. Hasło NIE w pliku,
// tylko w zmiennej NARCISSUS_KEY_PASS na czas buildu. Bez pliku release
// buduje się niepodpisany.
val keystoreFile = rootProject.file("keystore.properties")
val keystore = Properties().apply { if (keystoreFile.exists()) keystoreFile.inputStream().use(::load) }

android {
    namespace = "pl.yggdrasil.narcissus2"
    compileSdk = 36

    defaultConfig {
        // Identyfikator w sklepach. Pakiet kodu (namespace) zostaje pl.yggdrasil.narcissus2.
        applicationId = "io.github.pablofrelo.narcissus"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.1"
    }

    signingConfigs {
        create("release") {
            if (keystoreFile.exists()) {
                storeFile = file(keystore.getProperty("storeFile"))
                keyAlias = keystore.getProperty("keyAlias")
                val pass = System.getenv("NARCISSUS_KEY_PASS")
                    ?: error("Brak NARCISSUS_KEY_PASS — hasło do klucza wydania")
                storePassword = pass
                keyPassword = pass
            }
        }
    }

    buildTypes {
        release {
            if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
