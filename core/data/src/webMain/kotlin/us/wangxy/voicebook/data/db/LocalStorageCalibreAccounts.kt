package us.wangxy.voicebook.data.db

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import us.wangxy.voicebook.data.CalibreAccountStore
import us.wangxy.voicebook.reader.api.CalibreServerAccount

/** localStorage 已保存的 calibre 服务器账号列表。 */
internal class LocalCalibreAccounts(
    private val raw: (key: String) -> String?,
    private val write: (key: String, value: String) -> Unit,
) : CalibreAccountStore {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun saved(): List<CalibreServerAccount> {
        val value = raw(KEY) ?: return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(CalibreServerAccount.serializer()), value)
        }.getOrDefault(emptyList())
    }

    override suspend fun upsert(account: CalibreServerAccount) {
        write(KEY, json.encodeToString(ListSerializer(CalibreServerAccount.serializer()), saved().filterNot { it.id == account.id } + account))
    }

    override suspend fun delete(id: String) {
        write(KEY, json.encodeToString(ListSerializer(CalibreServerAccount.serializer()), saved().filterNot { it.id == id }))
    }

    private companion object {
        const val KEY = "voicebook.calibreAccounts"
    }
}
