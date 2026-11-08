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
    mainClass.set("io.github.whywood.deepdeep.sdk.demo.mcp.MainKt")
}
