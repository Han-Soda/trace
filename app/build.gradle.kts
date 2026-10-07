plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI passes the commit it builds, so an installed app can be matched to its build, and a
// version code that grows with every run, as Google Play needs for each upload.
val commit = System.getenv("TRACE_COMMIT")?.trim()?.take(7)?.takeIf { it.isNotEmpty() }
val buildNumber = System.getenv("TRACE_VERSION_CODE")?.toIntOrNull()

android {
    namespace = "io.github.hansoda.trace"
    // Compose 1.12 needs to compile against Android 17; the app still targets Android 16.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.hansoda.trace"
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber ?: 2
        versionName = if (commit == null) "1.2" else "1.2-$commit"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        val store = System.getenv("TRACE_KEYSTORE")
        if (!store.isNullOrBlank()) {
            create("release") {
                storeFile = file(store)
                storePassword = System.getenv("TRACE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TRACE_KEY_ALIAS")
                keyPassword = System.getenv("TRACE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.ui:ui:1.12.1")
    implementation("androidx.compose.foundation:foundation:1.12.1")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.code.gson:gson:2.13.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
