import dev.dimension.flare.buildlogic.FlarePlatform
import dev.dimension.flare.buildlogic.flare
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    id("dev.dimension.flare.multiplatform-library")
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    flare {
        namespace = "dev.dimension.flare.apple.shared"
        platforms(
            FlarePlatform.IOS,
            FlarePlatform.MACOS,
        )
    }

    listOf("iosArm64", "iosSimulatorArm64", "macosArm64")
        .map { targetName -> targets.getByName(targetName) as KotlinNativeTarget }
        .forEach { appleTarget ->
            appleTarget.binaries.framework {
                baseName = "KotlinSharedUI"
                isStatic = true
                export(projects.shared)
                export(projects.social.discourse)

                if (appleTarget.name.startsWith("macos")) {
                    linkerOpts.add("-lsqlite3")
                }
            }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.shared)
            api(projects.social.discourse)
        }
    }
}
