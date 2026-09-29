package us.wangxy.voicebook.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import us.wangxy.voicebook.bff.BffAuthApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MemoryStore(var saved: AuthState = AuthState()) : AuthStore {
    override fun load() = saved
    override fun save(state: AuthState) { saved = state }
}

private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK): HttpResponseData =
    respond(body, status, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))

class AuthControllerTest {
    private val calls = mutableListOf<String>()
    private var now = 1_000L
    private var refreshStatus = HttpStatusCode.OK
    private var logoutStatus = HttpStatusCode.NoContent

    private val engine = MockEngine { req ->
        calls += req.url.encodedPath
        when (req.url.encodedPath) {
            "/api/auth/login" -> {
                val body = (req.body as io.ktor.http.content.TextContent).text
                if (body.contains("\"password\":\"passw0rd1\"")) {
                    json("""{"tokens":{"accessToken":"at1","refreshToken":"rt1","expiresIn":3600},"user":{"username":"alice","displayName":"Alice","email":"a@x.io"}}""")
                } else {
                    json("""{"code":"INVALID_CREDENTIALS","message":"用户名或密码错误"}""", HttpStatusCode.Unauthorized)
                }
            }
            "/api/auth/register" -> json("""{"username":"alice","provisioning":{}}""", HttpStatusCode.Created)
            "/api/auth/refresh" ->
                if (refreshStatus == HttpStatusCode.OK) json("""{"accessToken":"at2","refreshToken":"rt2","expiresIn":3600}""")
                else json("""{"code":"SESSION_EXPIRED","message":"登录已过期，请重新登录"}""", refreshStatus)
            "/api/auth/password" -> respond("", HttpStatusCode.NoContent)
            "/api/auth/logout" -> respond("", logoutStatus)
            else -> json("{}", HttpStatusCode.NotFound)
        }
    }

    private val store = MemoryStore()
    private val controller = AuthController(
        api = BffAuthApi(HttpClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }),
        store = store,
        nowEpochSeconds = { now },
    )

    @Test
    fun loginPersistsUserAndSession() = runTest {
        assertNull(controller.login(" alice ", "passw0rd1"))
        val state = controller.state.value
        assertEquals("Alice", state.currentUser?.nickname)
        assertEquals(AuthSession("at1", "rt1", 1_000L + 3600), state.session)
        assertEquals(state, decodeAuthState(store.saved.encode()))
    }

    @Test
    fun loginErrorUsesServerMessage() = runTest {
        assertEquals("用户名或密码错误", controller.login("alice", "wrong"))
        assertNull(controller.state.value.session)
        assertEquals("请输入密码", controller.login("alice", ""))
    }

    @Test
    fun accessTokenIsRefreshedNearExpiry() = runTest {
        controller.login("alice", "passw0rd1")
        assertEquals("at1", controller.validAccessToken())
        now += 3600 - 30
        assertEquals("at2", controller.validAccessToken())
        assertEquals("rt2", controller.state.value.session?.refreshToken)
    }

    @Test
    fun expiredRefreshTokenSignsOut() = runTest {
        controller.login("alice", "passw0rd1")
        now += 4000
        refreshStatus = HttpStatusCode.Unauthorized
        assertNull(controller.validAccessToken())
        assertNull(controller.state.value.currentUser)
    }

    @Test
    fun logoutClearsLocallyEvenIfServerFails() = runTest {
        controller.login("alice", "passw0rd1")
        logoutStatus = HttpStatusCode.BadGateway
        controller.logout()
        assertNull(controller.state.value.session)
        assertTrue("/api/auth/logout" in calls)
    }

    @Test
    fun registerThenAutoLogin() = runTest {
        assertEquals("两次输入的密码不一致", controller.register("alice", "", "a@x.io", "passw0rd1", "passw0rd2"))
        assertEquals("密码需同时包含字母和数字", controller.register("alice", "", "a@x.io", "password", "password"))
        assertNull(controller.register("alice", "", "a@x.io", "passw0rd1", "passw0rd1"))
        assertEquals(listOf("/api/auth/register", "/api/auth/login"), calls)
        assertEquals("alice", controller.state.value.currentUser?.username)
    }

    @Test
    fun changePasswordUsesBearerSession() = runTest {
        assertEquals("登录已过期，请重新登录", controller.changePassword("old1pass", "newpass12", "newpass12"))
        controller.login("alice", "passw0rd1")
        assertNull(controller.changePassword("passw0rd1", "newpass12", "newpass12"))
    }

    @Test
    fun legacyLocalAccountFormatDecodesAsSignedOut() {
        val state = decodeAuthState("current=bob\nbob\tBob\tsecret\n")
        assertNull(state.currentUser)
        assertEquals(DEFAULT_BFF_URL, state.serverUrl)
    }
}
