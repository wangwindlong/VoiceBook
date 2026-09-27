plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.sqldelight)
}

// Pure SQLDelight schema module. The web targets (js/wasmJs) of :shared use an
// IndexedDB LocalLibrary instead, so this module deliberately has no web targets
// and :shared only consumes it from its dbMain intermediate source set.
kotlin {
    listOf(iosArm64(), iosSimulatorArm64())

    jvm()

    androidLibrary {
        namespace = "us.wangxy.voicebook.db"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    sourceSets {
        commonMain.dependencies {
            // api so consumers of the generated database interface see the runtime types.
            api(libs.sqldelight.runtime)
        }
    }
}

sqldelight {
    databases {
        create("VoiceBookDatabase") {
            packageName.set("us.wangxy.voicebook.db")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
            // FTS5 trigram tokenizer needs sqlite >= 3.34.
            dialect(libs.sqldelight.dialect35)
            verifyMigrations.set(true)
        }
    }
}
