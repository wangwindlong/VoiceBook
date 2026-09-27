package us.wangxy.voicebook

import android.app.Application
import android.content.Context
import android.util.Log
import us.wangxy.voicebook.di.initKoin
import org.koin.dsl.module
import java.io.File

class VoiceBookApp : Application() {
    override fun onCreate() {
        super.onCreate()
//        installBundledArchive()
        initKoin(
            platformModules = listOf(module { single<Context> { this@VoiceBookApp } }),
        )
    }

    /**
     * Copies `assets/VoiceBook.7z` into the app's internal files directory the first time
     * the process starts after install. A later launch leaves the existing file in place.
     */
    private fun installBundledArchive() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val dest = File(filesDir, ARCHIVE_NAME)
        if (prefs.getBoolean(INSTALLED_KEY, false) && dest.isFile && dest.length() > 0L) return
        val temp = File(filesDir, "$ARCHIVE_NAME.part")
        try {
            assets.open(ARCHIVE_NAME).use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            if (dest.exists() && !dest.delete()) return
            if (!temp.renameTo(dest)) return
            prefs.edit().putBoolean(INSTALLED_KEY, true).apply()
        } catch (error: Exception) {
            temp.delete()
            Log.e(TAG, "Failed to copy $ARCHIVE_NAME into internal storage", error)
        }
    }

    private companion object {
        const val TAG = "VoiceBookApp"
        const val PREFS = "bootstrap"
        const val INSTALLED_KEY = "voicebook_7z_installed"
        const val ARCHIVE_NAME = "VoiceBook.7z"
    }
}
