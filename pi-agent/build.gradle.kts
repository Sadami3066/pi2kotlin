plugins {
    `maven-publish`
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "dev.pi.agent"
        compileSdk = 36
        minSdk = 26
        withHostTest {}
    }

    jvm()
    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            api(project(":pi-ai"))
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

