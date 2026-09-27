import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)
    // shared 不再 api(voice)，koin 需显式声明（initKoin 的 platformModule 用到）
    implementation(libs.koin.core)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.uiToolingPreview)
    implementation(libs.compose.foundation)
    implementation(libs.ktor.client.okhttp)
}

android {
    namespace = "us.wangxy.voicebook"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    // 统一签名：debug/release 共用同一 keystore —— 不同机器打的包可直接覆盖安装
    // （含 Android Studio 的 Run ▶，它读 debug signingConfig），避免签名不匹配
    // 被迫卸载丢模型文件。keystore 在仓库外，路径密码从 local.properties 读
    // （gitignored，样例见 local.properties.example）；未配置时回落默认 debug 签名不阻塞构建。
    val signingProps = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val storeFile = signingProps.getProperty("VOICEBOOK_STORE_FILE")?.let { File(it) }
    signingConfigs {
        if (storeFile != null && storeFile.exists()) {
            create("voicebook") {
                this.storeFile = storeFile
                storePassword = signingProps.getProperty("VOICEBOOK_STORE_PASSWORD")
                keyAlias = signingProps.getProperty("VOICEBOOK_KEY_ALIAS")
                keyPassword = signingProps.getProperty("VOICEBOOK_KEY_PASSWORD")
            }
        }
    }
    defaultConfig {
        applicationId = "us.wangxy.voicebook"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
        // requery sqlite-android 自带 4 个 ABI 的 libsqlite3x.so(共 ~6.5MB);装机目标是
        // arm64 真机,只打包 arm64。如需 x86_64 模拟器调试,在列表里加上即可。
        ndk { abiFilters += "arm64-v8a" }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    androidResources {
        noCompress += "7z"
    }
    buildTypes {
        getByName("debug") {
            if (storeFile != null && storeFile.exists()) signingConfig = signingConfigs.getByName("voicebook")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (storeFile != null && storeFile.exists()) signingConfig = signingConfigs.getByName("voicebook")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
