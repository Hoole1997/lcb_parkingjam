plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

val analyticsConfig = findProperty("analytics") as? Map<*, *>

android {
    namespace = "net.corekit.metrics"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        buildConfigField("String", "ADJUST_APP_TOKEN", "\"${analyticsConfig?.get("adjustAppToken") ?: ""}\"")
        buildConfigField("String", "THINKING_DATA_APP_ID", "\"${analyticsConfig?.get("thinkingDataAppId") ?: ""}\"")
        buildConfigField("String", "THINKING_DATA_SERVER_URL", "\"${analyticsConfig?.get("thinkingDataServerUrl") ?: ""}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
    
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("com.github.toukaremax:core:1.0.11")
    
    // Adjust SDK
    api("com.adjust.sdk:adjust-android:5.4.3")
    api("com.android.installreferrer:installreferrer:2.2")
    api("com.google.android.gms:play-services-ads-identifier:18.0.1")
    
    // ThinkingData SDK
    api("cn.thinkingdata.android:ThinkingAnalyticsSDK:3.0.2")
}
