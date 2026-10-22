plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

subprojects {
    group = "dev.pi"
    version = "0.1.0-SNAPSHOT"
}
