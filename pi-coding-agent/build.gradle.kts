plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":pi-ai"))
    implementation(project(":pi-agent"))

    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass.set("dev.pi.coding.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
