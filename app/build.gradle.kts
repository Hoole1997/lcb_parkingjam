import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.gms.google.services)
    alias(libs.plugins.google.firebase.crashlytics)
}

@Suppress("UNCHECKED_CAST")
fun extraMap(name: String): Map<String, Any?> {
    return (rootProject.findProperty(name) as? Map<*, *>)
        ?.mapKeys { it.key.toString() }
        ?: emptyMap()
}

@Suppress("UNCHECKED_CAST")
fun Map<String, Any?>.nestedMap(name: String): Map<String, Any?> {
    return (this[name] as? Map<*, *>)
        ?.mapKeys { it.key.toString() }
        ?: emptyMap()
}

fun Map<String, Any?>.stringValue(name: String, defaultValue: String = ""): String {
    return this[name]?.toString() ?: defaultValue
}

fun Map<String, Any?>.intValue(name: String, defaultValue: Int): Int {
    return when (val value = this[name]) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: defaultValue
        else -> defaultValue
    }
}

fun booleanGradleProperty(name: String, defaultValue: Boolean): Boolean {
    return providers.gradleProperty(name).orNull?.let { value ->
        when {
            value.equals("true", ignoreCase = true) -> true
            value.equals("false", ignoreCase = true) -> false
            else -> defaultValue
        }
    } ?: defaultValue
}

fun secretValue(name: String): String {
    return rootProject.findProperty(name)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv(name)?.trim().orEmpty()
}

/** Converts configurable project metadata into a portable artifact file-name segment. */
fun String.toArtifactNameSegment(fallback: String): String {
    return trim()
        .replace(Regex("[^A-Za-z0-9._-]+"), "_")
        .trim('_', '.', '-')
        .ifEmpty { fallback }
}

fun resolveSigningFile(path: String) = file(path).takeIf { it.isAbsolute } ?: rootProject.file(path)

fun googleServicesPackageName(flavor: String): String? {
    val servicesFile = file("src/$flavor/google-services.json")
    if (!servicesFile.isFile) return null

    val json = groovy.json.JsonSlurper().parse(servicesFile) as? Map<*, *> ?: return null
    val clients = json["client"] as? List<*> ?: return null
    val clientInfo = (clients.firstOrNull() as? Map<*, *>)?.get("client_info") as? Map<*, *> ?: return null
    val androidInfo = clientInfo["android_client_info"] as? Map<*, *> ?: return null
    return androidInfo["package_name"]?.toString()
}

val appConfig = extraMap("app")
val analyticsConfig = extraMap("analytics")
val adMobConfig = extraMap("admob")
val adMobUnitConfig = adMobConfig.nestedMap("adUnitIds")
val gamConfig = extraMap("gam")
val gamUnitConfig = gamConfig.nestedMap("adUnitIds")
val pangleConfig = extraMap("pangle")
val pangleUnitConfig = pangleConfig.nestedMap("adUnitIds")
val toponConfig = extraMap("topon")
val toponUnitConfig = toponConfig.nestedMap("adUnitIds")

val resolvedVersionName = appConfig.stringValue("versionName", "1.0.0")
val privacyPolicyUrl = appConfig.stringValue(
    "privacyPolicyUrl",
    "https://naznotechnology.com/Policy.html",
)
val googleReleaseKeystorePath = secretValue("ANDROID_SIGNING_STORE_FILE")
val googleReleaseKeystoreFile = if (googleReleaseKeystorePath.isNotEmpty()) {
    resolveSigningFile(googleReleaseKeystorePath)
} else {
    file("src/google/google-release.keystore")
}
val googleReleaseStorePassword = secretValue("ANDROID_SIGNING_STORE_PASSWORD").ifEmpty { "google123456" }
val googleReleaseKeyAlias = secretValue("ANDROID_SIGNING_KEY_ALIAS").ifEmpty { "google" }
val googleReleaseKeyPassword = secretValue("ANDROID_SIGNING_KEY_PASSWORD").ifEmpty { "google123456" }
val hasGoogleReleaseSigning = googleReleaseKeystoreFile.isFile &&
    googleReleaseKeystoreFile.length() > 0L &&
    googleReleaseStorePassword.isNotEmpty() &&
    googleReleaseKeyAlias.isNotEmpty() &&
    googleReleaseKeyPassword.isNotEmpty()
