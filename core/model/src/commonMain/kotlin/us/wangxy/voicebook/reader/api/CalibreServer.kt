package us.wangxy.voicebook.reader.api

import kotlinx.serialization.Serializable

/** calibre-web 服务器连接配置，在书城设置界面填写后持久化。 */
@Serializable
data class CalibreServer(
    val baseUrl: String,
    val username: String = "",
    val password: String = "",
) {
    val root: String get() = baseUrl.trimEnd('/')
}
