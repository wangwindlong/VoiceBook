import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.kotlinxSerialization)
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64())

    jvm()

    js { browser() }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    androidLibrary {
        namespace = "us.wangxy.voicebook.core.data"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }
    // Manual dependsOn edges below switch off the implicit template; re-apply it so
    // js/wasmJs keep their webMain grouping.
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.kotlinx.coroutines.test)
        }

        commonMain.dependencies {
            implementation(projects.core.model)
            implementation(projects.core.base)
            implementation(libs.koin.core)
            implementation(projects.core.network)
            implementation(projects.core.design)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.io.core)
            implementation(libs.material.kolor)
            implementation(libs.paging.common)
        }

        // SQLDelight has no web driver; the schema lives in the 3-target :db module and
        // is only consumed by this intermediate source set (android/ios/jvm). The web
        // targets (webMain) provide their own localStorage LocalLibrary actual.
        val dbMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain.get().dependsOn(dbMain)
        iosMain.get().dependsOn(dbMain)
        jvmMain.get().dependsOn(dbMain)

        dbMain.dependencies {
            implementation(projects.db)
            implementation(libs.sqldelight.coroutines)
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
            implementation(libs.sqlite.android)
            implementation(libs.androidx.core.ktx)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
        jvmMain.dependencies {
            implementation(libs.sqldelight.sqlite.driver)
        }
        webMain.dependencies {
            implementation(libs.kotlinx.browser)
        }
    }
}
