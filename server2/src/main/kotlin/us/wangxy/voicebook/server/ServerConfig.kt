package us.wangxy.voicebook.server

/**
 * BFF 的全部配置，一律来自环境变量（部署时由 docker-compose 注入）。
 *
 * 命名统一 `VB_` 前缀。带 secret 的项生产环境请用 docker secret 或受限权限的 env 文件。
 */
data class ServerConfig(
    val port: Int,

    // ---- OIDC（Authelia）----
    /** Authelia 对外地址，也是 token 的 issuer，例如 https://nas.wangyl.work:8461 */
    val issuer: String,
    /**
     * 校验 access_token 用的 userinfo 端点。
     * 注意 Authelia 的路径是 `/api/oidc/userinfo`（不是根下的 /userinfo）。
     */
    val userinfoUrl: String,

    // ---- LLDAP ----
    /** LLDAP GraphQL 地址，例如 http://192.168.100.10:17170 */
    val lldapHttpUrl: String,
    /** LLDAP 的 LDAP 地址，例如 ldap://192.168.100.10:3890 （设密码只能走这条） */
    val lldapLdapUrl: String,
    /** 例如 dc=voicebook,dc=local */
    val lldapBaseDn: String,
    /** 管理员 DN，例如 uid=admin,ou=people,dc=voicebook,dc=local */
    val lldapAdminDn: String,
    val lldapAdminPassword: String,
    /** 新用户默认加入的组（逗号分隔）；calibre 需要 calibre_web 组才有访问权 */
    val lldapDefaultGroups: List<String>,

    // ---- Miniflux ----
    val minifluxUrl: String,
    /**
     * 管理员 API token（Miniflux「Settings > API Keys」生成）。
     * 用途：代用户建号、把用户密码重置成派生值。见 MinifluxUpstream 里的说明。
     */
    val minifluxAdminToken: String,
    /**
     * 派生用户密码的密钥。同一密钥下 username → 固定密码，
     * 所以 BFF 不需要保存任何用户密码表。
     */
    val minifluxPasswordSecret: String,

    // ---- Artalk ----
    val artalkUrl: String,

    // ---- calibre (CWA) ----
    val calibreUrl: String,
    /** OPDS 走 Basic Auth；用一个共享只读账号即可（书库内容全站一致） */
    val calibreUsername: String,
    val calibrePassword: String,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): ServerConfig {
            fun req(key: String): String =
                env[key]?.takeIf { it.isNotBlank() }
                    ?: error("缺少必需的环境变量 $key")

            val issuer = req("VB_ISSUER").trimEnd('/')

            return ServerConfig(
                port = env["VB_PORT"]?.toIntOrNull() ?: 8080,

                issuer = issuer,
                userinfoUrl = env["VB_USERINFO_URL"]?.takeIf { it.isNotBlank() }
                    ?: "$issuer/api/oidc/userinfo",

                lldapHttpUrl = req("VB_LLDAP_HTTP_URL").trimEnd('/'),
                lldapLdapUrl = req("VB_LLDAP_LDAP_URL"),
                lldapBaseDn = req("VB_LLDAP_BASE_DN"),
                lldapAdminDn = req("VB_LLDAP_ADMIN_DN"),
                lldapAdminPassword = req("VB_LLDAP_ADMIN_PASSWORD"),
                lldapDefaultGroups = (env["VB_LLDAP_DEFAULT_GROUPS"] ?: "calibre_web")
                    .split(',').map { it.trim() }.filter { it.isNotEmpty() },

                minifluxUrl = req("VB_MINIFLUX_URL").trimEnd('/'),
                minifluxAdminToken = req("VB_MINIFLUX_ADMIN_TOKEN"),
                minifluxPasswordSecret = req("VB_MINIFLUX_PASSWORD_SECRET"),

                artalkUrl = req("VB_ARTALK_URL").trimEnd('/'),

                calibreUrl = req("VB_CALIBRE_URL").trimEnd('/'),
                calibreUsername = env["VB_CALIBRE_USERNAME"] ?: "",
                calibrePassword = env["VB_CALIBRE_PASSWORD"] ?: "",
            )
        }
    }
}
