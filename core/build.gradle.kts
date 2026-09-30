plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// Shared by both apps: the read-only Desay VDBus client and the decode table of
// identified channels. No UI, no recording.
android {
    namespace = "net.gooshy.omodaboard.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 30
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
    testImplementation(libs.junit)
}
