rootProject.name = "kafka-desktop"

include("app")
include("core-kafka")
include("ui-common")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
