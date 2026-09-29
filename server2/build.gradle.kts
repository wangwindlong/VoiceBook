// BFF（Backend For Frontend）—— VoiceBook 的统一网关。
//
// 职责：把 Artalk / Miniflux / calibre 三个内容源收在一个后端之后，App 只认这一个地址。
//   - 校验 Authelia 签发的 OIDC access token（JWKS）
//   - 注册 / 改密：写 LLDAP（GraphQL 建号 + LDAP 协议设密码）
//   - Miniflux：反代并注入 X-Forwarded-User（走 Miniflux 的 AUTH_PROXY_HEADER）
//   - Artalk  ：拿用户 token 调 sso/exchange 换 JWT 后代发
//   - calibre ：反代 OPDS，Basic Auth 由本服务持一个共享只读账号
//
// 纯 JVM 模块（不是 KMP）：它只在服务器上跑。
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinxSerialization)
    alias(libs.plugins.ktor)
}

group = "us.wangxy.voicebook"
version = "1.0.0"

application {
    mainClass.set("us.wangxy.voicebook.server.ApplicationKt")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // ---- Ktor server ----
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.serialization.kotlinx.json)

    // ---- Ktor client：调上游（Artalk / Miniflux / calibre / LLDAP）----
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)

    // ---- LLDAP：建号走 GraphQL，设密码必须走 LDAP 协议 ----
    implementation(libs.unboundid.ldapsdk)

    // ---- 基础库 ----
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback.classic)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
}
