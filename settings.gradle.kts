pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // NewPipe Extractor (YouTube), as in the official app.
        maven("https://jitpack.io")
    }
}

rootProject.name = "cloudstream-linux"
include(":cs-library", ":android-shim", ":app")
include(":examples:InternetArchiveProvider", ":tests:DemoProvider")
