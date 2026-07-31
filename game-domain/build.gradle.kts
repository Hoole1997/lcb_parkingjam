plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    // Compile Java 17 bytecode while allowing Android Studio's bundled JBR to run Gradle.
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.junit)
}
