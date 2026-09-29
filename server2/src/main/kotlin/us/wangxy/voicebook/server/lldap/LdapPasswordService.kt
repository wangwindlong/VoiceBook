package us.wangxy.voicebook.server.lldap

import com.unboundid.ldap.sdk.LDAPConnection
import com.unboundid.ldap.sdk.ResultCode
import com.unboundid.ldap.sdk.extensions.PasswordModifyExtendedRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import us.wangxy.voicebook.server.ServerConfig

/**
 * LLDAP 的**密码通道**。
 *
 * 为什么必须单独一个类：LLDAP 的 GraphQL API 建得了号、设不了密码（OPAQUE 零知识证明，
 * 输入里根本没有 password 字段）。密码只能经 LDAP 协议的 RFC 3062
 * Password Modify extended operation 写入——这正是「自研 App 自助注册必须有一层 BFF」的原因。
 *
 * 里的操作都走 Dispatchers.IO（LDAP SDK 是阻塞式）。
 */
class LdapPasswordService(private val config: ServerConfig) {
    private val log = LoggerFactory.getLogger(LdapPasswordService::class.java)

    private data class Endpoint(val host: String, val port: Int)

    private val endpoint: Endpoint by lazy {
        // ldap://host:3890 或 ldaps://host:6360 或 host:3890
        val raw = config.lldapLdapUrl
        val withoutScheme = raw.substringAfter("://", raw)
        val scheme = raw.substringBefore("://", "ldap")
        val host = withoutScheme.substringBefore(':')
        val defaultPort = if (scheme.startsWith("ldaps")) 6360 else 3890
        val port = withoutScheme.substringAfter(':', "").toIntOrNull() ?: defaultPort
        Endpoint(host, port)
    }

    private fun userDn(username: String) = "uid=$username,ou=people,${config.lldapBaseDn}"

    /** 用管理员身份 bind 一个连接；调用方负责 close */
    private fun adminConnection(): LDAPConnection {
        val conn = LDAPConnection(endpoint.host, endpoint.port)
        val result = conn.bind(config.lldapAdminDn, config.lldapAdminPassword)
        if (result.resultCode != ResultCode.SUCCESS) {
            conn.close()
            error("LDAP 管理员 bind 失败: ${result.resultCode} ${result.diagnosticMessage}")
        }
        return conn
    }

    /** 设置/重置密码（管理员权限，RFC 3062） */
    suspend fun setPassword(username: String, newPassword: String): Unit = withContext(Dispatchers.IO) {
        val conn = adminConnection()
        try {
            val request = PasswordModifyExtendedRequest(userDn(username), null, newPassword)
            val result = conn.processExtendedOperation(request)
            if (result.resultCode != ResultCode.SUCCESS) {
                error("设置密码失败: ${result.resultCode} ${result.diagnosticMessage}")
            }
            log.info("已为用户 $username 设置密码")
        } finally {
            conn.close()
        }
    }

    /**
     * 改密码：先用旧密码 bind 验证身份，再用管理员连接改。
     * 这样即使 BFF 被冒用也改不了别人的密码。
     */
    suspend fun changePassword(username: String, oldPassword: String, newPassword: String): Unit =
        withContext(Dispatchers.IO) {
            // ① 验证旧密码
            val verifyConn = LDAPConnection(endpoint.host, endpoint.port)
            try {
                val r = verifyConn.bind(userDn(username), oldPassword)
                if (r.resultCode != ResultCode.SUCCESS) {
                    error("当前密码不正确")
                }
            } finally {
                verifyConn.close()
            }
            // ② 管理员改密
            val conn = adminConnection()
            try {
                val request = PasswordModifyExtendedRequest(userDn(username), null, newPassword)
                val result = conn.processExtendedOperation(request)
                if (result.resultCode != ResultCode.SUCCESS) {
                    error("修改密码失败: ${result.resultCode} ${result.diagnosticMessage}")
                }
                log.info("用户 $username 修改密码成功")
            } finally {
                conn.close()
            }
        }

    /** 验证用户名+密码（注册后自检，或将来做本地兜底登录） */
    suspend fun verifyPassword(username: String, password: String): Boolean = withContext(Dispatchers.IO) {
        val conn = LDAPConnection(endpoint.host, endpoint.port)
        try {
            conn.bind(userDn(username), password).resultCode == ResultCode.SUCCESS
        } catch (e: Exception) {
            false
        } finally {
            runCatching { conn.close() }
        }
    }
}
