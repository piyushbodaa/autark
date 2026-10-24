import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing reads an upload key from keystore.properties (never committed).
// Without it, release builds are produced unsigned.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.piyushbodaa.autark"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        // The Play Store never lets this change after the first upload.
        applicationId = "com.piyushbodaa.autark"
        minSdk = 28
        targetSdk = 36
        versionCode = 4
        versionName = "0.4.0"

        // On-device tests of the engine, for Firebase Test Lab: tools/device-test/run.sh.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // 64-bit Arm covers practically every phone that can run a language model;
            // x86_64 covers Chromebooks and the emulator.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_SHARED_LIBS=ON",
                    "-DLLAMA_BUILD_COMMON=OFF",  // built by our CMakeLists, as a static library
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_BACKEND_DL=ON",
                    "-DGGML_CPU_ALL_VARIANTS=ON",
                    "-DGGML_LLAMAFILE=OFF",
                )
            }
        }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("upload") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
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

    testOptions {
        // Each test in its own process: a process only ever loads one CPU backend variant.
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }

    packaging {
        // Native libraries stay uncompressed inside the APK and load from there, rather than
        // being unpacked into a second copy on install (Engine::init opens the CPU variants
        // by name for this).
        jniLibs.useLegacyPackaging = false
        // CPU backend variants no phone or Chromebook would pick: every Arm core with dotprod
        // also has FP16, every one with SME also has SVE2, the SVE2 one is slower than i8mm on
        // phones (Engine::init skips it), and the x86 ones left out are for servers or for chips
        // too old to run a model. Each would cost about 1.5 MB.
        jniLibs.excludes += listOf(
            "android_armv8.2_1", "android_armv9.0_1", "android_armv9.2_1",
            "sandybridge", "ivybridge", "piledriver", "skylakex", "cannonlake", "cascadelake",
            "cooperlake", "zen4", "sapphirerapids",
        ).map { "**/libggml-cpu-$it.so" }
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// libautark-tests.so runs the on-device tests (src/androidTest); the app itself never loads it.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.packaging.jniLibs.excludes.add("**/libautark-tests.so")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestUtil(libs.androidx.test.orchestrator)
}
