package us.wangxy.voicebook.ui

actual fun createUiPrefsStore(): UiPrefsStore = WebUiPrefsStore

private object WebUiPrefsStore : UiPrefsStore {
    private var state = UiPrefsState()

    override fun load(): UiPrefsState = state

    override fun save(state: UiPrefsState) {
        this.state = state
    }
}
