rootProject.name = "pi2kotlin"

include("pi-ai", "pi-agent", "pi-coding-agent", "pi-mcp")
include("examples:jvm-demo", "examples:mcp-demo")

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
