import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// ---------------------------------------------------------------------------
// Android wheels for the native decoders.
//
// texture2ddecoder, etcpak and astc-encoder-py (UnityPy's third native
// dependency) are C extensions. Chaquopy's package repository does not carry
// them and PyPI only ships Windows/macOS/Linux wheels, so pip cannot fetch them
// for Android. Build Android wheels once (see app/wheels/README.txt) and drop
// the .whl files into app/wheels: this script then bundles every wheel it finds.
// Without wheels the app still builds and runs; only formats that need those
// decoders (ETC/ASTC/etc.) are unavailable, and replacements fall back to RGBA32.
// ---------------------------------------------------------------------------
val localWheels: List<File> = File(projectDir, "wheels")
    .listFiles { file: File -> file.isFile && file.extension == "whl" }
    ?.sortedBy { it.name }
    .orEmpty()

logger.lifecycle(
    if (localWheels.isEmpty()) {
        "UABEA Mobile: no wheels in app/wheels -> building WITHOUT native texture decoders."
    } else {
        "UABEA Mobile: bundling local Android wheels: " + localWheels.joinToString { it.name }
    }
)

android {
    namespace = "com.example.uabeamobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.uabeamobile"
        minSdk = 24 // Chaquopy 17.0 requires API 24+
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            // Real phones (arm64) and the emulator (x86_64).
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sign release builds with the debug key so `assembleRelease` gives an installable APK.
            signingConfig = signingConfigs.getByName("debug")
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

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14" // matches Kotlin 1.9.24
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

chaquopy {
    defaultConfig {
        // Python 3.13 or newer is needed for 16 KB page-size devices.
        // The build machine must have a matching "python3.13" (or "py -3.13") installed.
        version = "3.13"

        pip {
            // All requirements are listed explicitly, so skip dependency resolution:
            // otherwise pip would try to fetch the three native packages above from PyPI
            // and fail the build.
            options("--no-deps")

            install("UnityPy==1.25.4")
            install("Pillow")      // Android wheel from Chaquopy's repository
            install("lz4")         // Android wheel from Chaquopy's repository
            install("brotli")      // Android wheel from Chaquopy's repository
            install("attrs")
            install("fsspec")
            install("tpk-ar")

            if (localWheels.isNotEmpty()) {
                install("archspec") // runtime dependency of etcpak / astc-encoder-py
                localWheels.forEach { install(it.absolutePath) }
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
