plugins {
    // Kotlin compilation comes from AGP itself since 9.0. Applying
    // org.jetbrains.kotlin.android alongside it is a hard error.
    id("com.android.application")
}

android {
    namespace = "ca.scryr.ringcursor"
    compileSdk = 37

    defaultConfig {
        applicationId = "ca.scryr.ringcursor"
        minSdk = 26
        targetSdk = 37
        versionCode = 7
        versionName = "0.7-actions"
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    // AGP derives the Kotlin jvmTarget from these, so no separate
    // kotlinOptions/compilerOptions block is needed.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
}