val googleReleaseArtifactTaskPrefixes = listOf("assemble", "bundle", "package", "publish")
val requiresGoogleReleaseSigning = gradle.startParameter.taskNames.any { taskName ->
    val simpleTaskName = taskName.substringAfterLast(':').lowercase()
    // Metadata helper tasks also contain "GoogleRelease" in their names but do not sign output.
    simpleTaskName.contains("googlerelease") &&
        googleReleaseArtifactTaskPrefixes.any(simpleTaskName::startsWith)
}
val artifactProjectName = rootProject.name.toArtifactNameSegment("android_app")
val artifactVersionName = resolvedVersionName.toArtifactNameSegment("unknown")
// UTC keeps artifact names unambiguous across local machines and GitHub-hosted runners.
val artifactTimestampUtc = DateTimeFormatter
    .ofPattern("yyyyMMdd_HHmmss")
    .withZone(ZoneOffset.UTC)
    .format(Instant.now())
val googleReleaseAabName =
    "${artifactProjectName}_google_release_v${artifactVersionName}_${artifactTimestampUtc}_UTC.aab"
val releaseMinifyEnabled = booleanGradleProperty("android.release.minifyEnabled", true)
val releaseShrinkResourcesEnabled = booleanGradleProperty("android.release.shrinkResourcesEnabled", false)
val releaseOptimizeEnabled = booleanGradleProperty("android.release.optimizeEnabled", releaseMinifyEnabled)
val releaseDefaultProguardFile = if (releaseMinifyEnabled && releaseOptimizeEnabled) {
    "proguard-android-optimize.txt"
} else {
    "proguard-android.txt"
}
val fallbackApplicationId = appConfig.stringValue("applicationId", "com.example.lcb.app")
val googleApplicationId = googleServicesPackageName("google") ?: fallbackApplicationId
val localApplicationId = googleServicesPackageName("local") ?: fallbackApplicationId

