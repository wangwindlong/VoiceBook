package us.wangxy.voicebook.audio.di

import org.koin.core.module.Module
import org.koin.dsl.module
import us.wangxy.voicebook.audio.AudioPlayer
import us.wangxy.voicebook.audio.createAudioPlayer

val audioModule: Module = module {
    single { createAudioPlayer() }
}
