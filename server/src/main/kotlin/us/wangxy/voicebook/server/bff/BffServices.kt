package us.wangxy.voicebook.server.bff

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import us.wangxy.voicebook.server.bff.profile.ProfileService
import us.wangxy.voicebook.bff.contract.BffComponents
import us.wangxy.voicebook.bff.contract.ProvisionResult
import us.wangxy.voicebook.server.bff.account.AccountService
import us.wangxy.voicebook.server.bff.account.Provisioner
import us.wangxy.voicebook.server.bff.artalk.ArtalkGateway
import us.wangxy.voicebook.server.bff.auth.OidcLoginClient
import us.wangxy.voicebook.server.bff.auth.OidcTokenVerifier
import us.wangxy.voicebook.server.bff.calibre.CalibreLibrary
import us.wangxy.voicebook.server.bff.calibre.CwaProgressMirror
import us.wangxy.voicebook.server.bff.calibre.CalibreWebLogin
import us.wangxy.voicebook.server.bff.config.BffConfig
import us.wangxy.voicebook.server.bff.config.SecurityConfig
import us.wangxy.voicebook.server.bff.lldap.LdapPasswords
import us.wangxy.voicebook.server.bff.lldap.LldapGraphqlClient
import us.wangxy.voicebook.server.bff.miniflux.MinifluxGateway
import us.wangxy.voicebook.server.bff.miniflux.SharedRssService
import us.wangxy.voicebook.server.bff.miniflux.UserRssStore

/** Object graph of the BFF; tests build it with fakes / MockEngine instead of [create]. */
class BffServices(
    val verifier: OidcTokenVerifier,
    val oidcLogin: OidcLoginClient,
    val accounts: AccountService,
    val miniflux: MinifluxGateway,
    val rss: SharedRssService,
    val artalk: ArtalkGateway,
    val calibreLibrary: CalibreLibrary,
    val calibreProgress: CwaProgressMirror,
    val security: SecurityConfig,
    private val http: HttpClient? = null,
    val content: us.wangxy.voicebook.server.bff.content.ContentStore? = null,
    val background: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {
    val mirrorMutex = kotlinx.coroutines.sync.Mutex()
    val profile = content?.let { ProfileService(it, calibreLibrary, rss) }
    init { rss.content = content; rss.profile = profile }


    override fun close() {
        background.cancel()
        http?.close()
    }

    companion object {
        fun create(config: BffConfig): BffServices {
            val http = upstreamHttpClient()
            val miniflux = MinifluxGateway(http, config.miniflux)
            val rss = SharedRssService(miniflux, UserRssStore(config.miniflux.stateDb), config.miniflux.defaultFeeds)
            val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val calibreProgress = CwaProgressMirror(config.calibre)
            val calibreLogin = CalibreWebLogin(config.calibre)
            val provisioners = mapOf(
                BffComponents.MINIFLUX to Provisioner { username, _ ->
                    // Backgrounded: Miniflux's POST /feeds waits for the first fetch of each feed,
                    // which is slow enough to make nginx return 504 on the register request.
                    // Feeds are subscribed once on the shared account; the user just gets linked.
                    background.launch { rss.subscribeDefaults(username) }
                    ProvisionResult(true)
                },
                BffComponents.CALIBRE to Provisioner { username, password ->
                    when {
                        !calibreLogin.login(username, password) ->
                            ProvisionResult(false, "calibre 拒绝登录，检查 CWA 的 LDAP 配置与用户分组")
                        !calibreProgress.userExists(username) ->
                            ProvisionResult(false, "calibre 登录成功但未建号，检查 config_ldap_auto_create_users")
                        else -> ProvisionResult(true)
                    }
                },
            )
            val services = BffServices(
                verifier = OidcTokenVerifier(http, config.oidc),
                oidcLogin = OidcLoginClient(http, config.oidc),
                accounts = AccountService(
                    directory = LldapGraphqlClient(http, config.lldap),
                    passwords = LdapPasswords(config.lldap),
                    defaultGroups = config.lldap.defaultGroups,
                    security = config.security,
                    provisioners = provisioners,
                ),
                miniflux = miniflux,
                rss = rss,
                artalk = ArtalkGateway(http, config.artalk),
                calibreLibrary = CalibreLibrary(config.calibre),
                calibreProgress = calibreProgress,
                security = config.security,
                http = http,
                background = background,
                content = us.wangxy.voicebook.server.bff.content.ContentStore(
                    java.io.File(config.miniflux.stateDb.absoluteFile.parentFile, "content.db"),
                    java.io.File(config.miniflux.stateDb.absoluteFile.parentFile, "books")),
            )
            background.launch {
                delay(60_000)
                while (isActive) {
                    try { services.profile?.rebuildAll() }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (e: Exception) { org.slf4j.LoggerFactory.getLogger(BffServices::class.java).warn("profile rebuild failed", e) }
                    delay(30 * 60_000L)
                }
            }
            return services
        }
    }
}
