import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

// Desktop sherpa-onnx JNI runtime for the JVM tests (Android resolves it from the APK instead).
// The engine classes load "sherpa-onnx-jni" from static initializers → must be startup-time.
tasks.withType<Test>().configureEach {
    val nativeLib = rootProject.file("desktopApp/native/sherpa-onnx-linux-x64/lib").absolutePath
    jvmArgs("-Djava.library.path=$nativeLib")
}

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    jvm()

    js {
        browser()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    androidLibrary {
        namespace = "us.wangxy.voicebook.voice"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.koin.core)
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // Reference bzip2/tar writer for the archive decoder tests.
            implementation(libs.commons.compress)
        }
        androidMain {
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies {
                implementation(libs.ktor.client.okhttp)
                // sherpa-onnx 1.13.6 Kotlin API (JNI variant). Android JNI .so files live in
                // androidApp/src/main/jniLibs; desktop Linux ones under desktopApp/native (see JvmSherpa).
                implementation(files("libs/sherpa-classes.jar"))
            }
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        jvmMain {
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies {
                implementation(libs.ktor.client.okhttp)
                // android-stubs: assetManager parameter types in sherpa-classes.jar need the
                // android.content classes on the compile classpath; we only ever pass null.
                implementation(files("libs/sherpa-classes.jar", "libs/android-stubs.jar"))
            }
        }
        jvmTest.dependencies {
            implementation(files("libs/sherpa-classes.jar", "libs/android-stubs.jar"))
        }
        webMain.dependencies {
            implementation(libs.ktor.client.js)
        }
    }
}

// ---------------------------------------------------------------- iOS cinterop
// Kotlin/Native 加载不了 JVM 的 JNI 库，Apple 侧走 sherpa-onnx C API：由
// scripts/build_sherpa_ios.sh（需 macOS + Xcode；deps 子命令任意平台可跑）产出
// xcframework + c-api.h 到 voice/ios/sherpa-onnx-<ver>-ios/，这里做 cinterop 接线。
//
// 未产出时整段跳过，并把依赖生成包的 SherpaCapiBridge.kt 从 iosMain 排除——保证
// Linux/CI 可编译（Kotlin/Native 在非 macOS 上本来也会静默 SKIP framework 链接）。
// 产出后重新 sync 即自动生效，无需改代码。
val sherpaVer = "1.13.8"
val sherpaAppleDir = rootProject.file("voice/ios/sherpa-onnx-$sherpaVer-ios")
val sherpaAppleHeader = sherpaAppleDir.resolve("c-api.h")
val sherpaAppleXcfw = sherpaAppleDir.resolve("sherpa-onnx.xcframework")
val sherpaAppleReady = sherpaAppleHeader.exists() && sherpaAppleXcfw.exists()

if (sherpaAppleReady) {
    logger.lifecycle("voice: 检测到 iOS xcframework，启用 sherpa cinterop（$sherpaAppleDir）")

    kotlin.targets.withType<KotlinNativeTarget>().configureEach {
        val slice = when (name) {
            "iosArm64" -> "ios-arm64"
            "iosSimulatorArm64" -> "ios-arm64_x86_64-simulator"
            else -> return@configureEach
        }
        val libDir = sherpaAppleXcfw.resolve("$slice/SherpaOnnxC.framework")

        compilations.getByName("main").cinterops.create("sherpaOnnx") {
            defFile(project.file("src/nativeInterop/cinterop/sherpa-onnx.def"))
            includeDirs(sherpaAppleDir)
            // cinterop DSL 没有 libraryPaths 属性，用底层参数让绑定生成时能定位库；
            // 真正把静态库带进最终链接的是 shared 模块 framework 的 linkerOpts。
            extraOpts("-libraryPath", libDir.absolutePath)
        }
    }
} else {
    // SherpaCapiBridge.kt 依赖 cinterop 生成的 us.wangxy.voicebook.voice.capi 包，
    // 未启用 cinterop 时该包不存在，必须排除，否则 iOS 侧编译失败。
    kotlin.sourceSets.named("iosMain") {
        kotlin.exclude("**/SherpaCapiBridge.kt")
    }
    logger.lifecycle(
        "voice: 未发现 voice/ios/sherpa-onnx-$sherpaVer-ios/，iOS 无本地推理（云端兜底）",
    )
}
