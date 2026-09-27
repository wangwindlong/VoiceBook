package us.wangxy.voicebook.di

import org.koin.core.module.Module
import us.wangxy.voicebook.data.LocalLibrary

/**
 * Registers the [LocalLibrary] singleton for this platform: SQLDelight-backed on
 * android/ios/jvm (actual lives in the dbMain source set), IndexedDB-backed on
 * js/wasmJs (actual in webMain).
 */
expect fun platformDatabaseModule(): Module
