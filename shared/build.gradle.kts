import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

// Assembly module: root App composable, navigation and the Koin wiring that stitches
// the core:* and feature:* modules together. All business code lives in the modules.
kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true

            // voice 模块的 sherpa cinterop 接上后（voice/ios/sherpa-onnx-1.13.8-ios/
            // 就位），最终链接必须带上 SherpaOnnxC 静态库与所需系统框架。静态库由
            // scripts/build_sherpa_ios.sh 用 libtool 合并 12 个中间库而成，位于
            // xcframework 对应切片内；isStatic=true 所以整个烧进 Shared framework。
            val xcfw = rootProject.file("voice/ios/sherpa-onnx-1.13.8-ios/sherpa-onnx.xcframework")
            if (xcfw.exists()) {
                val slice =
                    if (iosTarget.name == "iosArm64") "ios-arm64" else "ios-arm64_x86_64-simulator"
                val bin = xcfw.resolve("$slice/SherpaOnnxC.framework/SherpaOnnxC")
                if (bin.exists()) {
                    linkerOpts(
                        bin.absolutePath,
                        "-framework", "Foundation",
                        "-framework", "CoreML",
                        "-lc++",
                    )
                }
            }
        }
    }

    jvm()

    js {
        browser()
        // Compose UI test configuration check requires an executable binary for js.
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        // Compose UI test configuration check requires an executable binary for wasmJs.
        binaries.executable()
    }

    androidLibrary {
        namespace = "us.wangxy.voicebook.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.material.icons.extended)
            implementation(libs.paging.common)
            implementation(libs.paging.compose)
            // :voice 是共享引擎 library；App 壳只做 Koin 装配，各 feature 按需直接依赖它。
            implementation(projects.voice)

            implementation(projects.core.model)
            implementation(projects.core.base)
            implementation(projects.core.network)
            implementation(projects.core.data)
            implementation(projects.core.design)
            implementation(projects.core.audio)

            implementation(projects.feature.reading)
            implementation(projects.feature.mine)
            implementation(projects.feature.ai)
            implementation(projects.feature.rss)
            implementation(projects.feature.auth)

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.material.icons.core)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.adaptive)
            implementation(libs.compose.adaptive.layout)
            implementation(libs.compose.adaptive.navigation)
            implementation(libs.androidx.window.core)

            implementation(libs.navigation.compose)
            implementation(libs.androidx.lifecycle.runtimeCompose)

            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}
