package us.wangxy.voicebook.server.bff.lldap

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import us.wangxy.voicebook.server.bff.BffException
import us.wangxy.voicebook.server.bff.BffJson
import us.wangxy.voicebook.server.bff.config.LldapConfig

/** Account records in LLDAP (the single source of identities). Passwords live in [DirectoryPasswords]. */
interface DirectoryAdmin {
    suspend fun userExists(userId: String): Boolean
    suspend fun createUser(userId: String, email: String, displayName: String)
    suspend fun addUserToGroups(userId: String, groupDisplayNames: List<String>)
    suspend fun deleteUser(userId: String)
}

class LldapDuplicateException(message: String) : RuntimeException(message)

/**
 * LLDAP GraphQL admin client. Schema notes: groups expose `displayName` (there is no `name`),
 * `addUserToGroup(userId: String!, groupId: Int!)` and `deleteUser` return `Success { ok }`.
 */
class LldapGraphqlClient(
    private val http: HttpClient,
    private val config: LldapConfig,
    private val clock: () -> Long = System::currentTimeMillis,
) : DirectoryAdmin {

    private val loginMutex = Mutex()
    private var token: String? = null
    private var tokenObtainedAt = 0L

    override suspend fun userExists(userId: String): Boolean {
        val result = graphql(
            "query(\$id: String!) { user(userId: \$id) { id } }",
            buildJsonObject { put("id", userId) },
            failOnErrors = false,
        )
        val user = result.data?.jsonObject?.get("user")
        return user != null && user !is JsonNull
    }

    override suspend fun createUser(userId: String, email: String, displayName: String) {
        val result = graphql(
            "mutation(\$user: CreateUserInput!) { createUser(user: \$user) { id } }",
            buildJsonObject {
                putJsonObject("user") {
                    put("id", userId)
                    put("email", email)
                    put("displayName", displayName)
                }
            },
            failOnErrors = false,
        )
        result.errorMessage?.let { message ->
            if (message.contains("UNIQUE", ignoreCase = true) || message.contains("exist", ignoreCase = true)) {
                throw LldapDuplicateException("用户名或邮箱已被占用")
            }
            throw BffException.upstream("lldap", "创建账号失败: $message")
        }
    }

    override suspend fun addUserToGroups(userId: String, groupDisplayNames: List<String>) {
        if (groupDisplayNames.isEmpty()) return
        val groups = graphql("query { groups { id displayName } }").data!!
            .jsonObject.getValue("groups").jsonArray
            .associate { g ->
                g.jsonObject.getValue("displayName").jsonPrimitive.content to g.jsonObject.getValue("id").jsonPrimitive.int
            }
        for (name in groupDisplayNames) {
            val groupId = groups[name] ?: throw BffException.upstream("lldap", "LLDAP 中不存在分组 $name")
            graphql(
                "mutation(\$userId: String!, \$groupId: Int!) { addUserToGroup(userId: \$userId, groupId: \$groupId) { ok } }",
                buildJsonObject {
                    put("userId", userId)
                    put("groupId", groupId)
                },
            )
        }
    }

    override suspend fun deleteUser(userId: String) {
        graphql(
            "mutation(\$userId: String!) { deleteUser(userId: \$userId) { ok } }",
            buildJsonObject { put("userId", userId) },
        )
    }

    private class GraphqlResult(val data: JsonElement?, val errorMessage: String?)

    private suspend fun graphql(query: String, variables: JsonObject? = null, failOnErrors: Boolean = true): GraphqlResult {
        val body = buildJsonObject {
            put("query", query)
            if (variables != null) put("variables", variables)
        }.toString()

        var response = postGraphql(body, adminToken(forceRefresh = false))
        if (response.status == HttpStatusCode.Unauthorized) {
            response = postGraphql(body, adminToken(forceRefresh = true))
        }
        if (!response.status.isSuccess()) {
            throw BffException.upstream("lldap", "LLDAP GraphQL HTTP ${response.status.value}")
        }
        val json = BffJson.parseToJsonElement(response.bodyAsText()).jsonObject
        val errors = (json["errors"] as? JsonArray)
            ?.joinToString("; ") { it.jsonObject["message"]?.jsonPrimitive?.contentOrNull ?: it.toString() }
        if (errors != null && failOnErrors) throw BffException.upstream("lldap", "LLDAP: $errors")
        return GraphqlResult(json["data"]?.takeIf { it !is JsonNull }, errors)
    }

    private suspend fun postGraphql(body: String, token: String): HttpResponse = try {
        http.post("${config.httpUrl}/api/graphql") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw BffException.upstream("lldap", "无法连接 LLDAP", e)
    }

    private suspend fun adminToken(forceRefresh: Boolean): String = loginMutex.withLock {
        val current = token
        if (!forceRefresh && current != null && clock() - tokenObtainedAt < TOKEN_REUSE_MS) return current
        val response = try {
            http.post("${config.httpUrl}/auth/simple/login") {
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("username", config.adminUser)
                        put("password", config.adminPassword)
                    }.toString(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BffException.upstream("lldap", "无法连接 LLDAP", e)
        }
        if (!response.status.isSuccess()) {
            throw BffException.upstream("lldap", "LLDAP 管理员登录失败 HTTP ${response.status.value}")
        }
        val fresh = BffJson.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("token").jsonPrimitive.content
        token = fresh
        tokenObtainedAt = clock()
        fresh
    }

    private companion object {
        /** LLDAP JWTs default to 1 day; re-login well before that. */
        const val TOKEN_REUSE_MS = 6 * 60 * 60 * 1000L
    }
}
