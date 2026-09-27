plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.faucherd.markdownnotes"
    compileSdk {
        version = release(37)
    }

    // AGP 9 ships its own Kotlin support; applying org.jetbrains.kotlin.android
    // on top of it fails with "Cannot add extension with name 'kotlin'".
    enableKotlin = true

    defaultConfig {
        applicationId = "com.faucherd.markdownnotes"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        viewBinding = true
    }

    // SafeWriter is exercised for real against temp files on the JVM. The one
    // Android call it makes is android.os.Process.myPid(), which is stubbed in
    // unit tests; returning a default rather than throwing is what lets the
    // write path be tested outside a device.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.material)

    // LineOps decides how the outline is restructured. A bug there silently
    // corrupts the vault, so it is covered by JVM unit tests rather than only
    // being exercised by hand in the emulator.
    testImplementation(libs.junit)
}