android {
    namespace = "com.example.lcb.app"
    compileSdk = 36

    defaultConfig {
        minSdk = appConfig.intValue("minSdk", 26)
        targetSdk = appConfig.intValue("targetSdk", 35)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val defaultChannel = analyticsConfig.stringValue("defaultUserChannel", "default")
        buildConfigField("String", "DEFAULT_USER_CHANNEL", "\"$defaultChannel\"")
        buildConfigField("String", "PRIVACY_POLICY_URL", "\"$privacyPolicyUrl\"")

        manifestPlaceholders["ADMOB_APPLICATION_ID"] = adMobConfig.stringValue("applicationId")

        buildConfigField("String", "ADMOB_APPLICATION_ID", "\"${adMobConfig.stringValue("applicationId")}\"")
        buildConfigField("String", "ADMOB_SPLASH_ID", "\"${adMobUnitConfig.stringValue("splash")}\"")
        buildConfigField("String", "ADMOB_BANNER_ID", "\"${adMobUnitConfig.stringValue("banner")}\"")
        buildConfigField("String", "ADMOB_INTERSTITIAL_ID", "\"${adMobUnitConfig.stringValue("interstitial")}\"")
        buildConfigField("String", "ADMOB_NATIVE_ID", "\"${adMobUnitConfig.stringValue("native")}\"")
        buildConfigField("String", "ADMOB_FULL_NATIVE_ID", "\"${adMobUnitConfig.stringValue("full_native")}\"")
        buildConfigField("String", "ADMOB_REWARDED_ID", "\"${adMobUnitConfig.stringValue("rewarded")}\"")

        buildConfigField("String", "GAM_SPLASH_ID", "\"${gamUnitConfig.stringValue("splash")}\"")
        buildConfigField("String", "GAM_BANNER_ID", "\"${gamUnitConfig.stringValue("banner")}\"")
        buildConfigField("String", "GAM_INTERSTITIAL_ID", "\"${gamUnitConfig.stringValue("interstitial")}\"")
        buildConfigField("String", "GAM_NATIVE_ID", "\"${gamUnitConfig.stringValue("native")}\"")
        buildConfigField("String", "GAM_FULL_NATIVE_ID", "\"${gamUnitConfig.stringValue("full_native")}\"")
        buildConfigField("String", "GAM_REWARDED_ID", "\"${gamUnitConfig.stringValue("rewarded")}\"")

        buildConfigField("String", "PANGLE_APPLICATION_ID", "\"${pangleConfig.stringValue("applicationId")}\"")
        buildConfigField("String", "PANGLE_SPLASH_ID", "\"${pangleUnitConfig.stringValue("splash")}\"")
        buildConfigField("String", "PANGLE_BANNER_ID", "\"${pangleUnitConfig.stringValue("banner")}\"")
        buildConfigField("String", "PANGLE_INTERSTITIAL_ID", "\"${pangleUnitConfig.stringValue("interstitial")}\"")
        buildConfigField("String", "PANGLE_NATIVE_ID", "\"${pangleUnitConfig.stringValue("native")}\"")
        buildConfigField("String", "PANGLE_FULL_NATIVE_ID", "\"${pangleUnitConfig.stringValue("full_native")}\"")
        buildConfigField("String", "PANGLE_REWARDED_ID", "\"${pangleUnitConfig.stringValue("rewarded")}\"")

        buildConfigField("String", "TOPON_APPLICATION_ID", "\"${toponConfig.stringValue("applicationId")}\"")
        buildConfigField("String", "TOPON_APP_KEY", "\"${toponConfig.stringValue("appKey")}\"")
        buildConfigField("String", "TOPON_INTERSTITIAL_ID", "\"${toponUnitConfig.stringValue("interstitial")}\"")
        buildConfigField("String", "TOPON_REWARDED_ID", "\"${toponUnitConfig.stringValue("rewarded")}\"")
        buildConfigField("String", "TOPON_NATIVE_ID", "\"${toponUnitConfig.stringValue("native")}\"")
        buildConfigField("String", "TOPON_SPLASH_ID", "\"${toponUnitConfig.stringValue("splash")}\"")
        buildConfigField("String", "TOPON_FULL_NATIVE_ID", "\"${toponUnitConfig.stringValue("full_native")}\"")
        buildConfigField("String", "TOPON_BANNER_ID", "\"${toponUnitConfig.stringValue("banner")}\"")
    }

    flavorDimensions += "channel"

    productFlavors {
        create("google") {
            dimension = "channel"
            applicationId = googleApplicationId
            versionCode = appConfig.intValue("versionCode", 1)
            versionName = resolvedVersionName
        }

        create("local") {
            dimension = "channel"
            applicationId = localApplicationId
            versionCode = appConfig.intValue("versionCode", 1)
            versionName = "$resolvedVersionName-local"
            isDefault = true
        }
    }

    signingConfigs {
        create("googleRelease") {
            if (hasGoogleReleaseSigning) {
                storeFile = googleReleaseKeystoreFile
                storePassword = googleReleaseStorePassword
                keyAlias = googleReleaseKeyAlias
                keyPassword = googleReleaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isShrinkResources = false
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = releaseMinifyEnabled
            isShrinkResources = releaseMinifyEnabled && releaseShrinkResourcesEnabled
            if (hasGoogleReleaseSigning || requiresGoogleReleaseSigning) {
                signingConfig = signingConfigs.getByName("googleRelease")
            }
            if (requiresGoogleReleaseSigning && !hasGoogleReleaseSigning) {
                throw GradleException(
                    "Missing google release signing config. Ensure app/src/google/google-release.keystore exists or set " +
                        "ANDROID_SIGNING_STORE_FILE, ANDROID_SIGNING_STORE_PASSWORD, ANDROID_SIGNING_KEY_ALIAS, " +
                        "and ANDROID_SIGNING_KEY_PASSWORD."
                )
            }
            proguardFiles(
                getDefaultProguardFile(releaseDefaultProguardFile),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.version",
            )
        }
    }
}

tasks.register("printGoogleReleaseVersionName") {
    group = "help"
    description = "Prints the versionName used for google release builds."
    doLast {
        println(resolvedVersionName)
    }
}

tasks.register("printGoogleReleaseAabName") {
    group = "help"
    description = "Prints the expected output file name for google release AAB builds."
    doLast {
        println(googleReleaseAabName)
    }
}

configurations.configureEach {
    exclude(group = "com.google.firebase", module = "protolite-well-known-types")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.config)
    implementation(libs.firebase.messaging)
    implementation(libs.firebase.crashlytics)
    implementation(libs.gson)
    implementation(libs.glide)
    implementation("androidx.cardview:cardview:1.0.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

//    implementation(project(":bill"))
//    implementation(project(":core"))
    implementation(project(":metrics"))
    implementation(project(":feature-game"))
    implementation("com.github.toukaremax:core:1.0.15")
    implementation("com.github.toukaremax:bill:1.0.48") {
        // Launcher SDK provides com.unity3d.ads-mediation:mediation-sdk:9.2.0.
        // Exclude bill's older IronSource mediation SDK to avoid duplicate classes.
        exclude(group = "com.ironsource.sdk", module = "mediationsdk")
    }
    // 两个 Launcher SDK 含有相同包名的混淆类，必须按渠道隔离，不能同时进入一个 variant。
    add("googleImplementation", "com.launcher.unity:com.parkingjam.carpuzzle-release:1.0.2")
    add("localImplementation", "com.launcher.unity:com.leafmotivation.quizguessoncolor-ParkingJam:1.0.1")
}
