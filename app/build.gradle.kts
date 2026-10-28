plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.matthew.crs"
    compileSdk = 37
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.matthew.crs"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "com.matthew.crs.ProjectInstrumentation"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_shared") } }
    }
    // Device tests and benchmarks run on deviceTest: release in every respect
    // but R8 (as RawLens). -PtestBuildType=release runs them on the minified APK.
    testBuildType = (project.findProperty("testBuildType") as String?) ?: "deviceTest"
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            isJniDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideload/dev builds: sign with the debug key until a release key exists.
            signingConfig = signingConfigs.getByName("debug")
            // Native code is CMake Release (-O3). For benchmarks force full AOT after install:
            //   adb shell cmd package compile -m speed -f com.matthew.crs
            externalNativeBuild { cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release") } }
        }
        create("deviceTest") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += listOf("release")
        }
    }
    buildFeatures { compose = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
