package us.wangxy.voicebook

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import us.wangxy.voicebook.di.initKoin

fun main() {
    initKoin()

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "KMP-App-Template",
        ) {
            App()
        }
    }
}
