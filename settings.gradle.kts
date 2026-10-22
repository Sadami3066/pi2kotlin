rootProject.name = "pi2kotlin"

include("pi-ai", "pi-agent", "pi-coding-agent")
include("examples:jvm-demo")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
