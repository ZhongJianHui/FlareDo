import dev.dimension.flare.buildlogic.FlarePlatform
import dev.dimension.flare.buildlogic.flare

plugins {
    id("dev.dimension.flare.multiplatform-library")
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
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
        withHostTest {
            // Compose runtime reports asynchronous Molecule failures through android.util.Log.
            // Host tests use AGP's mockable SDK, so return defaults instead of masking the original
            // assertion or coroutine failure with a "Log not mocked" exception.
            isReturnDefaultValues = true
        }
        withDeviceTest {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            managedDevices {
                localDevices.create("flaredoApi30") {
                    device = "Pixel 2"
                    apiLevel = 30
                    // ATD images omit UI components that this vault test does not need, reducing
                    // CI boot time and memory while retaining the real Android Keystore service.
                    systemImageSource = "aosp-atd"
                }
            }
        }
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
            implementation(libs.ksoup)
            implementation(libs.compose.runtime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(kotlin("test-junit"))
            implementation(libs.androidx.test.core)
            implementation(libs.androidx.test.runner)
        }
        getByName("androidJvmMain").dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.jna.platform)
        }
        getByName("appleMain").dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

// AGP's host-test lint tasks read KSP output but do not currently wire the producer task.
tasks.matching {
    it.name.contains("AndroidHostTest") && it.name.contains("lint", ignoreCase = true)
}.configureEach {
    dependsOn(tasks.matching { it.name == "kspAndroidHostTest" })
}
