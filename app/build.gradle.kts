import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "dev.rusty.app"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "dev.rusty.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 11
        versionName = "2.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The Spotify core ships .so files for exactly these two ABIs (jniLibs/), so builds were
        // implicitly two-ABI already. The sherpa-onnx AAR under libs/ would silently widen the
        // APK to x86/x86_64 — sherpa present, Spotify core absent, +50 MB — without this filter.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    // Stable release signing key. Without a persistent key, every CI build would be
    // signed with a freshly generated debug keystore, and Android refuses to update
    // an installed app when the signing certificate changes
    // (INSTALL_FAILED_UPDATE_INCOMPATIBLE). The keystore and passwords are supplied
    // via environment variables (see .github/workflows/release.yml); when they are
    // absent the config stays empty so local debug builds are unaffected.
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("SIGNING_KEYSTORE_FILE")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Only attach the release signing key when its keystore is provided (CI).
            // A bare local `assembleRelease` then produces an unsigned APK rather
            // than failing on a missing keystore.
            if (System.getenv("SIGNING_KEYSTORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    // BuildConfig is off by default in AGP 8+. The remote-control API reports the app version in
    // GET /api/state (Home Assistant shows it as the device's software version), and the generated
    // BuildConfig.VERSION_NAME is the only compile-time-constant source of the value declared above.
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.coil)
    // Pinned to 1.3.0 — the version Material 1.13.0 already resolves to transitively, and the
    // newest one present in the offline Gradle cache this repo must build against. The line is
    // explicit because the Immich filter picker uses RecyclerView directly; relying on a
    // transitive dependency for direct API usage breaks silently if Material ever drops it.
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    implementation(libs.androidx.palette)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.rtsp)
    implementation(libs.androidx.media3.ui)
    implementation(libs.protobuf.javalite)
    implementation(libs.androidx.security.crypto)
    // Embedded neural TTS (Piper voices) — sherpa-onnx's prebuilt Android AAR, checked in under
    // libs/ like the Spotify core's .so files (Apache-2.0; x86/x86_64 jni stripped, the app never
    // ships those ABIs). Pinned; upgrade by replacing the file.
    implementation(files("libs/sherpa-onnx-1.13.6.aar"))
    // tar.bz2 extraction for the downloaded voice bundles (pure Java, no transitive deps at this
    // version — 1.25+ pulls commons-io/commons-codec).
    implementation("org.apache.commons:commons-compress:1.24.0")
    // Standalone XmlPullParser impl (not the android.util.Xml/Expat one, whose factory is a
    // "not mocked" stub in plain JVM unit tests) — used for WS-Discovery XML so parsing is
    // testable without Robolectric and identical on-device.
    implementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.fragment.testing)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("java") {
                    option("lite")
                }
            }
        }
    }
}