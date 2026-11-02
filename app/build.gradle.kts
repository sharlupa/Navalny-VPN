import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "org.navalny.vpn"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.navalny.vpn"
        minSdk = 26
        targetSdk = 36
        // CI passes these on tag builds (v1.2.3 -> "1.2.3", run number -> code)
        versionCode = providers.environmentVariable("VERSION_CODE").orNull?.toIntOrNull() ?: 1
        versionName = providers.environmentVariable("VERSION_NAME").orNull ?: "1.0.0"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // One APK per ABI: each is much smaller than a fat APK (the Go core is ~20 MB per ABI).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // Store .so uncompressed and page-aligned: they are mmapped directly from the APK,
            // no extraction on install, less storage and faster cold start.
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }
}

val libboxAar = file("libs/libbox.aar")
if (!libboxAar.exists()) {
    logger.warn(
        "\n⚠ app/libs/libbox.aar not found. Build it with scripts/build-libbox.sh " +
            "(or download the artifact from GitHub Actions).\n",
    )
}

dependencies {
    implementation(files("libs/libbox.aar"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.graphics.shapes)
    // Official Material color algorithms (HCT, dynamic schemes) — builds the named palettes
    implementation(libs.material.color.utilities) {
        // compile-time-only annotations of the library, not needed in the app
        exclude(group = "dev.drewhamilton.poko")
    }

    // QR: camera scanner (no Google Play Services needed) + decoding from gallery images.
    // The camera is only open while the scanner screen is shown.
    implementation(libs.zxing.embedded) { isTransitive = false }
    implementation(libs.zxing.core)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    // real org.json for JVM unit tests (the android.jar one is a stub)
    testImplementation(libs.org.json)
}
