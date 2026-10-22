plugins {
    `maven-publish`
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "dev.pi.coding"
        compileSdk = 36
        minSdk = 26
        withHostTest {}
    }

    jvm()
    jvmToolchain(21)

    sourceSets {
        val unixMain = create("unixMain")
        unixMain.dependsOn(commonMain.get())

        jvmMain.get().dependsOn(unixMain)
        androidMain.get().dependsOn(unixMain)

        jvmTest.get().dependsOn(commonTest.get())

        commonMain.dependencies {
            api(project(":pi-agent"))
            api(libs.okio)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okio.fakefilesystem)
        }
    }
}

