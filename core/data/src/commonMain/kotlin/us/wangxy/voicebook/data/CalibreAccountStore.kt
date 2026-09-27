package us.wangxy.voicebook.data

import us.wangxy.voicebook.reader.api.CalibreServerAccount

/** 已保存的 calibre-web 服务器账号列表（多账号记录与切换）。 */
interface CalibreAccountStore {
    suspend fun saved(): List<CalibreServerAccount>
    suspend fun upsert(account: CalibreServerAccount)
    suspend fun delete(id: String)
}
