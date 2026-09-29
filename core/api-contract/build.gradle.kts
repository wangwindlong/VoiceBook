import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Wire contract shared by the BFF (:server) and the app's network layer.
// Keep it dependency-free apart from kotlinx-serialization.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64())

    jvm()

    // :server 用 jvmToolchain(17)；wire contract 的 JVM 产物必须同字节码较旧，否则 server 测试
    // (Java 17) 加载会报 UnsupportedClassVersionError（class 65）。
    jvmToolchain(17)

    js { browser() }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    androidLibrary {
        namespace = "us.wangxy.voicebook.bff.contract"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
    }
}
