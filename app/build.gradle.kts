import org.gradle.api.tasks.testing.logging.TestExceptionFormat
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.dearmoon.shield"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dearmoon.shield"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    sourceSets {
        getByName("main") {
            assets {
                srcDirs("src/main/assets", "../modea/app/src/main/assets")
            }
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Give each test JVM a modest heap — keeps total RAM usage down
            it.maxHeapSize = "256m"
            it.testLogging {
                events("passed", "skipped", "failed")
                showStandardStreams = true
                exceptionFormat = TestExceptionFormat.FULL
            }
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = "password"
            keyAlias = "release"
            keyPassword = "password"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // C-03: Ensure signature hash is validated before release build completes
    // Task registered above will fail release builds if hash is still null
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    implementation(libs.viewpager2)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.fragment.ktx)
    implementation("com.github.PhilJay:MPAndroidChart:v3.1.0")
    implementation("androidx.biometric:biometric:1.1.0")

    // Unit testing
    testImplementation(libs.junit)
    testImplementation("org.mockito:mockito-core:5.3.1")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.json:json:20231013")

    // Android instrumented testing
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}


