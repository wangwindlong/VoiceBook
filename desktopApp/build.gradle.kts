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

// 把 sherpa 原生库放进分发包的 lib/app/ —— jpackage 启动器已把该目录加入
// java.library.path（实测），放这里即可被 System.loadLibrary 找到。
// 不这么做的话，分发包运行时只会看到上面 jvmArgs 里的源码目录绝对路径，
// 本机开发能跑、换机或在别的目录启动即 UnsatisfiedLinkError。
val copySherpaNativesToDist by tasks.registering(Copy::class) {
    description = "把 sherpa JNI 原生库复制进 app-image 的 lib/app/"
    val natives = rootProject.file("desktopApp/native/sherpa-onnx-linux-x64/lib")
    from(natives) {
        include("*.so")
    }
    into(layout.buildDirectory.dir("compose/binaries/main/app/us.wangxy.voicebook/lib/app"))
    // native/ 是 gitignored 的自备目录（从 sherpa-onnx releases 解压）；没准备过的
    // 机器上跳过拷贝，包里没有本地推理，走云端兜底，构建不应失败。
    onlyIf { natives.isDirectory }
    doLast {
        logger.lifecycle("已复制 sherpa 原生库到分发包 lib/app/")
    }
}

// 注意：compose 插件在配置阶段之后才注册 createDistributable，
// 不能用 tasks.named(...)（配置期直接报 Task not found），用 matching + configureEach 挂接。
tasks.matching { it.name == "createDistributable" }.configureEach {
    finalizedBy(copySherpaNativesToDist)
}
