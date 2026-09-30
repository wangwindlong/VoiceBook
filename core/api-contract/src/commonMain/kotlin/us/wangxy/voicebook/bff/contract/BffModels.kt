package us.wangxy.voicebook.bff.contract

import kotlinx.serialization.Serializable

/** Body of every non-2xx response produced by the BFF itself (upstream pass-through bodies excluded). */
@Serializable
data class ApiError(val code: String, val message: String)

// ---- auth ----

@Serializable
data class LoginRequest(val username: String, val password: String)

/** Authelia OIDC tokens relayed by the BFF; [expiresIn] is seconds from issuance. */
@Serializable
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresIn: Long,
    val tokenType: String = "Bearer",
)

@Serializable
data class LoginResponse(val tokens: AuthTokens, val user: MeResponse)

@Serializable
data class RefreshRequest(val refreshToken: String)

/** The access token travels in the Authorization header (optional: it may already be expired). */
@Serializable
data class LogoutRequest(val refreshToken: String? = null)

/** Public settings the login screens need before the user is signed in. */
@Serializable
data class AuthConfigResponse(
    val registrationEnabled: Boolean,
    val passwordMinLength: Int,
    val passwordResetUrl: String? = null,
)

@Serializable
data class RegisterRequest(
    val username: String,
    val email: String,
    val password: String,
    val displayName: String? = null,
)

@Serializable
data class ProvisionResult(val ok: Boolean, val message: String? = null)

/** [provisioning] is keyed by [BffComponents]; a failed entry does not fail the registration. */
@Serializable
data class RegisterResponse(val username: String, val provisioning: Map<String, ProvisionResult>)

@Serializable
data class ChangePasswordRequest(val oldPassword: String, val newPassword: String)

@Serializable
data class MeResponse(
    val username: String,
    val displayName: String? = null,
    val email: String? = null,
    val groups: List<String> = emptyList(),
)

@Serializable
data class TokenExchangeRequest(val component: String)

/** [expiresAt] is epoch seconds, null when the upstream token carries no expiry. */
@Serializable
data class TokenExchangeResponse(val component: String, val token: String, val expiresAt: Long? = null)

// ---- calibre ----

@Serializable
data class CalibreBook(
    val id: Long,
    val title: String,
    val authors: List<String> = emptyList(),
    val authorSort: String? = null,
    val tags: List<String> = emptyList(),
    val series: String? = null,
    val seriesIndex: Double? = null,
    val pubdate: String? = null,
    val addedAt: String? = null,
    val hasCover: Boolean = false,
    val formats: List<String> = emptyList(),
    val description: String? = null,
)

@Serializable
data class CalibreBookPage(val items: List<CalibreBook>, val total: Long, val offset: Int, val limit: Int)

@Serializable
data class ReadingProgress(
    val bookId: Long,
    val format: String? = null,
    val position: String? = null,
    val percent: Double? = null,
    val updatedAt: String? = null,
)

/** [position] is opaque to the BFF (EPUB CFI, page index, ...); [percent] is 0..100. */
@Serializable
data class ReadingProgressUpdate(val format: String, val position: String, val percent: Double? = null)

@Serializable
data class CalibreActivateRequest(val password: String)

// ---- artalk ----

@Serializable
data class CommentCreateRequest(
    val pageKey: String,
    val content: String,
    val pageTitle: String? = null,
    val replyTo: Long? = null,
)

/** Artalk 图片验证码的用户输入，配合 [BffRoutes.ARTALK_CAPTCHA_VERIFY] 使用。 */
@Serializable
data class CaptchaVerifyRequest(val value: String)

@Serializable
data class ArticleReaction(val likes: Long = 0, val liked: Boolean = false)

/** PUT uses desired state, so retries never accidentally toggle twice. */
@Serializable
data class ArticleReactionUpdate(val liked: Boolean)

@Serializable
data class FeedCategories(val categories: Map<String, String> = emptyMap())

@Serializable
data class FeedCategoryUpdate(val category: String)
