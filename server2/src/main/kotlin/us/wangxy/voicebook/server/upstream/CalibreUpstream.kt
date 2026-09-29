package us.wangxy.voicebook.server.upstream

import io.ktor.client.HttpClient
import io.ktor.server.application.ApplicationCall
import java.util.Base64
import us.wangxy.voicebook.server.ServerConfig

/**
 * calibre (Calibre-Web-Automated) 通道：**反代 OPDS + Basic Auth**。
 *
 * CWA 没有 API token 机制，OPDS 走 HTTP Basic。书库内容是全站共享的，所以本服务
 * 持一个**只读共享账号**即可，不需要每个用户的凭据——个人阅读进度由 App 本地保存。
 *
 * 代理的路径就是 CWA 的 OPDS 路由：
 *   /opds/new、/opds/search/{q}、/opds/cover/{id}、/opds/download/{id}/{FORMAT}
 */
class CalibreUpstream(
    private val config: ServerConfig,
    private val client: HttpClient,
) {
    private val basicAuthHeader: String? by lazy {
        if (config.calibreUsername.isBlank()) return@lazy null
        val raw = "${config.calibreUsername}:${config.calibrePassword}".toByteArray()
        "Basic " + Base64.getEncoder().encodeToString(raw)
    }

    suspend fun proxy(call: ApplicationCall, pathWithQuery: String) {
        call.relay(
            client = client,
            targetUrl = "${config.calibreUrl}$pathWithQuery",
            injectHeaders = basicAuthHeader?.let { mapOf("Authorization" to it) } ?: emptyMap(),
        )
    }
}
