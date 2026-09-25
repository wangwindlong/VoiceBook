import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
