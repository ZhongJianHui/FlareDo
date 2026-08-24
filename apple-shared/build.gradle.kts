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

                if (appleTarget.name.startsWith("macos")) {
                    linkerOpts.add("-lsqlite3")
                }
            }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared)
            implementation(projects.social.discourse)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("appleMain").dependencies {
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
