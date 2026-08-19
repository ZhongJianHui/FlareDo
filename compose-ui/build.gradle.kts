import dev.dimension.flare.buildlogic.FlarePlatform
import dev.dimension.flare.buildlogic.flare
import org.jetbrains.compose.compose

plugins {
    id("dev.dimension.flare.multiplatform-library")
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.composeMultiplatform)
}

kotlin {
    flare {
        namespace = "dev.dimension.flare.compose.ui"
        platforms(
            FlarePlatform.ANDROID,
            FlarePlatform.JVM,
        )
    }

    android {
        experimentalProperties["android.experimental.kmp.enableAndroidResources"] = true
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared)
            implementation(projects.social.discourse)
            implementation(compose("org.jetbrains.compose.runtime:runtime"))
            implementation(compose("org.jetbrains.compose.foundation:foundation"))
            implementation(compose("org.jetbrains.compose.ui:ui"))
            implementation(libs.compose.material3)
            implementation(compose("org.jetbrains.compose.components:components-resources"))
            implementation(libs.composeIcons.fontAwesome)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

compose.resources {
    packageOfResClass = "dev.dimension.flare.compose.ui"
    generateResClass = always
}
