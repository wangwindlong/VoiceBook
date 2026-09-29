package us.wangxy.voicebook.server.model

import kotlinx.serialization.Serializable

// ============================================================
// 认证相关 DTO
// ============================================================

/** POST /auth/register 的请求体 */
@Serializable
data class RegisterRequest(
    val username: String,
    val email: String,
    val password: String,
    /** 展示名，缺省用 username */
    val displayName: String? = null,
)

/** POST /auth/password 的请求体（改自己的密码，需验证旧密码） */
@Serializable
data class ChangePasswordRequest(
    val username: String,
    val oldPassword: String,
    val newPassword: String,
)

/** 注册/改密的通用响应 */
@Serializable
data class SimpleResult(
    val ok: Boolean,
    val message: String? = null,
)

/** GET /auth/me 的响应（来自 OIDC userinfo） */
@Serializable
data class MeResponse(
    val sub: String,
    val username: String,
    val email: String? = null,
    val displayName: String? = null,
    val groups: List<String> = emptyList(),
)

/** POST /auth/token/exchange 的请求体 */
@Serializable
data class TokenExchangeRequest(
    val component: String, // artalk | miniflux | calibre
)

/** POST /auth/token/exchange 的响应：各组件自己的凭据形态不同，用 map 返回 */
@Serializable
data class TokenExchangeResponse(
    val component: String,
    /** 组件侧凭据（Artalk 为 JWT；Miniflux/calibre 为反代用法说明） */
    val token: String? = null,
    /** 该组件应使用的 base URL（App 直接用这个） */
    val baseUrl: String,
    /** 额外信息，如用户名 */
    val username: String? = null,
)
