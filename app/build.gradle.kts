plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.squish.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.squish.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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

    androidResources {
        // Memory-mapped by the segmenter, which a compressed asset cannot be.
        noCompress += "tflite"
    }

    packaging {
        resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
    }
}

/**
 * Kotlin settings live here rather than in `android { kotlinOptions { } }`.
 *
 * That block is a deprecated shim under the Kotlin 2.x Gradle plugin, and
 * `freeCompilerArgs +=` inside it is not reliably carried through to the compile
 * tasks - which is how this project spent a while believing it had opted in to
 * Media3's unstable API while every file that touched Transformer was failing to
 * compile. `kotlin { compilerOptions { } }` is the authoritative DSL.
 *
 * Media3's @UnstableApi is not opted in to here. It is an androidx
 * `RequiresOptIn` marker, enforced by lint rather than the Kotlin compiler, so a
 * compiler `optIn` entry for it does nothing except warn that it "is not an
 * opt-in requirement marker". Every Media3 file carries its own
 * `@file:OptIn(UnstableApi::class)`, which is what lint reads.
 */
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    // Everything the source imports directly, declared. The Compose compiler
    // needs runtime on the compile classpath and will not say so in those words.
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.common)
    implementation(libs.mediapipe.tasks.vision)
}
