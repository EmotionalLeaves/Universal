plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// GitHub Actions sets GITHUB_RUN_NUMBER, so every build gets a higher
// version code and installs over the previous one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.emotionalleaves.universe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.emotionalleaves.universe"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.0.$buildNumber"
    }

    // Fixed, non-secret key committed to the repo (see docs/PLAN.md).
    // Every build is signed with the same key so updates install in place.
    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("keystore/universe-explorer.jks")
            storePassword = "universe123"
            keyAlias = "universe"
            keyPassword = "universe123"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
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
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
}
