plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":pi-ai"))
    implementation(project(":pi-coding-agent"))
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("dev.pi.demo.mcp.MainKt")
}
