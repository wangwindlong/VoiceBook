package us.wangxy.voicebook.server.bff.routes

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import us.wangxy.voicebook.bff.contract.AuthConfigResponse
import us.wangxy.voicebook.bff.contract.BffComponents
import us.wangxy.voicebook.bff.contract.BffRoutes
import us.wangxy.voicebook.bff.contract.ChangePasswordRequest
import us.wangxy.voicebook.bff.contract.LoginRequest
import us.wangxy.voicebook.bff.contract.LoginResponse
import us.wangxy.voicebook.bff.contract.LogoutRequest
import us.wangxy.voicebook.bff.contract.MeResponse
import us.wangxy.voicebook.bff.contract.RefreshRequest
import us.wangxy.voicebook.bff.contract.RegisterRequest
import us.wangxy.voicebook.bff.contract.TokenExchangeRequest
import us.wangxy.voicebook.bff.contract.TokenExchangeResponse
import us.wangxy.voicebook.server.bff.AUTH_OIDC
import us.wangxy.voicebook.server.bff.BffException
import us.wangxy.voicebook.server.bff.BffJson
import us.wangxy.voicebook.server.bff.BffServices
import us.wangxy.voicebook.server.bff.artalk.ArtalkGateway
import us.wangxy.voicebook.server.bff.auth.OidcUser

fun Route.authRoutes(services: BffServices) {
    get(BffRoutes.AUTH_CONFIG) {
        val security = services.security
        call.respond(AuthConfigResponse(security.registrationEnabled, security.passwordMinLength, security.passwordResetUrl))
    }

    rateLimit(LOGIN_LIMIT) {
        post(BffRoutes.AUTH_LOGIN) {
            val request = call.receive<LoginRequest>()
            val username = request.username.trim().lowercase()
            if (username.isEmpty() || request.password.isEmpty()) throw BffException.badRequest("请输入用户名和密码")
            val tokens = services.oidcLogin.login(username, request.password, call.clientIp())
            val user = services.verifier.verify(tokens.accessToken)
                ?: throw BffException.upstream("oidc", "新签发的 token 未通过 userinfo 校验")
            call.respond(LoginResponse(tokens, user.toMe()))
        }
    }

    post(BffRoutes.AUTH_REFRESH) {
        val request = call.receive<RefreshRequest>()
        if (request.refreshToken.isBlank()) throw BffException.badRequest("缺少 refreshToken")
        call.respond(services.oidcLogin.refresh(request.refreshToken))
    }

    // Deliberately outside authenticate(): an expired access token must still be able to log out.
    post(BffRoutes.AUTH_LOGOUT) {
        val accessToken = call.request.headers[HttpHeaders.Authorization]
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.trim()?.takeIf { it.isNotEmpty() }
        val body = call.receiveText().takeIf { it.isNotBlank() }
            ?.let { runCatching { BffJson.decodeFromString<LogoutRequest>(it) }.getOrNull() }
        if (accessToken != null) {
            services.verifier.invalidate(accessToken)?.let { services.artalk.forget(it.username) }
            services.oidcLogin.revoke(accessToken, "access_token")
        }
        body?.refreshToken?.takeIf { it.isNotBlank() }?.let { services.oidcLogin.revoke(it, "refresh_token") }
        call.respond(HttpStatusCode.NoContent)
    }

    rateLimit(REGISTER_LIMIT) {
        post(BffRoutes.AUTH_REGISTER) {
            val request = call.receive<RegisterRequest>()
            call.respond(HttpStatusCode.Created, services.accounts.register(request))
        }
    }

    authenticate(AUTH_OIDC) {
        get(BffRoutes.AUTH_ME) {
            call.respond(call.bffPrincipal.user.toMe())
        }

        rateLimit(PASSWORD_LIMIT) {
            post(BffRoutes.AUTH_PASSWORD) {
                services.accounts.changePassword(call.bffPrincipal.username, call.receive<ChangePasswordRequest>())
                call.respond(HttpStatusCode.NoContent)
            }
        }

        post(BffRoutes.AUTH_TOKEN_EXCHANGE) {
            val request = call.receive<TokenExchangeRequest>()
            when (request.component) {
                BffComponents.ARTALK -> when (val exchanged = services.artalk.tokenFor(call.bffPrincipal)) {
                    is ArtalkGateway.ExchangeResult.Issued ->
                        call.respond(TokenExchangeResponse(BffComponents.ARTALK, exchanged.token.token, exchanged.token.expiresAtSeconds))

                    // 反垃圾要求验证码：原样透出 Artalk 的 403 + 图（客户端弹码后重试），别包成 502
                    is ArtalkGateway.ExchangeResult.CaptchaRequired -> call.respondUpstream(exchanged.upstream)
                }
                BffComponents.MINIFLUX, BffComponents.CALIBRE ->
                    throw BffException.badRequest("${request.component} 由 BFF 代理访问，不下发凭据", "NOT_EXCHANGEABLE")
                else -> throw BffException.badRequest("未知组件 ${request.component}")
            }
        }
    }
}

private fun OidcUser.toMe() = MeResponse(username, displayName, email, groups)
