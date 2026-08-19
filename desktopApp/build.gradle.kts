import io.github.kdroidfilter.nucleus.desktop.application.dsl.AppImageCategory
import io.github.kdroidfilter.nucleus.desktop.application.dsl.CompressionLevel
import io.github.kdroidfilter.nucleus.desktop.application.dsl.TargetFormat
import java.util.Properties
import org.jetbrains.compose.compose

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.nucleus)
}

dependencies {
    implementation(projects.composeUi)
    implementation(projects.shared)
    implementation(projects.social.discourse)
    implementation(project.dependencies.platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(compose.desktop.currentOs)
    implementation(compose("org.jetbrains.compose.components:components-resources"))

    testImplementation(kotlin("test"))
}

val fdroidProperties =
    Properties().apply {
        rootProject.file("fdroid.properties").inputStream().use(::load)
    }
val desktopVersionName =
    System.getenv("BUILD_VERSION")?.takeIf { version ->
        Regex("""\d+\.\d+\.\d+""").matches(version)
    } ?: fdroidProperties.getProperty("versionName") ?: "1.0.0"

nucleus.application {
    mainClass = "dev.dimension.flare.MainKt"
    nativeDistributions {
        cleanupNativeLibs = true
        modules("jdk.localedata")
        homepage = "https://github.com/ZhongJianHui/FlareDo"
        compressionLevel = CompressionLevel.Store
        targetFormats(
            TargetFormat.AppImage,
            TargetFormat.AppX,
        )
        packageName = "FlareDo"
        packageVersion = desktopVersionName
        artifactName = $$"FlareDo-$${desktopVersionName}.${ext}"

        windows {
            iconFile.set(project.file("resources/ic_launcher.ico"))
            appx {
                applicationId = "FlareDo"
                publisherDisplayName = "FlareDo Contributors"
                displayName = "FlareDo"
                publisher = "CN=FlareDo"
                identityName = "io.github.zhongjianhui.flaredo"
                languages = listOf("en-US", "zh-CN")
                backgroundColor = "#087F73"
                showNameOnTiles = true
                minVersion = "10.0.17763.0"
                capabilities = listOf("runFullTrust")

                storeLogo.set(project.file("resources/appx/StoreLogo.scale-100.png"))
                square44x44Logo.set(project.file("resources/appx/Square44x44Logo.scale-100.png"))
                square150x150Logo.set(project.file("resources/appx/Square150x150Logo.scale-100.png"))
                wide310x150Logo.set(project.file("resources/appx/Wide310x150Logo.scale-100.png"))
            }
        }
        linux {
            iconFile.set(project.file("resources/ic_launcher.png"))
            appCategory = "Network"
            appImage {
                category = AppImageCategory.Network
                genericName = "FlareDo"
            }
        }
    }
}

compose.resources {
    packageOfResClass = "dev.dimension.flare"
}
