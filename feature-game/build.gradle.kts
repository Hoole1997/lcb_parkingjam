plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val npmExecutable = providers.environmentVariable("NPM_EXECUTABLE").orNull
    ?: listOf("/opt/homebrew/bin/npm", "/usr/local/bin/npm")
        .firstOrNull { candidate -> file(candidate).canExecute() }
    ?: "npm"

val caroutDirectory = rootProject.file("carout")
val caroutPackageJson = caroutDirectory.resolve("package.json")
val caroutPackageLock = caroutDirectory.resolve("package-lock.json")

/**
 * 为网页运行时安装锁文件确定的依赖。将 node_modules 内 npm 生成的锁文件声明为输出，
 * 避免每次 Android 构建重复安装，同时保证干净的 CI Runner 可以自包含地完成构建。
 */
val installCaroutWebDependencies by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Installs the locked carout web dependencies required by the Android build."
    workingDir(caroutDirectory)
    commandLine(npmExecutable, "ci", "--include=dev", "--no-audit", "--no-fund")
    inputs.files(caroutPackageJson, caroutPackageLock)
    outputs.file(caroutDirectory.resolve("node_modules/.package-lock.json"))
}

val buildCaroutWeb by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the local carout Canvas runtime before Android assets are merged."
    dependsOn(installCaroutWebDependencies)
    workingDir(caroutDirectory)
    commandLine(npmExecutable, "run", "build")
    inputs.files(
        caroutPackageJson,
        caroutPackageLock,
        caroutDirectory.resolve("tsconfig.json"),
        caroutDirectory.resolve("vite.config.ts"),
        caroutDirectory.resolve("index.html"),
    )
    inputs.dir(caroutDirectory.resolve("src"))
    inputs.dir(caroutDirectory.resolve("scripts"))
    inputs.dir(caroutDirectory.resolve("public"))
    outputs.dir(caroutDirectory.resolve("dist"))
}

tasks.matching { task -> task.name.startsWith("merge") && task.name.endsWith("Assets") }
    .configureEach { dependsOn(buildCaroutWeb) }
tasks.matching { task -> task.name.contains("Lint", ignoreCase = true) }
    .configureEach { dependsOn(buildCaroutWeb) }

android {
    namespace = "com.example.lcb.parking.feature"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        viewBinding = false
        compose = true
    }

    // carout 的 Vite 构建产物直接打进 AAR，避免复制资源产生两份事实来源。
    sourceSets["main"].assets.srcDir(caroutDirectory.resolve("dist"))
    // 原生旧玩法已归档到 src/legacyNative，不进入产物；carout 是唯一运行时规则源。

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.customview)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.webkit)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.material3)
    // Public host Views inherit AbstractComposeView, so consumers need this ABI type on classpath.
    api(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.google.material)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.kotlinxCoroutines.get()}")
}
