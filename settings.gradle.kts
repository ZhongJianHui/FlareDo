pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
// START Non-FOSS component
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
// END Non-FOSS component
dependencyResolutionManagement {
    // repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FlareDo"
include(":app")
include(":shared")
include(":social:discourse")
include(":compose-ui")
include(":apple-shared")
include(":desktopApp")
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
