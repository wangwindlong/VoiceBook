package us.wangxy.voicebook.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.log
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import us.wangxy.voicebook.server.auth.AUTH_OIDC
import us.wangxy.voicebook.server.auth.voiceBookUser
import us.wangxy.voicebook.server.lldap.LdapPasswordService
import us.wangxy.voicebook.server.lldap.LldapGraphQlClient
import us.wangxy.voicebook.server.model.ChangePasswordRequest
import us.wangxy.voicebook.server.model.ErrorResponse
import us.wangxy.voicebook.server.model.MeResponse
import us.wangxy.voicebook.server.model.RegisterRequest
import us.wangxy.voicebook.server.model.SimpleResult
import us.wangxy.voicebook.server.ServerConfig

/**
 * 认证类接口。
 *
 * `/auth/register` 和 `/auth/password` 之所以必须有后端：LLDAP 的 GraphQL **建得了号、
 * 设不了密码**（OPAQUE），密码只能经 LDAP 协议写入。
 */
fun Route.authRoutes(
    config: ServerConfig,
    lldap: LldapGraphQlClient,
    passwords: LdapPasswordService,
) {
    route("/auth") {

        /**
         * 注册：GraphQL 建号 → 加默认组 → LDAP 设密码。
         * 三步都成功才算成功；设密码失败会尽量把刚建的号删掉，避免留下「有号无密码」的僵尸账号。
         */
        post("/register") {
            val req = call.receive<RegisterRequest>()

            // ---- 基本校验 ----
            val username = req.username.trim()
            val email = req.email.trim()
            val err = when {
                username.length < 3 -> "用户名至少 3 个字符"
                !username.matches(Regex("[a-zA-Z0-9._-]+")) -> "用户名只能包含字母、数字、. _ -"
                !email.contains('@') -> "邮箱格式不正确"
                req.password.length < 8 -> "密码至少 8 位"
                else -> null
            }
            if (err != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_input", err))
                return@post
            }

            var created = false
            try {
                lldap.createUser(username, email, req.displayName)
                created = true
                config.lldapDefaultGroups.forEach { group -> lldap.addUserToGroup(username, group) }
                passwords.setPassword(username, req.password)
                call.application.log.info("注册成功: $username")
                call.respond(HttpStatusCode.Created, SimpleResult(true, "注册成功"))
            } catch (e: Exception) {
                call.application.log.error("注册失败: $username", e)
                if (created) {
                    runCatching { lldap.deleteUser(username) }
                }
                call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("register_failed", e.message ?: "注册失败"),
                )
            }
        }

        /** 改密码：先验证旧密码（LDAP bind），再管理员改。要求已登录。 */
        authenticate(AUTH_OIDC) {
            post("/password") {
                val user = call.voiceBookUser()
                    ?: return@post call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse("unauthorized", "需要登录"),
                    )
                val req = call.receive<ChangePasswordRequest>()
                if (req.username != user.username) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ErrorResponse("forbidden", "只能修改自己的密码"),
                    )
                    return@post
                }
                if (req.newPassword.length < 8) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("invalid_input", "新密码至少 8 位"),
                    )
                    return@post
                }
                try {
                    passwords.changePassword(req.username, req.oldPassword, req.newPassword)
                    call.respond(SimpleResult(true, "密码已修改，所有组件立即生效"))
                } catch (e: Exception) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("password_failed", e.message ?: "修改密码失败"),
                    )
                }
            }

            /** 当前登录用户 */
            get("/me") {
                val user = call.voiceBookUser()
                    ?: return@get call.respond(
                        HttpStatusCode.Unauthorized,
                        ErrorResponse("unauthorized", "需要登录"),
                    )
                call.respond(
                    MeResponse(
                        sub = user.subject,
                        username = user.username,
                        email = user.email,
                        displayName = user.displayName,
                        groups = user.groups,
                    ),
                )
            }
        }
    }
}
