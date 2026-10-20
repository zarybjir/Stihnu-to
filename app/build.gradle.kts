plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cz.stihnuto.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "cz.stihnuto.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // Podepisování release buildu: hodnoty se berou z proměnných prostředí
    // (GitHub Actions) nebo z ~/.gradle/gradle.properties. Když chybí,
    // release se nepodepíše — pak použij debug build nebo
    // Build > Generate Signed App Bundle / APK v Android Studiu.
    fun prop(name: String): String? = System.getenv(name) ?: (project.findProperty(name) as String?)
    val ksPath = prop("STIHNU_KEYSTORE")
    if (ksPath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(ksPath)
                storePassword = prop("STIHNU_KEYSTORE_PASSWORD")
                keyAlias = prop("STIHNU_KEY_ALIAS")
                keyPassword = prop("STIHNU_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksPath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.11.0")
}
