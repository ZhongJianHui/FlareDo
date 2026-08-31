import dev.dimension.flare.buildlogic.FlarePlatform
import dev.dimension.flare.buildlogic.flare
import org.jetbrains.compose.compose

plugins {
    id("dev.dimension.flare.multiplatform-library")
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
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
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            implementation(libs.navigation3.runtime)
        }
        androidMain.dependencies {
            implementation(libs.activity.compose)
            implementation(libs.compose.material3.adaptive)
            implementation(libs.compose.material3.adaptive.navigation3)
            implementation(libs.lifecycle.runtime.compose)
            implementation(libs.navigation3.ui)
            implementation(libs.play.services.code.scanner)
            implementation(libs.zxing.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            // Compose's desktop test host renders into an offscreen Skiko surface. This keeps the
            // semantic and pixel-contract tests runnable on CI without an X server or emulator.
            implementation(libs.compose.ui.test)
            implementation(libs.kotlinx.coroutines.test)
            runtimeOnly(compose.desktop.currentOs)
        }
        jvmMain.dependencies {
            implementation(libs.composewebview)
            implementation(libs.nucleus.application)
            implementation(libs.zxing.core)
            implementation(libs.zxing.javase)
        }
    }
}

compose.resources {
    packageOfResClass = "dev.dimension.flare.compose.ui"
    generateResClass = always
}
