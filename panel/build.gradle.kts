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
        versionCode = 5
        versionName = "0.5"
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
}
