package us.wangxy.voicebook.reader.api

import kotlinx.serialization.Serializable

/**
 * calibre-web 服务器连接配置，在书城设置界面填写后持久化。
 *
 * [viaBff] 为 true 时表示「已登录统一账号，书库经 BFF 访问」：[baseUrl] 是 BFF 地址，
 * 认证用登录会话的 Bearer token，用户名密码为空。这种实例只在运行时生成，从不落盘。
 */
@Serializable
data class CalibreServer(
    val baseUrl: String,
    val username: String = "",
    val password: String = "",
    val viaBff: Boolean = false,
) {
    val root: String get() = baseUrl.trimEnd('/')
}
