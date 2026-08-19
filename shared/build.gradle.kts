
import dev.dimension.flare.buildlogic.FlarePlatform
import dev.dimension.flare.buildlogic.flare

plugins {
    id("dev.dimension.flare.multiplatform-library")
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.room)
}

kotlin {
    flare {
        namespace = "dev.dimension.flare.shared"
        platforms(
            FlarePlatform.ANDROID,
            FlarePlatform.JVM,
            FlarePlatform.IOS,
            FlarePlatform.MACOS,
        )
        ksp(libs.room.compiler)
    }

    android {
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            api(libs.paging.common)
            api(libs.paging.compose)
            implementation(libs.molecule.runtime)
            api(libs.room.runtime)
            implementation(libs.room.paging)
            implementation(libs.sqlite)
            implementation(libs.sqlite.async)
        }
        getByName("nonWebMain").dependencies {
            implementation(libs.sqlite.bundled)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.paging.testing)
        }
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

// AGP's host-test lint tasks read KSP output but do not currently wire the producer task.
tasks.matching {
    it.name.contains("AndroidHostTest") && it.name.contains("lint", ignoreCase = true)
}.configureEach {
    dependsOn(tasks.matching { it.name == "kspAndroidHostTest" })
}
