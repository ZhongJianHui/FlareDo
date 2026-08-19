import dev.dimension.flare.buildlogic.flare

plugins {
    id("dev.dimension.flare.android-application")
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.screenshot)
}

flare {
    namespace = "io.github.zhongjianhui.flaredo"
    applicationId = "io.github.zhongjianhui.flaredo"
}

android {
    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    defaultConfig {
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // Release signing and distribution are intentionally outside the source tree.
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(project.dependencies.platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(projects.composeUi)
    implementation(projects.shared)
    implementation(projects.social.discourse)

    screenshotTestImplementation(libs.screenshot.validation.api)
    screenshotTestImplementation(libs.compose.ui.tooling)

    // The shared Android convention enables core library desugaring for every app variant.
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
