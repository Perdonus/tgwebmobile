plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tgweb.core.notifications"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "DEFAULT_PUSH_BACKEND_URL", "\"https://sosiskibot.ru/flygram/push\"")
        buildConfigField("String", "DEFAULT_PUSH_BACKEND_FALLBACK_URL", "\"http://91.233.168.135:8081/flygram/push\"")
        buildConfigField("String", "PUSH_SHARED_SECRET", "\"flygram_push_2026\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:sync"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.firebase:firebase-messaging-ktx:24.1.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
