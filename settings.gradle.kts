rootProject.name = "spcrefs"

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

include(":shared")
// the Android app needs an SDK; include it when one is configured (CI sets ANDROID_HOME) so ":shared:jvmTest" works anywhere
if (System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null || file("local.properties").exists()) {
    include(":androidApp")
}
