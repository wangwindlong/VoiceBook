import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64())

    jvm()

    js {
        browser()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    androidLibrary {
        namespace = "us.wangxy.voicebook.core.design"
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
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
        }

        // jvmTest 里 BloomShapeTest 用到 Skia 的 Path.getBounds()，需要 skiko 原生库
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }

        commonMain.dependencies {
            implementation(libs.koin.core)
            implementation(projects.core.base)
            implementation(libs.material.kolor)
            implementation(libs.compose.components.resources)
            api(libs.compose.ui)
            implementation(libs.compose.animation)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.material.icons.core)
            implementation(libs.kotlinx.coroutines.core)
            // 皮肤数据模型(SkinData)的 JSON 导入导出;版本目录已有,仅 core/design 引入
            implementation(libs.kotlinx.serialization.json)
            // api:TwineMenu 的 panel 作用域签名暴露 compose-unstyled 的类型,消费模块需要可见
            api(libs.compose.unstyled)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }

        androidMain {
            // jvmShared 模式(同 voice 模块):android/desktop 共享的 jvm 系组件放
            // src/jvmShared/kotlin。pagecurl 只有 Android 工件,TwinePageTurn 因此
            // 放在 androidMain;jvmShared 里只能放不依赖 pagecurl 的代码。
            kotlin.srcDir("src/jvmShared/kotlin")
            dependencies {
                implementation(libs.androidx.core.ktx)
                implementation(libs.pagecurl)
            }
        }

        jvmMain {
            kotlin.srcDir("src/jvmShared/kotlin")
        }
    }
}

compose.resources {
    generateResClass = always
    packageOfResClass = "us.wangxy.voicebook.core.design.resources"
    publicResClass = true
}
