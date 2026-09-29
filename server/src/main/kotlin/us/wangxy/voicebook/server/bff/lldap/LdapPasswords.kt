package us.wangxy.voicebook.server.bff.lldap

import com.unboundid.ldap.sdk.LDAPConnection
import com.unboundid.ldap.sdk.LDAPConnectionOptions
import com.unboundid.ldap.sdk.LDAPException
import com.unboundid.ldap.sdk.ResultCode
import com.unboundid.ldap.sdk.extensions.PasswordModifyExtendedRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import us.wangxy.voicebook.server.bff.BffException
import us.wangxy.voicebook.server.bff.config.LldapConfig

interface DirectoryPasswords {
    /** True when [password] binds as [userId]; blank passwords are always rejected (anonymous bind). */
    suspend fun verify(userId: String, password: String): Boolean
    suspend fun setPassword(userId: String, newPassword: String)
}

/**
 * LLDAP stores OPAQUE registrations, so passwords can only be set over the LDAP protocol
 * (Password Modify extended operation, RFC 3062), bound as the LLDAP admin.
 */
class LdapPasswords(private val config: LldapConfig) : DirectoryPasswords {

    override suspend fun verify(userId: String, password: String): Boolean {
        if (password.isBlank()) return false
        return withContext(Dispatchers.IO) {
            connect().use { conn ->
                try {
                    conn.bind(config.userDn(userId), password)
                    true
                } catch (e: LDAPException) {
                    if (e.resultCode == ResultCode.INVALID_CREDENTIALS || e.resultCode == ResultCode.NO_SUCH_OBJECT) false
                    else throw BffException.upstream("lldap", "LDAP 校验失败: ${e.resultCode}", e)
                }
            }
        }
    }

    override suspend fun setPassword(userId: String, newPassword: String) {
        withContext(Dispatchers.IO) {
            connect().use { conn ->
                try {
                    conn.bind(config.userDn(config.adminUser), config.adminPassword)
                    val result = conn.processExtendedOperation(
                        PasswordModifyExtendedRequest(config.userDn(userId), null, newPassword),
                    )
                    if (result.resultCode != ResultCode.SUCCESS) {
                        throw BffException.upstream("lldap", "设置密码失败: ${result.resultCode} ${result.diagnosticMessage.orEmpty()}")
                    }
                } catch (e: LDAPException) {
                    throw BffException.upstream("lldap", "设置密码失败: ${e.resultCode}", e)
                }
            }
        }
    }

    private fun connect(): LDAPConnection = try {
        val options = LDAPConnectionOptions().apply {
            connectTimeoutMillis = 5_000
            responseTimeoutMillis = 10_000
        }
        LDAPConnection(options, config.ldapHost, config.ldapPort)
    } catch (e: LDAPException) {
        throw BffException.upstream("lldap", "无法连接 LDAP ${config.ldapHost}:${config.ldapPort}", e)
    }
}
