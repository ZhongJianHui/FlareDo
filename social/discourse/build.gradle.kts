import dev.dimension.flare.buildlogic.FlarePlatform
import dev.dimension.flare.buildlogic.flare

plugins {
    id("dev.dimension.flare.multiplatform-library")
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktorfit)
}

kotlin {
    flare {
        namespace = "dev.dimension.flare.social.discourse"
        platforms(
            FlarePlatform.ANDROID,
            FlarePlatform.JVM,
            FlarePlatform.IOS,
            FlarePlatform.MACOS,
        )
        ksp(libs.ktorfit.ksp)
    }

    android {
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.shared)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.ktorfit.lib)
            implementation(libs.ktorfit.converters.response)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        getByName("androidJvmMain").dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        getByName("appleMain").dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
