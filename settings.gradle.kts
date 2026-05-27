rootProject.name = "kafka-desktop"

include("app")
include("core-kafka")
include("core-filter")
include("core-storage")
include("core-auth")
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
