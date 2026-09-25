import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(projects.shared)

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.swing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "us.wangxy.voicebook.MainKt"
        // Desktop sherpa-onnx JNI runtime (gitignored; see voice/jvmMain JvmSherpa).
        jvmArgs.add(
            "-Djava.library.path=${rootProject.file("desktopApp/native/sherpa-onnx-linux-x64/lib").absolutePath}",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "us.wangxy.voicebook"
            packageVersion = "1.0.0"
        }
    }
}
