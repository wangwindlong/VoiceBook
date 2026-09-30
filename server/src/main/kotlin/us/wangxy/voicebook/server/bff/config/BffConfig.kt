package us.wangxy.voicebook.server.bff.config

import java.io.File

/**
 * All runtime settings, read from environment variables (see server/deploy/.env.example).
 * Any variable `X` may instead be supplied as a file path in `X_FILE` (docker secrets).
 */
data class BffConfig(
    val port: Int,
    val oidc: OidcConfig,
    val lldap: LldapConfig,
    val miniflux: MinifluxConfig,
    val artalk: ArtalkConfig,
    val calibre: CalibreConfig,
    val security: SecurityConfig,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): BffConfig {
            val e = Env(env)
            val issuer = e.required("OIDC_ISSUER").trimEnd('/')
            return BffConfig(
                port = e.int("PORT", 8080),
                oidc = OidcConfig(
                    issuer = issuer,
                    clientId = e.str("OIDC_CLIENT_ID", "voicebook-app"),
                    redirectUri = e.str("OIDC_REDIRECT_URI", "voicebook://oauth2/callback"),
                    scopes = e.str("OIDC_SCOPES", "openid profile email groups offline_access"),
                    userinfoUrl = e.str("OIDC_USERINFO_URL", "$issuer/api/oidc/userinfo"),
                    usernameClaim = e.str("OIDC_USERNAME_CLAIM", "preferred_username"),
                    cacheTtlSeconds = e.int("OIDC_CACHE_TTL_SECONDS", 60).toLong(),
                ),
                lldap = LldapConfig(
                    httpUrl = e.str("LLDAP_HTTP_URL", "http://lldap:17170").trimEnd('/'),
                    ldapHost = e.str("LLDAP_LDAP_HOST", "lldap"),
                    ldapPort = e.int("LLDAP_LDAP_PORT", 3890),
                    baseDn = e.required("LLDAP_BASE_DN"),
                    adminUser = e.str("LLDAP_ADMIN_USER", "admin"),
                    adminPassword = e.required("LLDAP_ADMIN_PASSWORD"),
                    defaultGroups = e.list("LLDAP_DEFAULT_GROUPS"),
                ),
                miniflux = MinifluxConfig(
                    url = e.str("MINIFLUX_URL", "http://miniflux:8080").trimEnd('/'),
                    adminToken = e.required("MINIFLUX_ADMIN_TOKEN"),
                    passwordSecret = e.required("MINIFLUX_PASSWORD_SECRET").also {
                        require(it.length >= 32) { "MINIFLUX_PASSWORD_SECRET 至少 32 个字符" }
                    },
                    sharedUser = e.str("MINIFLUX_SHARED_USER", "voicebook-shared"),
                    defaultFeeds = e.list("MINIFLUX_DEFAULT_FEEDS"),
                    stateDb = File(e.str("BFF_STATE_DB", "/data/bff.db")),
                ),
                artalk = ArtalkConfig(
                    url = e.str("ARTALK_URL", "http://artalk:23366").trimEnd('/'),
                    siteName = e.str("ARTALK_SITE_NAME", "VoiceBook"),
                ),
                calibre = CalibreConfig(
                    libraryDir = File(e.str("CALIBRE_LIBRARY_DIR", "/calibre-library")),
                    appDb = File(e.str("CALIBRE_APP_DB", "/cwa-config/app.db")),
                    webUrl = e.str("CALIBRE_WEB_URL", "http://calibre-web-automated:8083").trimEnd('/'),
                ),
                security = SecurityConfig(
                    registrationEnabled = e.bool("REGISTRATION_ENABLED", true),
                    registerPerMinute = e.int("REGISTER_RATE_PER_MINUTE", 5),
                    passwordMinLength = e.int("PASSWORD_MIN_LENGTH", 8),
                    loginPerMinute = e.int("LOGIN_RATE_PER_MINUTE", 10),
                    commentRateLimit = e.int("COMMENT_RATE_LIMIT", 10),
                    commentRateWindowMinutes = e.int("COMMENT_RATE_WINDOW_MINUTES", 60),
                    passwordResetUrl = e.raw("PASSWORD_RESET_URL"),
                    corsAllowedOrigins = e.list("CORS_ALLOWED_ORIGINS"),
                ),
            )
        }
    }
}

