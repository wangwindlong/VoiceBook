package us.wangxy.voicebook.server.bff.account

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import us.wangxy.voicebook.bff.contract.BffComponents
import us.wangxy.voicebook.bff.contract.ChangePasswordRequest
import us.wangxy.voicebook.bff.contract.ProvisionResult
import us.wangxy.voicebook.bff.contract.RegisterRequest
import us.wangxy.voicebook.bff.contract.RegisterResponse
import us.wangxy.voicebook.server.bff.BffException
import us.wangxy.voicebook.server.bff.config.SecurityConfig
import us.wangxy.voicebook.server.bff.lldap.DirectoryAdmin
import us.wangxy.voicebook.server.bff.lldap.DirectoryPasswords
import us.wangxy.voicebook.server.bff.lldap.LldapDuplicateException

/** Downstream components that get an account created eagerly after registration. */
fun interface Provisioner {
    suspend fun provision(username: String, password: String): ProvisionResult
}

/**
 * Registration and password changes. LLDAP is the only place a password is stored; component
 * accounts are created by JIT (Artalk on first comment) or eagerly via [provisioners]. A failed
 * provisioner never fails the registration: every component re-provisions itself on first use.
 */
class AccountService(
    private val directory: DirectoryAdmin,
    private val passwords: DirectoryPasswords,
    private val defaultGroups: List<String>,
    private val security: SecurityConfig,
    private val provisioners: Map<String, Provisioner>,
) {
    private val log = LoggerFactory.getLogger(AccountService::class.java)

    suspend fun register(request: RegisterRequest): RegisterResponse {
        if (!security.registrationEnabled) {
            throw BffException(HttpStatusCode.Forbidden, "REGISTRATION_DISABLED", "暂未开放注册")
        }
        val username = request.username.trim().lowercase()
        val email = request.email.trim()
        validateUsername(username)
        if (!EMAIL.matches(email)) throw BffException.badRequest("邮箱格式不正确", "INVALID_EMAIL")
        validatePassword(request.password)

        if (directory.userExists(username)) throw duplicate()
        try {
            directory.createUser(username, email, request.displayName?.trim()?.takeIf { it.isNotEmpty() } ?: username)
        } catch (e: LldapDuplicateException) {
            throw duplicate()
        }
        try {
            directory.addUserToGroups(username, defaultGroups)
            passwords.setPassword(username, request.password)
        } catch (e: Throwable) {
            log.warn("register {} failed after createUser, rolling back", username, e)
            runCatching { directory.deleteUser(username) }
                .onFailure { log.error("rollback of {} failed; delete it in LLDAP manually", username, it) }
            throw e
        }
        log.info("registered {}", username)
        return RegisterResponse(username, provisionAll(username, request.password))
    }

    suspend fun changePassword(username: String, request: ChangePasswordRequest) {
        validatePassword(request.newPassword)
        if (request.oldPassword == request.newPassword) throw BffException.badRequest("新密码不能与旧密码相同", "WEAK_PASSWORD")
        if (!passwords.verify(username, request.oldPassword)) {
            throw BffException(HttpStatusCode.Forbidden, "WRONG_PASSWORD", "旧密码不正确")
        }
        passwords.setPassword(username, request.newPassword)
        log.info("password changed for {}", username)
        provisioners[BffComponents.CALIBRE]?.let { runProvisioner(BffComponents.CALIBRE, it, username, request.newPassword) }
    }

    /** Re-runs one component's provisioning with a password the caller just proved. */
    suspend fun activate(component: String, username: String, password: String): ProvisionResult {
        if (!passwords.verify(username, password)) {
            throw BffException(HttpStatusCode.Forbidden, "WRONG_PASSWORD", "密码不正确")
        }
        val provisioner = provisioners[component] ?: throw BffException.badRequest("未知组件 $component")
        return runProvisioner(component, provisioner, username, password)
    }

    private suspend fun provisionAll(username: String, password: String): Map<String, ProvisionResult> =
        provisioners.mapValues { (name, p) -> runProvisioner(name, p, username, password) } +
            (BffComponents.ARTALK to ProvisionResult(true, "首次评论时自动创建"))

    private suspend fun runProvisioner(name: String, p: Provisioner, username: String, password: String) = try {
        p.provision(username, password)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("provision {} for {} failed: {}", name, username, e.message)
        ProvisionResult(false, e.message ?: e::class.simpleName)
    }

    private fun validateUsername(username: String) {
        if (!USERNAME.matches(username)) {
            throw BffException.badRequest("用户名需 3-32 位，以字母开头，只含小写字母、数字、._-", "INVALID_USERNAME")
        }
        if (username in RESERVED) throw BffException.badRequest("该用户名不可用", "INVALID_USERNAME")
    }

    private fun validatePassword(password: String) {
        val ok = password.length >= security.passwordMinLength &&
            password.length <= 128 &&
            password.any { it.isLetter() } &&
            password.any { it.isDigit() }
        if (!ok) {
            throw BffException.badRequest("密码至少 ${security.passwordMinLength} 位，且需同时包含字母和数字", "WEAK_PASSWORD")
        }
    }

    private fun duplicate() = BffException(HttpStatusCode.Conflict, "USER_EXISTS", "用户名或邮箱已被占用")

    private companion object {
        val USERNAME = Regex("^[a-z][a-z0-9._-]{2,31}$")
        val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        val RESERVED = setOf("admin", "root", "administrator", "system", "lldap", "authelia", "miniflux", "artalk", "calibre")
    }
}
