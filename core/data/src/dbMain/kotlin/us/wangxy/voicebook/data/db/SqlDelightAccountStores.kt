package us.wangxy.voicebook.data.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import us.wangxy.voicebook.data.rss.RssSavedAccountStore
import us.wangxy.voicebook.db.VoiceBookDatabase
import us.wangxy.voicebook.rss.RssSavedAccount
import us.wangxy.voicebook.rss.RssSyncMode
import us.wangxy.voicebook.reader.api.CalibreServerAccount

/** SQLDelight-backed account lists (android/ios/jvm). */
internal class SqlDelightAccountStores(private val db: VoiceBookDatabase) {

    val calibreAccounts = CalibreAccounts()
    val rssSavedAccounts = RssSaved()

    inner class CalibreAccounts : us.wangxy.voicebook.data.CalibreAccountStore {

        override suspend fun saved(): List<CalibreServerAccount> = withContext(Dispatchers.IO) {
            db.savedServerQueries.selectSavedServers().executeAsList().map { row ->
                CalibreServerAccount(
                    id = row.id,
                    label = row.label,
                    baseUrl = row.baseUrl,
                    username = row.username,
                    password = row.password,
                    lastUsedAt = row.lastUsedAt,
                )
            }
        }

        override suspend fun upsert(account: CalibreServerAccount) = withContext(Dispatchers.IO) {
            db.savedServerQueries.upsertSavedServer(
                id = account.id,
                label = account.label,
                baseUrl = account.baseUrl,
                username = account.username,
                password = account.password,
                lastUsedAt = account.lastUsedAt,
            )
            Unit
        }

        override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
            db.savedServerQueries.deleteSavedServer(id)
            Unit
        }
    }

    inner class RssSaved : RssSavedAccountStore {

        override suspend fun saved(): List<RssSavedAccount> = withContext(Dispatchers.IO) {
            db.savedRssAccountQueries.selectSavedRssAccounts().executeAsList().map { row ->
                RssSavedAccount(
                    id = row.id,
                    label = row.label,
                    mode = RssSyncMode.valueOf(row.mode),
                    serverUrl = row.serverUrl,
                    token = row.token,
                    lastUsedAt = row.lastUsedAt,
                )
            }
        }

        override suspend fun upsert(account: RssSavedAccount) = withContext(Dispatchers.IO) {
            db.savedRssAccountQueries.upsertSavedRssAccount(
                id = account.id,
                label = account.label,
                mode = account.mode.name,
                serverUrl = account.serverUrl,
                token = account.token,
                lastUsedAt = account.lastUsedAt,
            )
            Unit
        }

        override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
            db.savedRssAccountQueries.deleteSavedRssAccount(id)
            Unit
        }
    }
}
