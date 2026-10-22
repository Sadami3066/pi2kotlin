plugins {
    `maven-publish`
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "dev.pi.ai"
        compileSdk = 36
        minSdk = 26
        withHostTest {}
    }

    jvm()
    jvmToolchain(21)

    sourceSets {
        val okhttpMain = create("okhttpMain")
        okhttpMain.dependsOn(commonMain.get())

        jvmMain.get().dependsOn(okhttpMain)
        androidMain.get().dependsOn(okhttpMain)

        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
            api(libs.okio)
        }

        okhttpMain.dependencies {
            implementation(libs.okhttp)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        jvmTest.get().dependsOn(commonTest.get())
        jvmTest.dependencies {
            implementation(libs.okhttp.mockwebserver)
        }
    }
}
