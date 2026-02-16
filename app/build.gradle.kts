plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val tgwebStoreFile = rootProject.file("signing/tgweb-update.jks")
val tgwebStorePassword = (findProperty("TGWEB_STORE_PASSWORD") as? String)
    ?: System.getenv("TGWEB_STORE_PASSWORD")
    ?: "tgwebmobile"
val tgwebKeyAlias = (findProperty("TGWEB_KEY_ALIAS") as? String)
    ?: System.getenv("TGWEB_KEY_ALIAS")
    ?: "tgweb"
val tgwebKeyPassword = (findProperty("TGWEB_KEY_PASSWORD") as? String)
    ?: System.getenv("TGWEB_KEY_PASSWORD")
    ?: "tgwebmobile"

android {
    namespace = "com.tgweb.app"
    compileSdk = 34

    signingConfigs {
        create("stable") {
            storeFile = tgwebStoreFile
            storePassword = tgwebStorePassword
            keyAlias = tgwebKeyAlias
            keyPassword = tgwebKeyPassword
        }
    }

    defaultConfig {
        applicationId = "com.tgweb.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 4
        versionName = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            signingConfig = signingConfigs.getByName("stable")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:db"))
    implementation(project(":core:media"))
    implementation(project(":core:tdlib"))
    implementation(project(":core:webbridge"))
    implementation(project(":core:notifications"))
    implementation(project(":core:sync"))

    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
}
