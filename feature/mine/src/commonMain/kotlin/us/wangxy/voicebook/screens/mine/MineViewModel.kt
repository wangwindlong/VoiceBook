package us.wangxy.voicebook.screens.mine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import us.wangxy.voicebook.data.BookRepository
import us.wangxy.voicebook.reader.api.CalibreServer
import us.wangxy.voicebook.reader.api.CalibreServerAccount
import us.wangxy.voicebook.reader.api.CalibreWebApi

/**
 * 「我的」页：calibre-web 服务器设置（自书城的设置弹窗迁来）、外观、崩溃日志与调试入口。
 * 保存/切换服务器走 [BookRepository]，书城/书架通过 serverVersion 流自动重载。
 * 这里编辑的始终是手动配置的服务器；已登录统一账号时书库实际走 BFF，退出后才用它。
 */
class MineViewModel(
    private val repository: BookRepository,
    private val api: CalibreWebApi,
) : ViewModel() {

    private val serverFlow = MutableStateFlow<CalibreServer?>(null)
    val server: StateFlow<CalibreServer?> = serverFlow.asStateFlow()

    private val savedServersFlow = MutableStateFlow<List<CalibreServerAccount>>(emptyList())
    val savedServers: StateFlow<List<CalibreServerAccount>> = savedServersFlow.asStateFlow()

    private val testingFlow = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = testingFlow.asStateFlow()

    private val testResultFlow = MutableStateFlow<String?>(null)
    val testResult: StateFlow<String?> = testResultFlow.asStateFlow()

    init {
        viewModelScope.launch {
            serverFlow.value = repository.configuredServer()
            refreshSavedServers()
        }
    }

    fun refreshSavedServers() {
        viewModelScope.launch { savedServersFlow.value = repository.savedServers() }
    }

    /** 切换到已保存的服务器：替换生效配置、清缓存（书城/书架经 serverVersion 自动刷新）。 */
    fun activateServerAccount(id: String) {
        viewModelScope.launch {
            if (repository.activateServerAccount(id)) {
                serverFlow.value = repository.configuredServer()
                refreshSavedServers()
            }
        }
    }

    fun deleteServerAccount(id: String) {
        viewModelScope.launch {
            repository.deleteServerAccount(id)
            refreshSavedServers()
        }
    }

    /** Saves the config (persisted by the repository) and bumps serverVersion. */
    fun saveServer(server: CalibreServer) {
        viewModelScope.launch {
            repository.saveServer(server)
            serverFlow.value = server
            refreshSavedServers()
        }
    }

    fun testConnection(server: CalibreServer) {
        testingFlow.value = true
        testResultFlow.value = null
        viewModelScope.launch {
            val result = runCatching { api.ping(server) }
            testingFlow.value = false
            testResultFlow.value = if (result.getOrDefault(false)) "连接成功" else connectFailureHint(result.exceptionOrNull())
        }
    }

    /** 把连接层异常翻译成可操作的提示（证书不匹配/解析失败/连不上），兜底保留原始信息。 */
    private fun connectFailureHint(e: Throwable?): String {
        val name = e?.let { it::class.simpleName }.orEmpty()
        val text = e?.message.orEmpty()
        return when {
            name.contains("SSL") || text.contains("certificate", ignoreCase = true) || text.contains("SSL", ignoreCase = false) ->
                "HTTPS 证书与地址不匹配，请改用证书签发的域名（如 nas.wangxy.us）或修正服务器证书"
            name.contains("UnknownHost") -> "域名无法解析，请检查服务器地址"
            name.contains("Connect") -> "无法连接服务器，请检查地址、端口与网络"
            e?.message != null -> e.message.orEmpty()
            else -> "连接失败，请检查地址/账号"
        }
    }
}
