plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":pi-ai"))
    implementation(project(":pi-coding-agent"))
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("dev.pi.demo.MainKt")
}
