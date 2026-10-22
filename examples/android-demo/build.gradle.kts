plugins {
    id("com.android.application") version "9.4.1"
    id("org.jetbrains.kotlin.android") version "2.4.21"
}

android {
    namespace = "dev.pi.demo.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.pi.demo.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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
    implementation("dev.pi:pi-coding-agent-android:0.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
