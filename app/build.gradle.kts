import dev.dimension.flare.buildlogic.flare

plugins {
    id("dev.dimension.flare.android-application")
    alias(libs.plugins.compose.compiler)
}

flare {
    namespace = "io.github.zhongjianhui.flaredo"
    applicationId = "io.github.zhongjianhui.flaredo"
}

android {
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
    implementation(projects.composeUi)

    // The shared Android convention enables core library desugaring for every app variant.
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
