package us.wangxy.voicebook.reader.api

import kotlinx.serialization.Serializable

/** 已保存的 calibre-web 服务器账号（多账号切换用）。 */
@Serializable
data class CalibreServerAccount(
    /** baseUrl|username，稳定可重算。 */
    val id: String,
    val label: String,
    val baseUrl: String,
    val username: String = "",
    val password: String = "",
    val lastUsedAt: Long = 0L,
)
