package us.wangxy.voicebook.screens.reader

import kotlin.random.Random

/**
 * 评论列表里「阅读进度 / 阅读时长 / 笔记条数」三个字段的载体。
 *
 * Artalk 只存评论本身，没有这三项；等 BFF/服务端补齐真实数据后，
 * 在 Koin 里把 [MockCommentExtrasProvider] 换成真实实现即可，UI 只依赖本接口。
 */
data class CommentExtras(
    /** 已读进度百分比 0..100。TODO(real-data): 该读者在此书的真实阅读进度（calibre progress）。 */
    val progressPercent: Int,
    /** 累计阅读时长（分钟）。TODO(real-data): 真实累计时长（本地统计或服务端下发）。 */
    val readMinutes: Int,
    /** 笔记条数。TODO(real-data): 笔记功能上线后接入真实条数。 */
    val noteCount: Int,
)

/** 评论附加信息（进度/时长/笔记）来源，UI 只依赖本接口。 */
interface CommentExtrasProvider {
    fun extrasFor(commentId: Long, pageKey: String): CommentExtras
}

/**
 * 假数据实现（当前唯一实现）：以 commentId + pageKey 做种子**确定性**生成，
 * 同一条评论每次看到同一组数值，滚动/重进列表不会乱跳。
 * 区间参考：进度 5%~95%、时长 3~180 分钟、笔记 0~12 条。
 */
class MockCommentExtrasProvider : CommentExtrasProvider {
    override fun extrasFor(commentId: Long, pageKey: String): CommentExtras {
        // kotlin.random.Random(seed) 结果与平台无关，这里要的就是确定性而非随机感
        val random = Random(commentId * 31L + pageKey.hashCode())
        return CommentExtras(
            progressPercent = random.nextInt(5, 96),
            readMinutes = random.nextInt(3, 181),
            noteCount = random.nextInt(0, 13),
        )
    }
}