data class OidcConfig(
    /**
     * Public Authelia URL. The BFF logs users in through it, so it must be the address whose host
     * matches Authelia's session cookie domain (not an internal container URL).
     */
    val issuer: String,
    /** Public PKCE client; Authelia must have `consent_mode: 'implicit'` for it. */
    val clientId: String = "voicebook-app",
    /** Never followed: the BFF only reads the code out of the redirect Location. */
    val redirectUri: String = "voicebook://oauth2/callback",
    val scopes: String = "openid profile email groups offline_access",
    /** Authelia `/api/oidc/userinfo`; access tokens are opaque, so this is the only way to validate them. */
    val userinfoUrl: String = "$issuer/api/oidc/userinfo",
    val usernameClaim: String = "preferred_username",
    val cacheTtlSeconds: Long = 60,
)

data class LldapConfig(
    val httpUrl: String,
    val ldapHost: String,
    val ldapPort: Int,
    val baseDn: String,
    val adminUser: String,
    val adminPassword: String,
    /** Group displayNames every new user joins (e.g. the group CWA's LDAP filter requires). */
    val defaultGroups: List<String>,
) {
    fun userDn(uid: String): String =
        com.unboundid.ldap.sdk.RDN("uid", uid).toString() + ",ou=people," + baseDn
}

data class MinifluxConfig(
    val url: String,
    /** API key of a Miniflux admin; used only for user management endpoints. */
    val adminToken: String,
    /** HMAC key deriving the shared account's Miniflux password; rotating it re-provisions it lazily. */
    val passwordSecret: String,
    /** The single Miniflux account that owns every feed; all BFF users share it. */
    val sharedUser: String = "voicebook-shared",
    /** Feeds every newly registered user is subscribed to (subscribed once on the shared account). */
    val defaultFeeds: List<String> = emptyList(),
    /** BFF-owned SQLite file holding per-user subscriptions and read/starred state. */
    val stateDb: File = File("/data/bff.db"),
)

data class ArtalkConfig(val url: String, val siteName: String)

data class CalibreConfig(
    /** Directory holding calibre's metadata.db and the book folders (mounted read-only). */
    val libraryDir: File,
    /** calibre-web-automated's app.db (users, bookmarks, kobo reading state). */
    val appDb: File,
    /** Internal CWA URL, used to log a user in once so its LDAP auto-create kicks in. */
    val webUrl: String,
) {
    val metadataDb: File get() = File(libraryDir, "metadata.db")
}

data class SecurityConfig(
    val registrationEnabled: Boolean,
    val registerPerMinute: Int,
    val passwordMinLength: Int,
    val loginPerMinute: Int = 10,

    /** 发评论限频：同一 key（已登录取用户名，未登录取 IP）在 [commentRateWindowMinutes] 分钟内最多 [commentRateLimit] 条。 */
    val commentRateLimit: Int = 10,
    val commentRateWindowMinutes: Int = 60,
    /** Shown by the app's "forgot password" page, e.g. Authelia's reset portal; null hides the link. */
    val passwordResetUrl: String? = null,
    /** Browser origins allowed to call the BFF (web app build); empty disables CORS. */
    val corsAllowedOrigins: List<String> = emptyList(),
)

private class Env(private val env: Map<String, String>) {
    fun raw(key: String): String? {
        env[key]?.takeIf { it.isNotBlank() }?.let { return it }
        return env["${key}_FILE"]?.takeIf { it.isNotBlank() }?.let { File(it).readText().trim() }
    }

    fun required(key: String): String =
        raw(key) ?: throw IllegalStateException("缺少必填环境变量 $key（或 ${key}_FILE）")

    fun str(key: String, default: String) = raw(key) ?: default
    fun int(key: String, default: Int) = raw(key)?.toInt() ?: default
    fun bool(key: String, default: Boolean) = raw(key)?.lowercase()?.let { it == "1" || it == "true" } ?: default
    fun list(key: String) = raw(key)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}
