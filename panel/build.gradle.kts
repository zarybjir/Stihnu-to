plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "cz.stihnuto.panel"
    compileSdk = 34

    defaultConfig {
        applicationId = "cz.stihnuto.panel"
        minSdk = 26
        targetSdk = 34
        versionCode = 76
        versionName = "0.76"
        ndk { abiFilters += setOf("armeabi-v7a", "arm64-v8a") }
    }

    // Stálý podpisový klíč (GitHub secret PANEL_KEYSTORE_B64 + PANEL_KEYSTORE_PASSWORD).
    // Díky němu se každá nová verze nainstaluje přes starou a nastavení zůstane.
    // Bez něj se podepíše náhodným ladicím klíčem.
    val ksPath = System.getenv("PANEL_KEYSTORE")
    if (ksPath != null) {
        signingConfigs {
            getByName("debug") {
                storeFile = file(ksPath)
                storePassword = System.getenv("PANEL_KEYSTORE_PASSWORD")
                keyAlias = "panel"
                keyPassword = System.getenv("PANEL_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
            pickFirsts += setOf("**/module-info.class")
        }
    }
}

dependencies {
    // Claude (hlasový asistent). Načítá se až při prvním dotazu.
    implementation("com.anthropic:anthropic-java:3.4.0")
    // Hlasová aktivace „prosím Pepo“ — rozpoznávání řeči přímo v hodinách (offline)
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    // QR kód pro „Poslat z mobilu“
    implementation("com.google.zxing:core:3.5.3")
}
