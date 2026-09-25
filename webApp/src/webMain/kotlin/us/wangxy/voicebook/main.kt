package us.wangxy.voicebook

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import us.wangxy.voicebook.di.initKoin

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    initKoin()

    ComposeViewport(viewportContainerId = "composeTarget") {
        App()
    }
}
