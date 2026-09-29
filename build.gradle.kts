plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinxSerialization) apply false
}

import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// 统一全部 KMP 模块的 JVM toolchain 为 17：:server 用 jvmToolchain(17)，各模块的 jvmTest
// 也在 JVM 17 上运行。任一模组若用更高字节码（默认 JDK 21 = class 65）会被 17 加载不了，
// 抛 UnsupportedClassVersionError。与 server 保持一致。
// 注意：androidLibrary 的 jvmTarget 各自单独声明（JVM_11），此处只约束共用 jvm() target。
subprojects {
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension> {
            jvmToolchain(17)
        }
    }
}
