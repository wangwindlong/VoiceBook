package us.wangxy.voicebook

import us.wangxy.voicebook.di.initKoin

/**
 * iOS / Objective-C 侧的 Koin 启动入口。
 *
 * Kotlin 的默认参数值不会导出到 Objective-C：`di.initKoin(voiceConfig, platformModules, debugNetworkLogging)`
 * 在 Swift 里必须显式传满所有参数，而 `VoiceConfig` 的默认值（尤其 [modelBaseUrls] 里
 * 内置的镜像列表）不适合在 Swift 侧再抄一遍，否则两处默认值会各自漂移。
 *
 * 因此这里包一层无参重载。Kotlin/Native 会给以 init 开头的导出方法加 `do` 前缀，
 * iOS 侧实际调用 `KoinIosKt.doInitKoinIos()`，默认值仍由 commonMain 的 Kotlin 定义单点持有。
 */
fun initKoinIos() {
    initKoin(debugNetworkLogging = kotlin.native.Platform.isDebugBinary)
}
