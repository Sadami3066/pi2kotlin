plugins {
    id("com.android.application") version "9.4.1"
    id("org.jetbrains.kotlin.android") version "2.4.21"
}

android {
    namespace = "io.github.whywood.deepdeep.sdk.demo.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.whywood.deepdeep.sdk.demo.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.4.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("io.github.whywood.deepdeep.sdk:pi-coding-agent-android:0.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
