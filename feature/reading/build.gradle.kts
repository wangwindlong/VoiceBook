import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
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
        namespace = "us.wangxy.voicebook.feature.reading"
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
        }

        jvmTest.dependencies {
            implementation(libs.ktor.client.okhttp)
            // Skia natives so PdfDocumentTest can decode bitmaps outside the Compose
            // desktop runtime; keep in sync with the skiko version compose pulls in.
            runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-linux-x64:0.150.1")
        }

        commonMain.dependencies {
            implementation(projects.core.model)
            implementation(projects.core.base)
            implementation(projects.core.network)
            implementation(projects.core.data)
            implementation(projects.core.design)
            implementation(libs.koin.core)
            implementation(libs.koin.compose.viewmodel)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor)
            implementation(libs.paging.common)
            implementation(libs.paging.compose)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.material.icons.core)
            implementation(libs.compose.adaptive)
            implementation(libs.compose.adaptive.layout)
            implementation(libs.compose.adaptive.navigation)
            implementation(libs.androidx.window.core)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        // pagecurl 只有 Android 工件,ReaderPagerSurface 的卷页 actual 在这里用
        androidMain.dependencies {
            implementation(libs.pagecurl)
        }
        jvmMain.dependencies {
            implementation(libs.pdfbox)
        }
    }
}
