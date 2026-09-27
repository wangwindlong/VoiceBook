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
            serverFlow.value = repository.server()
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
                serverFlow.value = repository.server()
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
            val ok = runCatching { api.ping(server) }.getOrDefault(false)
            testingFlow.value = false
            testResultFlow.value = if (ok) "连接成功" else "连接失败，请检查地址/账号"
        }
    }
}
