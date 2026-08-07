import java.util.Properties

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

val buildConfigFile = file("build.config.properties")
val buildConfig = Properties()
if (buildConfigFile.exists()) {
    buildConfig.load(buildConfigFile.inputStream())
}

// 兼容本地配置、专用 CI 密钥以及 LocationSharing 使用的 GitHub Actions 密钥命名。
// 明确的 Launcher SDK 密钥优先，旧工作流仍可通过 PACKAGES_READ_TOKEN 正常拉取依赖。
val launcherSdkGithubUser = buildConfig.getProperty("github.user")
    ?: System.getenv("LAUNCHER_SDK_GITHUB_USER")
    ?: System.getenv("GITHUB_ACTOR")
val launcherSdkGithubToken = buildConfig.getProperty("github.token")
    ?: System.getenv("LAUNCHER_SDK_GITHUB_TOKEN")
    ?: System.getenv("PACKAGES_READ_TOKEN")
    ?: System.getenv("GITHUB_TOKEN")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://artifact.bytedance.com/repository/pangle/")
        maven("https://repo.itextsupport.com/android")
        maven("https://repo.dgtverse.cn/repository/maven-public/")
        maven("https://dl-maven-android.mintegral.com/repository/mbridge_android_sdk_oversea")
        maven("https://android-sdk.is.com/")
        maven("https://jfrog.anythinktech.com/artifactory/overseas_sdk")
        maven("https://artifacts.applovin.com/android")
        maven("https://repo.dgtverse.cn/repository/maven-public")
        maven {
            url = uri("https://maven.pkg.github.com/toukaRemax/remax_sdk")
            credentials {
                username = launcherSdkGithubUser
                password = launcherSdkGithubToken
            }
        }
    }
}

rootProject.name = "LCB_ParkingJam"
include(":app")
//include(":bill")
//include(":core")
include(":metrics")
include(":game-domain")
include(":game-data")
include(":feature-game")
