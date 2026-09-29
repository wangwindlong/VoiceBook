package us.wangxy.voicebook.server.lldap

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import us.wangxy.voicebook.server.ServerConfig

/**
 * LLDAP 的 GraphQL 客户端：建号、建组、入组。
 *
 * **它不能设密码**——LLDAP 的 `createUser`/`updateUser` 输入里没有 password 字段
 * （OPAQUE 零知识证明协议），密码只能经 LDAP 协议写入，见 [LdapPasswordService]。
 *
 * 端点固定 `/api/graphql`（`/graphql` 会返回前端 SPA 的 HTML）。
 */
class LldapGraphQlClient(
    private val config: ServerConfig,
    private val client: HttpClient,
) {
    private val log = LoggerFactory.getLogger(LldapGraphQlClient::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val tokenMutex = Mutex()
    @Volatile
    private var token: String? = null

    /** 登录拿管理 JWT（LDAP 管理员的同一套账号密码） */
    private suspend fun login(): String {
        val body = buildJsonObject {
            put("username", "admin")
            put("password", config.lldapAdminPassword)
        }
        val resp = client.post("${config.lldapHttpUrl}/auth/simple/login") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = resp.bodyAsText()
        if (!resp.status.isSuccess()) {
            error("LLDAP 登录失败 HTTP ${resp.status.value}: ${text.take(200)}")
        }
        val obj = json.parseToJsonElement(text).jsonObject
        return obj["token"]?.jsonPrimitive?.content
            ?: error("LLDAP 登录响应里没有 token")
    }

    private suspend fun currentToken(): String = token ?: tokenMutex.withLock {
        token ?: login().also { token = it }
    }

    /** 执行一次 GraphQL；401 时自动重登一次 */
    private suspend fun graphql(query: String, variables: JsonObject? = null): JsonObject {
        suspend fun call(tokenValue: String): Pair<Boolean, String> {
            val body = buildJsonObject {
                put("query", query)
                if (variables != null) put("variables", variables)
            }
            val resp = client.post("${config.lldapHttpUrl}/api/graphql") {
                header("Authorization", "Bearer $tokenValue")
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
            return resp.status.isSuccess() to resp.bodyAsText()
        }

        var (ok, text) = call(currentToken())
        if (!ok && text.contains("401") || !ok && text.contains("Unauthorized")) {
            // token 过期，重登一次
            token = null
            val fresh = currentToken()
            val retry = call(fresh)
            ok = retry.first
            text = retry.second
        }
        if (!ok) error("LLDAP GraphQL 失败: ${text.take(300)}")

        val obj = json.parseToJsonElement(text).jsonObject
        obj["errors"]?.let { errs ->
            val msg = (errs as? JsonArray)?.firstOrNull()?.jsonObject?.get("message")
                ?.jsonPrimitive?.content
            if (!msg.isNullOrBlank()) error("LLDAP GraphQL 报错: $msg")
        }
        return obj["data"]?.jsonObject ?: JsonObject(emptyMap())
    }

    /** 建号。注意：建出来的用户**还没有密码** */
    suspend fun createUser(username: String, email: String, displayName: String?) {
        val vars = buildJsonObject {
            put(
                "user",
                buildJsonObject {
                    put("id", username)
                    put("email", email)
                    put("displayName", displayName ?: username)
                    put("attributes", JsonArray(emptyList()))
                },
            )
        }
        graphql(
            """
            mutation CreateUser(${'$'}user: CreateUserInput!) {
              createUser(user: ${'$'}user) { id }
            }
            """.trimIndent(),
            vars,
        )
        log.info("LLDAP 建号成功: $username")
    }

    /** 列出所有组：displayName → id（LLDAP 的 Group 上字段是 displayName，没有 name） */
    suspend fun groups(): Map<String, Int> {
        val data = graphql("{ groups { id displayName } }")
        val arr = data["groups"] as? JsonArray ?: return emptyMap()
        return arr.mapNotNull { el ->
            val o = el.jsonObject
            val name = o["displayName"]?.jsonPrimitive?.content
            val id = (o["id"] as? JsonPrimitive)?.content?.toIntOrNull()
            if (name != null && id != null) name to id else null
        }.toMap()
    }

    /** 确保组存在，返回组 id */
    suspend fun ensureGroup(name: String): Int {
        groups()[name]?.let { return it }
        val data = graphql(
            """
            mutation CreateGroup(${'$'}name: String!) {
              createGroup(name: ${'$'}name) { id }
            }
            """.trimIndent(),
            buildJsonObject { put("name", name) },
        )
        log.info("LLDAP 创建组: $name")
        return (data["createGroup"]?.jsonObject?.get("id") as? JsonPrimitive)?.content?.toIntOrNull()
            ?: error("创建组后拿不到 id")
    }

    /** 删号（注册失败回滚用）。参数名是 userId，返回 Success{ok} */
    suspend fun deleteUser(username: String) {
        graphql(
            """
            mutation DeleteUser(${'$'}userId: String!) {
              deleteUser(userId: ${'$'}userId) { ok }
            }
            """.trimIndent(),
            buildJsonObject { put("userId", username) },
        )
        log.info("LLDAP 已删除用户: $username")
    }

    /** 把用户加入组（幂等：已在组里不报错）。参数名 userId/groupId，返回 Success{ok} */
    suspend fun addUserToGroup(username: String, group: String) {
        val groupId = ensureGroup(group)
        try {
            graphql(
                """
                mutation AddToGroup(${'$'}userId: String!, ${'$'}groupId: Int!) {
                  addUserToGroup(userId: ${'$'}userId, groupId: ${'$'}groupId) { ok }
                }
                """.trimIndent(),
                buildJsonObject {
                    put("userId", username)
                    put("groupId", groupId)
                },
            )
            log.info("LLDAP 用户 $username 已加入组 $group")
        } catch (e: Exception) {
            // 已在组里会报错，忽略
            log.debug("加入组 $group 未成功（可能已在组内）: ${e.message}")
        }
    }
}
