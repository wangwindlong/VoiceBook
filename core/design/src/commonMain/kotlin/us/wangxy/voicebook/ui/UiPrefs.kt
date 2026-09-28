package us.wangxy.voicebook.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 底部主导航 tab。 */
enum class BottomTab { READING, RSS, AI, MINE }

/** 阅读 tab 内部顶部分页。书架(本地书+继续读)在前,书城(calibre 远程浏览)在后。 */
enum class ReadingTab { SHELF, LIBRARY }

/**
 * 跨启动保留的 UI 偏好：上次所在的底部 tab、阅读内页、快捷面板把手位置与配置。
 */
data class UiPrefsState(
    val bottomTab: BottomTab = BottomTab.READING,
    val readingTab: ReadingTab = ReadingTab.SHELF,
    /** 快捷面板把手的垂直位置，占可用屏高的比例（0..1）；负值表示未设置过。 */
    val quickPanelOffsetY: Float = -1f,
    /** 快捷面板启用的动作 id（有序）；空列表表示使用默认配置。 */
    val quickPanelItems: List<String> = emptyList(),
)

interface UiPrefsStore {
    fun load(): UiPrefsState
    fun save(state: UiPrefsState)
}

expect fun createUiPrefsStore(): UiPrefsStore

class UiPrefsController(private val store: UiPrefsStore) {
    private val stateFlow = MutableStateFlow(store.load())
    val prefs: StateFlow<UiPrefsState> = stateFlow.asStateFlow()

    /** 当前值（同步可得，用于冷启动 startDestination / initialPage）。 */
    val current: UiPrefsState get() = stateFlow.value

    fun setBottomTab(tab: BottomTab) = update { it.copy(bottomTab = tab) }

    fun setReadingTab(tab: ReadingTab) = update { it.copy(readingTab = tab) }

    fun setQuickPanelOffsetY(ratio: Float) = update { it.copy(quickPanelOffsetY = ratio) }

    fun setQuickPanelItems(items: List<String>) = update { it.copy(quickPanelItems = items) }

    private fun update(transform: (UiPrefsState) -> UiPrefsState) {
        val value = transform(stateFlow.value)
        stateFlow.value = value
        store.save(value)
    }
}

internal inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
    runCatching { enumValueOf<T>(this ?: "") }.getOrDefault(default)

internal fun String.parseItems(): List<String> =
    split(',').map { it.trim() }.filter { it.isNotEmpty() }

internal fun List<String>.joinItems(): String = joinToString(",")
