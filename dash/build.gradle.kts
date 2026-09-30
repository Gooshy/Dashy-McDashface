plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Dashy McDashface: the driver's dashboard. Reads only the channels already
// identified (see core's KnownChannels) and draws them. No recording, no
// discovery sweep, no export: that's the OmodaBoard recorder's job.
android {
    namespace = "net.gooshy.dashy"
    compileSdk = 35

    // CarPropertyManager, for speed / gear / brake. Optional at runtime.
    useLibrary("android.car")

    defaultConfig {
        applicationId = "net.gooshy.dashy"
        minSdk = 30
        targetSdk = 30 // the head unit is Android 11
        versionCode = 6
        versionName = "2.0.0"
    }

    buildTypes {
        release {
            // Sideloaded onto one car: the debug key means no keystore setup.
            signingConfig = signingConfigs.getByName("debug")
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

    buildFeatures {
        compose = true
    }

    lint {
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)

    testImplementation(libs.junit)
}
